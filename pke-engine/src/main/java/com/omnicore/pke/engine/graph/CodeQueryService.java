package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Read-only code and journey queries used by ingestion (incremental scope, routing) and the journey API. */
@Service
@SuppressWarnings("unchecked")
public class CodeQueryService {

    private final GraphReader reader;

    public CodeQueryService(GraphReader reader) {
        this.reader = reader;
    }

    /**
     * Files (evidence scopes) of {@code artifactKey} whose current relationships point into the given files' types
     * or methods: the reverse-dependency set an incremental run must re-extract.
     */
    public List<String> dependentScopes(String repository, String artifactKey, List<String> paths) {
        if (paths.isEmpty()) {
            return List.of();
        }
        return reader.query("""
                MATCH (c:Entity:JavaClass {repository: $repository}) WHERE c.path IN $paths
                OPTIONAL MATCH (c)-[:DECLARES]->(m:JavaMethod)
                WITH collect(DISTINCT c.pke_id) + collect(DISTINCT m.pke_id) AS targets
                UNWIND targets AS target
                MATCH (a:Assertion {pke_objectId: target, pke_status: 'CURRENT'})-[:SUPPORTED_BY]->(e:Evidence {sourceArtifactId: $artifact})
                WHERE e.scope IS NOT NULL AND NOT e.scope IN $paths
                RETURN DISTINCT e.scope AS scope ORDER BY scope""",
                Map.of("repository", repository, "artifact", artifactKey, "paths", paths))
                .stream().map(r -> (String) r.get("scope")).toList();
    }

    /**
     * Pairs of APIs (subject id &lt; object id) whose handlers' call trees (1..4 hops) reach the same non-trivial method:
     * one with an outgoing call, log, error, read, write or publish, whose class does not match {@code exclude}.
     * APIs with the same handler (a gateway API and its service API) are not paired. Rows: {@code a, b, shared}.
     */
    public List<Map<String, Object>> sharedLogic(String exclude) {
        return reader.query("""
                MATCH (a1:Entity:ApiOperation)-[:HANDLED_BY]->(h1:JavaMethod)-[:CALLS*1..4]->(m:JavaMethod)
                WHERE NOT m.fqcn =~ $exclude
                  AND EXISTS { (m)-[:CALLS|LOGS|THROWS_ERROR|READS_FROM|WRITES_TO|PUBLISHES_TO]->() }
                WITH DISTINCT a1, h1, m
                MATCH (m)<-[:CALLS*1..4]-(h2:JavaMethod)<-[:HANDLED_BY]-(a2:Entity:ApiOperation)
                WHERE h2 <> h1 AND a1.pke_id < a2.pke_id
                WITH a1.pke_id AS a, a2.pke_id AS b, m.fqcn + '#' + m.signature AS method
                ORDER BY a, b, method
                RETURN a, b, collect(DISTINCT method) AS shared ORDER BY a, b""", Map.of("exclude", exclude));
    }

    /** Which entity types are connected by which current relationship types, with counts (relationship discovery). */
    public List<Map<String, Object>> relationshipPatterns() {
        return reader.query("""
                MATCH (s:Entity)-[r]->(o:Entity) WHERE r.pke_relId IS NOT NULL
                RETURN s.pke_type AS subjectType, type(r) AS relationship, o.pke_type AS objectType, count(*) AS count
                ORDER BY subjectType, relationship, objectType""", Map.of());
    }

    /**
     * A log statement's method, the APIs whose handler reaches that method (0..4 hops, nearest first), and the error
     * codes the method or its callees (0..2 hops) can raise.
     */
    public Map<String, Object> logContext(String logId) {
        return reader.single("""
                MATCH (m:JavaMethod)-[:LOGS]->(log:LogStatement {pke_id: $id})
                OPTIONAL MATCH path = (api:ApiOperation)-[:HANDLED_BY]->(:JavaMethod)-[:CALLS*0..4]->(m)
                WITH m, log, api, min(length(path)) AS hops ORDER BY hops, api.pathTemplate
                WITH m, log, [a IN collect(api) WHERE a IS NOT NULL | a.pke_id] AS apis
                OPTIONAL MATCH (m)-[:CALLS*0..2]->(:JavaMethod)-[:THROWS_ERROR]->(e:ErrorCode)
                RETURN m.pke_id AS methodId, m.fqcn + '#' + m.signature AS method, log.level AS level,
                       log.template AS template, log.order AS order, apis,
                       [c IN collect(DISTINCT e.code) WHERE c IS NOT NULL] AS errorCodes""", Map.of("id", logId))
                .orElse(Map.of());
    }

    /** Current APIs (both layers), for journey interviews: id, layer, service, method, path. */
    public List<Map<String, Object>> apiCatalogue(int limit) {
        return reader.query("""
                MATCH (a:Entity:ApiOperation) WHERE coalesce(a.pke_status, 'CURRENT') <> 'INVALID' AND coalesce(a.pke_scope, 'main') = 'main'
                RETURN a.pke_id AS id, a.layer AS layer, a.service AS service, a.httpMethod AS method,
                       a.pathTemplate AS path
                ORDER BY a.layer, a.pathTemplate, a.httpMethod, a.pke_id LIMIT $limit""", Map.of("limit", limit));
    }

