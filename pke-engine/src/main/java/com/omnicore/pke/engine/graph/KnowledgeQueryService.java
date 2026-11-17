package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Read side of the knowledge graph: entities, relationships, provenance, versions and change events. */
@Service
public class KnowledgeQueryService {

    public static final int MAX_PAGE_SIZE = 200;
    /** Integers as Long, matching how Neo4j returns live property values. */
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();

    private final GraphReader reader;

    public KnowledgeQueryService(GraphReader reader) {
        this.reader = reader;
    }

    public record Page<T>(List<T> items, int offset, int limit, long total) {
    }

    public record EntityView(String id, String type, long version, String status, String epistemic, Object validAt,
                             Object invalidAt, String invalidationReason, String schemaVersion,
                             long createdSeq, long updatedSeq, Map<String, Object> properties,
                             List<Map<String, Object>> evidence, String scope) {
    }

    public record RelationshipView(String id, String type, String category, String subjectId, String objectId,
                                   String status, String epistemic, long version, Object validAt, Object invalidAt,
                                   String invalidationReason, long createdSeq, long updatedSeq,
                                   Map<String, Object> properties, List<Map<String, Object>> evidence,
                                   String scope) {
    }

    public record VersionView(long version, String status, String epistemic, Object validAt, Object invalidAt,
                              long validFromSeq, Long validToSeq, Object recordedAt, String schemaVersion,
                              Map<String, Object> properties) {
    }

    public record EventView(long seq, String type, Object at, String actor, String operationId, String subjectKind,
                            String subjectId, String subjectType, Long version, Map<String, Object> details) {
    }

    public EntityView entity(String id) {
        Map<String, Object> row = reader.single("""
                MATCH (n:Entity {pke_id: $id})
                OPTIONAL MATCH (n)-[:SUPPORTED_BY]->(e:Evidence)
                WITH n, e ORDER BY e.recordedSeq
                RETURN properties(n) AS n, collect(properties(e)) AS evidence""", Map.of("id", id))
                .orElseThrow(() -> new NotFoundException("Entity " + id + " does not exist"));
        return entityView(map(row.get("n")), maps(row.get("evidence")));
    }

    public Page<EntityView> entities(String type, int offset, int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        params.put("offset", from);
        params.put("limit", size);
        List<EntityView> items = reader.query("""
                MATCH (n:Entity) WHERE $type IS NULL OR n.pke_type = $type
                RETURN properties(n) AS n ORDER BY n.pke_type, n.pke_id SKIP $offset LIMIT $limit""", params)
                .stream().map(r -> entityView(map(r.get("n")), List.of())).toList();
        long total = ((Number) reader.single(
                "MATCH (n:Entity) WHERE $type IS NULL OR n.pke_type = $type RETURN count(n) AS c", params)
                .orElseThrow().get("c")).longValue();
        return new Page<>(items, from, size, total);
    }

    public List<VersionView> entityVersions(String id) {
        List<VersionView> versions = reader.query("""
                MATCH (:Entity {pke_id: $id})-[:HAS_VERSION]->(v:EntityVersion)
                RETURN properties(v) AS v ORDER BY v.version""", Map.of("id", id))
                .stream().map(r -> versionView(map(r.get("v")))).toList();
        if (versions.isEmpty()) {
            throw new NotFoundException("Entity " + id + " does not exist");
        }
        return versions;
    }

    /** Relationships where the entity is subject or object; only currently valid ones unless {@code includeInvalid}. */
    public List<RelationshipView> relationshipsOf(String entityId, boolean includeInvalid) {
        entity(entityId);
        return reader.query("""
                CALL (*) {
                  MATCH (a:Assertion {pke_subjectId: $id}) RETURN a
                  UNION
                  MATCH (a:Assertion {pke_objectId: $id}) RETURN a
                }
                WITH a WHERE $includeInvalid OR a.pke_status = 'CURRENT'
                RETURN properties(a) AS a ORDER BY a.pke_type, a.pke_id""",
                Map.of("id", entityId, "includeInvalid", includeInvalid))
                .stream().map(r -> relationshipView(map(r.get("a")), List.of())).toList();
    }

