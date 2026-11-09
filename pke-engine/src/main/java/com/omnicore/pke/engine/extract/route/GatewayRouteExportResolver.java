package com.omnicore.pke.engine.extract.route;

import com.omnicore.pke.engine.extract.ApiOperations;
import com.omnicore.pke.engine.extract.SqlInserts;
import com.omnicore.pke.engine.source.FileSystemDocumentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads gateway route exports: the {@code INSERT INTO ocs_t_gateway_api_route (...)} scripts kept in
 * {@code dkn-db-config-master} (columns {@code "method"}, {@code uri}, {@code target_url}, {@code replace_uri},
 * {@code status}, {@code route_name}, {@code api_route_id}). A gateway API matches an active row with the same
 * method and URI; the target service is the host of {@code target_url} without the {@code -svc} suffix, and the
 * forwarded path is {@code replace_uri} (or the URI itself). The handler is the service API with that path.
 */
public final class GatewayRouteExportResolver implements RouteResolver {

    public static final String METHOD = "route.gateway-export";

    private record Route(String method, String uri, String service, String targetPath, String name, String id, int line) {
    }

    private final List<Path> files;
    private final String table;

    public GatewayRouteExportResolver(List<Path> files, String table) {
        this.files = files;
        this.table = table;
    }

    @Override
    public String id() {
        return METHOD;
    }

    @Override
    public Result resolve(List<GatewayApi> apis, List<ServiceHandler> handlers, Set<String> alreadyResolved) {
        List<Resolution> resolved = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        for (Path file : files) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read route export " + file, e);
            }
            String scope = file.getFileName().toString();
            scopes.add(scope);
            String version = FileSystemDocumentStore.sha256(text.getBytes(StandardCharsets.UTF_8));
            List<Route> routes = new ArrayList<>();
            for (SqlInserts.Row row : SqlInserts.read(text, table)) {
                String status = row.get("status");
                if (status != null && !status.equalsIgnoreCase("ACT") && !status.equalsIgnoreCase("ACTIVE")) {
                    continue;
                }
                String uri = row.get("uri");
                String target = row.get("target_url");
                if (uri == null || target == null) {
                    continue;
                }
                String replace = row.get("replace_uri");
                routes.add(new Route(String.valueOf(row.get("method")).toUpperCase(Locale.ROOT), ApiOperations.normalisePath(uri),
                        serviceOf(target), ApiOperations.normalisePath(replace == null || replace.isBlank() ? uri : replace),
                        row.get("route_name"), row.get("api_route_id"), row.line()));
            }
            for (GatewayApi api : apis) {
                if (alreadyResolved.contains(api.id())) {
                    continue;
                }
                List<Route> candidates = routes.stream().filter(r -> r.method().equals(api.httpMethod())
                        && RouteResolver.matches(r.uri(), api.pathTemplate())).toList();
                if (candidates.isEmpty()) {
                    continue;
                }
                Route route = candidates.getFirst();
                List<ServiceHandler> matching = handlers.stream().filter(h -> h.service().equals(route.service())
                        && h.httpMethod().equals(api.httpMethod()) && RouteResolver.matches(h.pathTemplate(), route.targetPath()))
                        .toList();
                if (RouteResolver.distinctHandlers(matching).size() == 1) {
                    resolved.add(new Resolution(api, matching.getFirst(), METHOD, "routes:" + scope, version, scope,
                            scope + ":" + route.line() + " (api_route_id " + route.id() + ")", route.name()));
                } else {
                    unmatched.add(new Unmatched(api, matching.isEmpty()
                            ? "route " + route.id() + " forwards to " + route.service() + " " + route.targetPath()
                            + ", but no controller mapping of that service matches (service not ingested?)"
                            : "route " + route.id() + " matches " + matching.size() + " handlers"));
                }
            }
        }
        return new Result(resolved, unmatched, scopes);
    }

    /** {@code http://international-transfer-service-svc:80} gives {@code international-transfer-service}. */
    static String serviceOf(String targetUrl) {
        String host;
        try {
            host = URI.create(targetUrl.strip()).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null) {
            host = targetUrl.replaceAll("^[a-z]+://", "").replaceAll("[:/].*$", "");
        }
        host = host.split("\\.")[0];
        return host.endsWith("-svc") ? host.substring(0, host.length() - 4) : host;
    }
}
