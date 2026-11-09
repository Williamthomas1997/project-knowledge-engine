package com.omnicore.pke.engine.extract.route;

import java.util.List;

/**
 * Links client-facing (gateway) APIs to the Java methods that handle them (ADR-007 §6). Routing is not stored as
 * graph structure: a resolver produces {@code ApiOperation -[:HANDLED_BY]-> JavaMethod} edges whose evidence cites
 * the route row or the controller mapping.
 */
public interface RouteResolver {

    /** A gateway API as stored in the graph. */
    record GatewayApi(String id, String httpMethod, String pathTemplate) {
    }

    /** A service-layer API with its handler method. */
    record ServiceHandler(String service, String httpMethod, String pathTemplate, String repository, String fqcn,
                          String signature) {
    }

    /**
     * A resolved route.
     *
     * @param evidenceArtifact source of the routing fact (e.g. {@code routes:ocs_t_gateway_api_route.sql})
     * @param scope            scope re-evaluated on every run (the export file, or {@code spring-mapping})
     * @param location         route row or controller location
     */
    record Resolution(GatewayApi api, ServiceHandler handler, String method, String evidenceArtifact, String evidenceVersion,
                      String scope, String location, String excerpt) {
    }

    record Unmatched(GatewayApi api, String reason) {
    }

    record Result(List<Resolution> resolved, List<Unmatched> unmatched, List<String> scopes) {
    }

    String id();

    /** Resolves what it can; {@code alreadyResolved} holds gateway API ids resolved by an earlier resolver. */
    Result resolve(List<GatewayApi> apis, List<ServiceHandler> handlers, java.util.Set<String> alreadyResolved);

    /** True when {@code template} (with {@code {var}} segments) matches {@code path}. */
    static boolean matches(String template, String path) {
        String[] t = template.split("/");
        String[] p = path.split("/");
        if (t.length != p.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            boolean variable = t[i].startsWith("{") && t[i].endsWith("}") || p[i].startsWith("{") && p[i].endsWith("}");
            if (!variable && !t[i].equals(p[i])) {
                return false;
            }
        }
        return true;
    }

    static List<String> distinctHandlers(List<ServiceHandler> handlers) {
        return handlers.stream().map(h -> h.fqcn() + "#" + h.signature()).distinct().toList();
    }
}