    public RelationshipView relationship(String id) {
        Map<String, Object> row = reader.single("""
                MATCH (a:Assertion {pke_id: $id})
                OPTIONAL MATCH (a)-[:SUPPORTED_BY]->(e:Evidence)
                WITH a, e ORDER BY e.recordedSeq
                RETURN properties(a) AS a, collect(properties(e)) AS evidence""", Map.of("id", id))
                .orElseThrow(() -> new NotFoundException("Relationship " + id + " does not exist"));
        return relationshipView(map(row.get("a")), maps(row.get("evidence")));
    }

    public List<VersionView> relationshipVersions(String id) {
        List<VersionView> versions = reader.query("""
                MATCH (:Assertion {pke_id: $id})-[:HAS_VERSION]->(v:AssertionVersion)
                RETURN properties(v) AS v ORDER BY v.version""", Map.of("id", id))
                .stream().map(r -> versionView(map(r.get("v")))).toList();
        if (versions.isEmpty()) {
            throw new NotFoundException("Relationship " + id + " does not exist");
        }
        return versions;
    }

    public List<EventView> events(long afterSeq, int limit, String subjectId) {
        Map<String, Object> params = new HashMap<>();
        params.put("after", afterSeq);
        params.put("limit", Math.clamp(limit, 1, MAX_PAGE_SIZE));
        params.put("subjectId", subjectId);
        return reader.query("""
                MATCH (e:ChangeEvent) WHERE e.seq > $after AND ($subjectId IS NULL OR e.subjectId = $subjectId)
                RETURN properties(e) AS e ORDER BY e.seq LIMIT $limit""", params)
                .stream().map(r -> eventView(map(r.get("e")))).toList();
    }

    /** Highest committed seq (0 when nothing has been written). */
    public long currentSeq() {
        return reader.single("MATCH (s:Sequence {name: $name}) RETURN s.value AS value",
                        Map.of("name", GraphTransactions.SEQUENCE_NAME))
                .map(r -> ((Number) r.get("value")).longValue()).orElse(0L);
    }

    // ---------------------------------------------------------------- scope-widened reads (ADR-015)

    /** Scopes of all OPEN proposals ({@code includeProposals=true}). */
    public List<String> openProposalScopes() {
        return reader.query("MATCH (p:Proposal {status: 'OPEN'}) RETURN p.pke_scope AS scope ORDER BY p.createdSeq",
                Map.of()).stream().map(r -> (String) r.get("scope")).toList();
    }