    public List<Map<String, Object>> gatewayApis() {
        return reader.query("""
                MATCH (a:Entity:ApiOperation {layer: 'gateway'})
                RETURN a.pke_id AS id, a.httpMethod AS httpMethod, a.pathTemplate AS pathTemplate ORDER BY id""", Map.of());
    }

    /** Domain properties of every gateway API, for merging observations across repositories. */
    public List<Map<String, Object>> gatewayApiProperties() {
        return reader.query("MATCH (a:Entity:ApiOperation {layer: 'gateway'}) RETURN properties(a) AS a", Map.of())
                .stream().map(r -> KnowledgeWriter.domainProperties((Map<String, Object>) r.get("a"))).toList();
    }

    public List<Map<String, Object>> serviceHandlers() {
        return reader.query("""
                MATCH (a:Entity:ApiOperation {layer: 'service'})-[:HANDLED_BY]->(m:Entity:JavaMethod)
                RETURN a.service AS service, a.httpMethod AS httpMethod, a.pathTemplate AS pathTemplate,
                       m.repository AS repository, m.fqcn AS fqcn, m.signature AS signature""", Map.of());
    }

    public record JourneySummary(String id, String journeyKey, String name, long steps, List<String> sources) {
    }

    public record JourneyStepView(long order, String id, List<String> labels, List<Map<String, Object>> apis,
                                  List<Map<String, Object>> handlers) {
    }

    public record JourneyView(String id, String journeyKey, String name, List<JourneyStepView> steps,
                              List<Map<String, Object>> feeds, List<String> sources, List<String> coveredBy) {
    }

    public List<JourneySummary> journeys() {
        return reader.query("""
                MATCH (j:Entity:UserJourney)
                OPTIONAL MATCH (j)-[:HAS_STEP]->(s:JourneyStep)
                OPTIONAL MATCH (j)-[:SUPPORTED_BY]->(e:Evidence)
                RETURN j.pke_id AS id, j.journeyKey AS key, j.name AS name, count(DISTINCT s) AS steps,
                       collect(DISTINCT e.extractionMethod) AS sources ORDER BY key""", Map.of())
                .stream().map(r -> new JourneySummary((String) r.get("id"), (String) r.get("key"), (String) r.get("name"),
                        ((Number) r.get("steps")).longValue(), strings(r.get("sources")))).toList();
    }

    /** A journey by id or key: ordered steps with their APIs, handlers, source labels, data flow and suites. */
    @SuppressWarnings("unchecked")
    public JourneyView journey(String idOrKey) {
        Map<String, Object> head = reader.single("""
                MATCH (j:Entity:UserJourney) WHERE j.pke_id = $id OR j.journeyKey = $id
                OPTIONAL MATCH (j)-[:SUPPORTED_BY]->(e:Evidence)
                OPTIONAL MATCH (suite:TestSuite)-[:COVERS]->(j)
                RETURN j.pke_id AS id, j.journeyKey AS key, j.name AS name,
                       collect(DISTINCT e.extractionMethod + ' ' + e.scope) AS sources, collect(DISTINCT suite.name) AS suites""",
                Map.of("id", idOrKey)).orElseThrow(() -> new NotFoundException("Journey " + idOrKey + " does not exist"));
        String id = (String) head.get("id");
        List<JourneyStepView> steps = new ArrayList<>();
        for (Map<String, Object> row : reader.query("""
                MATCH (:Entity:UserJourney {pke_id: $id})-[:HAS_STEP]->(s:JourneyStep)
                OPTIONAL MATCH (s)-[:SUPPORTED_BY]->(e:Evidence)
                WITH s, collect(DISTINCT e.excerpt + ' (' + e.extractionMethod + ')') AS labels
                OPTIONAL MATCH (s)-[:CALLS_API]->(a:ApiOperation)
                OPTIONAL MATCH (a)-[:HANDLED_BY]->(m:JavaMethod)
                RETURN s.order AS order, s.pke_id AS id, labels,
                       collect(DISTINCT a {.pke_id, .httpMethod, .pathTemplate, .gatewayKey}) AS apis,
                       collect(DISTINCT m {.pke_id, .fqcn, .signature, .repository}) AS handlers
                ORDER BY order""", Map.of("id", id))) {
            steps.add(new JourneyStepView(((Number) row.get("order")).longValue(), (String) row.get("id"),
                    strings(row.get("labels")), (List<Map<String, Object>>) row.get("apis"),
                    (List<Map<String, Object>>) row.get("handlers")));
        }
        List<Map<String, Object>> feeds = reader.query("""
                MATCH (:Entity:UserJourney {pke_id: $id})-[:HAS_STEP]->(from:JourneyStep)-[f:FEEDS]->(to:JourneyStep)
                RETURN from.order AS fromStep, to.order AS toStep, f.field AS field, f.variable AS variable
                ORDER BY fromStep, toStep, field""", Map.of("id", id)).stream().map(r -> (Map<String, Object>) new LinkedHashMap<>(r)).toList();
        return new JourneyView(id, (String) head.get("key"), (String) head.get("name"), steps, feeds,
                strings(head.get("sources")), strings(head.get("suites")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value == null ? List.of() : ((List<Object>) value).stream().filter(v -> v != null).map(String::valueOf).sorted().toList();
    }
}
