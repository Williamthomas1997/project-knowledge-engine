package com.omnicore.pke.engine.extract.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fallback without a route export: a gateway API is handled by the service API with the same method and path,
 * when exactly one ingested service exposes it. Ambiguous or missing matches are reported, never guessed.
 */
public final class SpringMappingRouteResolver implements RouteResolver {

    public static final String METHOD = "route.spring-mapping";
    public static final String SCOPE = "spring-mapping";

    @Override
    public String id() {
        return METHOD;
    }

    @Override
    public Result resolve(List<GatewayApi> apis, List<ServiceHandler> handlers, Set<String> alreadyResolved) {
        List<Resolution> resolved = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (GatewayApi api : apis) {
            if (alreadyResolved.contains(api.id())) {
                continue;
            }
            List<ServiceHandler> matching = handlers.stream().filter(h -> h.httpMethod().equals(api.httpMethod())
                    && RouteResolver.matches(h.pathTemplate(), api.pathTemplate())).toList();
            if (RouteResolver.distinctHandlers(matching).size() == 1) {
                ServiceHandler h = matching.getFirst();
                resolved.add(new Resolution(api, h, METHOD, "routes:" + SCOPE, null, SCOPE,
                        h.fqcn() + "#" + h.signature(), h.service() + " " + h.httpMethod() + " " + h.pathTemplate()));
            } else {
                unmatched.add(new Unmatched(api, matching.isEmpty()
                        ? "no route export row and no service exposes " + api.httpMethod() + " " + api.pathTemplate()
                        : matching.size() + " services expose " + api.httpMethod() + " " + api.pathTemplate()));
            }
        }
        return new Result(resolved, unmatched, List.of(SCOPE));
    }
}