    /**
     * Main entities plus the current items of the given proposal scopes: a proposed version replaces the main entity
     * with the same id (scope = the proposal), proposed invalidations mark it {@code PROPOSED_INVALIDATION}, and
     * entities that exist only in a proposal follow the main entities.
     */
    public Page<EntityView> entities(String type, int offset, int limit, List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return entities(type, offset, limit);
        }
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        List<Map<String, Object>> items = proposalItems(scopes, List.of("ENTITY", "ENTITY_INVALIDATION"), type, null);
        List<EntityView> added = items.stream().filter(i -> "ENTITY".equals(i.get("kind")) && !inMain(i))
                .map(KnowledgeQueryService::proposedEntity).toList();
        Page<EntityView> main = entities(type, from, size);
        List<EntityView> page = new ArrayList<>(main.items().stream().map(v -> overlay(v, items)).toList());
        if (page.size() < size) {
            int start = (int) Math.max(0, from - main.total());
            for (int k = start; k < added.size() && page.size() < size; k++) {
                page.add(added.get(k));
            }
        }
        return new Page<>(page, from, size, main.total() + added.size());
    }

    public EntityView entity(String id, List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return entity(id);
        }
        List<Map<String, Object>> items = proposalItems(scopes, List.of("ENTITY", "ENTITY_INVALIDATION"), null, id);
        boolean inMain = reader.single("MATCH (n:Entity {pke_id: $id}) RETURN n.pke_id AS id", Map.of("id", id)).isPresent();
        if (!inMain) {
            return items.stream().filter(i -> "ENTITY".equals(i.get("kind"))).findFirst()
                    .map(KnowledgeQueryService::proposedEntity)
                    .orElseThrow(() -> new NotFoundException("Entity " + id + " does not exist in " + scopes));
        }
        return overlay(entity(id), items);
    }

    public List<RelationshipView> relationshipsOf(String entityId, boolean includeInvalid, List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return relationshipsOf(entityId, includeInvalid);
        }
        entity(entityId, scopes);
        List<Map<String, Object>> items = reader.query("""
                MATCH (i:ProposalItem) WHERE i.pke_scope IN $scopes AND i.pke_status = 'CURRENT'
                  AND i.kind IN ['RELATIONSHIP', 'RELATIONSHIP_INVALIDATION']
                OPTIONAL MATCH (a:Assertion {pke_id: i.targetId})
                WITH i, a WHERE i.subjectId = $id OR i.objectId = $id
                             OR a.pke_subjectId = $id OR a.pke_objectId = $id
                RETURN properties(i) AS i, a IS NOT NULL AS inMain ORDER BY i.pke_type, i.targetId""",
                Map.of("scopes", scopes, "id", entityId)).stream().map(KnowledgeQueryService::flatten).toList();
        List<RelationshipView> out = new ArrayList<>();
        boolean mainExists = reader.single("MATCH (n:Entity {pke_id: $id}) RETURN n.pke_id AS id", Map.of("id", entityId)).isPresent();
        if (mainExists) {
            relationshipsOf(entityId, includeInvalid).forEach(r -> out.add(overlay(r, items)));
        }
        items.stream().filter(i -> "RELATIONSHIP".equals(i.get("kind")) && !inMain(i))
                .forEach(i -> out.add(proposedRelationship(i)));
        return out;
    }

    public RelationshipView relationship(String id, List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return relationship(id);
        }
        List<Map<String, Object>> items = proposalItems(scopes, List.of("RELATIONSHIP", "RELATIONSHIP_INVALIDATION"), null, id);
        boolean inMain = reader.single("MATCH (a:Assertion {pke_id: $id}) RETURN a.pke_id AS id", Map.of("id", id)).isPresent();
        if (!inMain) {
            return items.stream().filter(i -> "RELATIONSHIP".equals(i.get("kind"))).findFirst()
                    .map(KnowledgeQueryService::proposedRelationship)
                    .orElseThrow(() -> new NotFoundException("Relationship " + id + " does not exist in " + scopes));
        }
        return overlay(relationship(id), items);
    }

    private List<Map<String, Object>> proposalItems(List<String> scopes, List<String> kinds, String type, String targetId) {
        Map<String, Object> params = new HashMap<>();
        params.put("scopes", scopes);
        params.put("kinds", kinds);
        params.put("type", type);
        params.put("targetId", targetId);
        return reader.query("""
                MATCH (i:ProposalItem) WHERE i.pke_scope IN $scopes AND i.pke_status = 'CURRENT' AND i.kind IN $kinds
                  AND ($targetId IS NULL OR i.targetId = $targetId)
                OPTIONAL MATCH (n:Entity {pke_id: i.targetId})
                OPTIONAL MATCH (a:Assertion {pke_id: i.targetId})
                WITH i, n, a WHERE $type IS NULL OR coalesce(i.pke_type, n.pke_type, a.pke_type) = $type
                RETURN properties(i) AS i, (n IS NOT NULL OR a IS NOT NULL) AS inMain
                ORDER BY i.pke_type, i.targetId""", params).stream().map(KnowledgeQueryService::flatten).toList();
    }

    private static Map<String, Object> flatten(Map<String, Object> row) {
        Map<String, Object> item = new HashMap<>(map(row.get("i")));
        item.put("_inMain", row.get("inMain"));
        return item;
    }

    private static boolean inMain(Map<String, Object> item) {
        return Boolean.TRUE.equals(item.get("_inMain"));
    }

    private static EntityView overlay(EntityView main, List<Map<String, Object>> items) {
        for (Map<String, Object> i : items) {
            if (main.id().equals(i.get("targetId"))) {
                if ("ENTITY".equals(i.get("kind"))) {
                    return proposedEntity(i);
                }
                return new EntityView(main.id(), main.type(), main.version(), "PROPOSED_INVALIDATION", main.epistemic(),
                        main.validAt(), main.invalidAt(), main.invalidationReason(), main.schemaVersion(),
                        main.createdSeq(), main.updatedSeq(), main.properties(), main.evidence(), (String) i.get("pke_scope"));
            }
        }
        return main;
    }

    private static RelationshipView overlay(RelationshipView main, List<Map<String, Object>> items) {
        for (Map<String, Object> i : items) {
            if (main.id().equals(i.get("targetId"))) {
                if ("RELATIONSHIP".equals(i.get("kind"))) {
                    return proposedRelationship(i);
                }
                return new RelationshipView(main.id(), main.type(), main.category(), main.subjectId(), main.objectId(),
                        "PROPOSED_INVALIDATION", main.epistemic(), main.version(), main.validAt(), main.invalidAt(),
                        main.invalidationReason(), main.createdSeq(), main.updatedSeq(), main.properties(),
                        main.evidence(), (String) i.get("pke_scope"));
            }
        }
        return main;
    }

    private static EntityView proposedEntity(Map<String, Object> i) {
        return new EntityView((String) i.get("targetId"), (String) i.get("pke_type"), number(i.get("pke_version")),
                "PROPOSED", (String) i.get("pke_epistemic"), null, null, null, null,
                number(i.get("pke_createdSeq")), number(i.get("pke_updatedSeq")), json((String) i.get("propsJson")),
                List.of(), (String) i.get("pke_scope"));
    }

    private static RelationshipView proposedRelationship(Map<String, Object> i) {
        return new RelationshipView((String) i.get("targetId"), (String) i.get("pke_type"), null,
                (String) i.get("subjectId"), (String) i.get("objectId"), "PROPOSED", (String) i.get("pke_epistemic"),
                number(i.get("pke_version")), null, null, null, number(i.get("pke_createdSeq")),
                number(i.get("pke_updatedSeq")), json((String) i.get("propsJson")), List.of(), (String) i.get("pke_scope"));
    }

    private static EntityView entityView(Map<String, Object> n, List<Map<String, Object>> evidence) {
        return new EntityView((String) n.get("pke_id"), (String) n.get("pke_type"), number(n.get("pke_version")),
                n.get("pke_status") == null ? "CURRENT" : (String) n.get("pke_status"), (String) n.get("pke_epistemic"),
                n.get("pke_validAt"), n.get("pke_invalidAt"), (String) n.get("pke_invalidationReason"),
                (String) n.get("pke_schemaVersion"),
                number(n.get("pke_createdSeq")), number(n.get("pke_updatedSeq")),
                KnowledgeWriter.domainProperties(n), evidence.stream().map(KnowledgeQueryService::evidenceView).toList(),
                scope(n));
    }

    private static RelationshipView relationshipView(Map<String, Object> a, List<Map<String, Object>> evidence) {
        return new RelationshipView((String) a.get("pke_id"), (String) a.get("pke_type"), (String) a.get("pke_category"),
                (String) a.get("pke_subjectId"), (String) a.get("pke_objectId"), (String) a.get("pke_status"),
                (String) a.get("pke_epistemic"), number(a.get("pke_version")), a.get("pke_validAt"),
                a.get("pke_invalidAt"), (String) a.get("pke_invalidationReason"), number(a.get("pke_createdSeq")),
                number(a.get("pke_updatedSeq")), KnowledgeWriter.domainProperties(a),
                evidence.stream().map(KnowledgeQueryService::evidenceView).toList(), scope(a));
    }

    /** Main knowledge written before ADR-015 has no {@code pke_scope}; it is main. */
    private static String scope(Map<String, Object> n) {
        Object scope = n.get("pke_scope");
        return scope == null ? "main" : (String) scope;
    }

    private static VersionView versionView(Map<String, Object> v) {
        return new VersionView(number(v.get("version")), (String) v.get("status"), (String) v.get("epistemic"),
                v.get("validAt"), v.get("invalidAt"), number(v.get("validFromSeq")),
                v.get("validToSeq") == null ? null : number(v.get("validToSeq")), v.get("recordedAt"),
                (String) v.get("schemaVersion"), json((String) v.get("propsJson")));
    }

    private static EventView eventView(Map<String, Object> e) {
        return new EventView(number(e.get("seq")), (String) e.get("type"), e.get("at"), (String) e.get("actor"),
                (String) e.get("operationId"), (String) e.get("subjectKind"), (String) e.get("subjectId"),
                (String) e.get("subjectType"), e.get("version") == null ? null : number(e.get("version")),
                json((String) e.get("details")));
    }

    private static Map<String, Object> evidenceView(Map<String, Object> evidence) {
        Map<String, Object> view = new TreeMap<>(evidence);
        view.put("id", view.remove("pke_id"));
        return view;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String json) {
        return json == null ? Map.of() : JSON.readValue(json, TreeMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value == null ? List.of() : new ArrayList<>((List<Map<String, Object>>) value);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
