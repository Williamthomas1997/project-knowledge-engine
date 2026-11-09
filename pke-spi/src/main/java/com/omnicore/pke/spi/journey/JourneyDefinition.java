package com.omnicore.pke.spi.journey;

import java.util.List;
import java.util.Map;

/**
 * One user journey as read from one source file.
 *
 * @param key        stable journey key (kebab-case), the same for every source that describes the same journey
 * @param name       human-readable name as written in the source
 * @param sourceKind e.g. {@code postman}, {@code java-journey}, {@code gatling}, {@code jmeter}
 * @param sourcePath file the journey came from, relative to the repository root
 * @param steps      ordered steps (order starts at 1)
 * @param feeds      data flowing from one step's response into a later step's request
 * @param drivers    fully qualified test classes that run this journey (used for TestNG suite coverage)
 * @param unresolved what could not be resolved, with the reason (never guessed)
 */
public record JourneyDefinition(String key, String name, String sourceKind, String sourcePath, List<Step> steps,
                                List<Feed> feeds, List<String> drivers, List<Unresolved> unresolved) {

    public JourneyDefinition {
        steps = List.copyOf(steps);
        feeds = List.copyOf(feeds);
        drivers = List.copyOf(drivers);
        unresolved = List.copyOf(unresolved);
    }

    /**
     * @param order    1-based position in the journey
     * @param label    the step's name in the source (e.g. a Postman request name or a Java method name)
     * @param api      the API the step calls, or null when it could not be resolved
     * @param location where in the source file the step is defined (e.g. {@code line 42} or a request path)
     */
    public record Step(int order, String label, ApiCall api, String location) {
    }

    /**
     * The full definition of an API as seen by a client. Never holds values, except gateway header codes,
     * which identify the API.
     *
     * @param httpMethod      upper-case HTTP method
     * @param pathTemplate    normalised path, variables written as {@code {name}}
     * @param gatewayHeaders  gateway service header codes (moduleId, subModuleId, serviceId, screenId, unit, channel)
     * @param otherHeaders    names of other headers the call sends (values are never kept)
     * @param pathParameters  names of path variables
     * @param queryParameters names of query parameters
     * @param requestFields   request body structure, or null when the call sends no body
     * @param responseFields  response body structure, or null when unknown
     */
    public record ApiCall(String httpMethod, String pathTemplate, Map<String, String> gatewayHeaders,
                          List<String> otherHeaders, List<String> pathParameters, List<String> queryParameters,
                          Field requestFields, Field responseFields) {
    }

    /** A JSON body structure: name, type ({@code object|array|string|number|boolean|null}) and children. */
    public record Field(String name, String type, List<Field> children) {
        public Field {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /** Step {@code fromOrder}'s response provides {@code variable}, which step {@code toOrder} sends as {@code field}. */
    public record Feed(int fromOrder, int toOrder, String variable, String field) {
    }

    public record Unresolved(String location, String reason) {
    }
}
