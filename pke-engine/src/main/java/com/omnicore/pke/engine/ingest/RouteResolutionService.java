package com.omnicore.pke.engine.ingest;

import com.omnicore.pke.engine.extract.ExtractedKnowledge;
import com.omnicore.pke.engine.extract.route.GatewayRouteExportResolver;
import com.omnicore.pke.engine.extract.route.RouteResolver;
import com.omnicore.pke.engine.extract.route.RouteResolver.GatewayApi;
import com.omnicore.pke.engine.extract.route.RouteResolver.Resolution;
import com.omnicore.pke.engine.extract.route.RouteResolver.ServiceHandler;
import com.omnicore.pke.engine.extract.route.SpringMappingRouteResolver;
import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.ingest.KnowledgeBatchWriter.Scoped;
import com.omnicore.pke.engine.ingest.KnowledgeBatchWriter.Stats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Recomputes {@code ApiOperation(gateway) -[:HANDLED_BY]-> JavaMethod} for every gateway API in the graph: the route
 * export first, then Spring mapping matching. Each resolver scope is retracted and rewritten as a whole, so routes
 * that disappeared are invalidated (history kept) and no route nodes are created.
 */
@Service
public class RouteResolutionService {

    private final CodeQueryService codeQueries;
    private final KnowledgeBatchWriter batchWriter;
    private final IngestionProperties properties;

    public RouteResolutionService(CodeQueryService codeQueries, KnowledgeBatchWriter batchWriter, IngestionProperties properties) {
        this.codeQueries = codeQueries;
        this.batchWriter = batchWriter;
        this.properties = properties;
    }

    public record Report(int gatewayApis, int resolved, List<String> unmatched, Stats stats) {
    }

    public Report resolve(String operationId, String actor) {
        List<GatewayApi> apis = codeQueries.gatewayApis().stream().map(r -> new GatewayApi((String) r.get("id"),
                (String) r.get("httpMethod"), (String) r.get("pathTemplate"))).toList();
        List<ServiceHandler> handlers = codeQueries.serviceHandlers().stream().map(r -> new ServiceHandler(
                (String) r.get("service"), (String) r.get("httpMethod"), (String) r.get("pathTemplate"),
                (String) r.get("repository"), (String) r.get("fqcn"), (String) r.get("signature"))).toList();
        List<RouteResolver> resolvers = new ArrayList<>();
        List<Path> exports = properties.routeExportFiles().stream().map(Path::of).filter(Files::isRegularFile).toList();
        if (!exports.isEmpty()) {
            resolvers.add(new GatewayRouteExportResolver(exports, properties.routeTable()));
        }
        resolvers.add(new SpringMappingRouteResolver());

        Set<String> resolvedIds = new HashSet<>();
        Map<String, String> unmatched = new LinkedHashMap<>();
        List<Scoped> scoped = new ArrayList<>();
        for (RouteResolver resolver : resolvers) {
            RouteResolver.Result result = resolver.resolve(apis, handlers, resolvedIds);
            Map<String, ExtractedKnowledge> byScope = new LinkedHashMap<>();
            Map<String, String> artifacts = new LinkedHashMap<>();
            for (String scope : result.scopes()) {
                byScope.put(scope, new ExtractedKnowledge(scope));
                artifacts.put(scope, "routes:" + scope);
            }
            for (Resolution r : result.resolved()) {
                ExtractedKnowledge k = byScope.computeIfAbsent(r.scope(), ExtractedKnowledge::new);
                artifacts.put(r.scope(), r.evidenceArtifact());
                ServiceHandler h = r.handler();
                k.relate("HANDLED_BY", EntityRef.id(r.api().id()), EntityRef.key("JavaMethod",
                                Map.of("repository", h.repository(), "fqcn", h.fqcn(), "signature", h.signature())),
                        Map.of(), EvidenceInput.scoped(r.evidenceArtifact(), r.evidenceVersion(), r.scope(), r.location(),
                                r.method(), r.excerpt()), null);
                resolvedIds.add(r.api().id());
                unmatched.remove(r.api().id());
            }
            for (RouteResolver.Unmatched u : result.unmatched()) {
                if (!resolvedIds.contains(u.api().id())) {
                    unmatched.put(u.api().id(), u.api().httpMethod() + " " + u.api().pathTemplate() + ": " + u.reason());
                }
            }
            byScope.forEach((scope, k) -> scoped.add(new Scoped(k, artifacts.get(scope), resolver.id())));
        }
        Stats stats = batchWriter.write(scoped, List.of(), operationId, actor, properties.batchSize());
        return new Report(apis.size(), resolvedIds.size(), List.copyOf(new java.util.TreeSet<>(unmatched.values())), stats);
    }
}
