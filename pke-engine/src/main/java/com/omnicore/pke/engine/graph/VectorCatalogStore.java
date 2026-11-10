package com.omnicore.pke.engine.graph;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Embedding generations and vector collections as graph nodes (ADR-007 §2):
 * {@code (:VectorCollection {name, type, field, kind})-[:INDEXES]->(:SchemaDefinition)},
 * {@code -[:EMBEDDED_WITH]->(:EmbeddingGeneration {pke_id, model, dimensions, status})}, and
 * {@code (CollectionCatalog)-[:DEPENDS_ON]->(content collection)}, {@code (GraphSchemaMap)-[:DERIVED_FROM]->(:SchemaDefinition)}.
 * Also the reads the index worker and reconciliation need. Operational: no change events.
 */
@Service
public class VectorCatalogStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public VectorCatalogStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public record Generation(String id, String model, int dimensions, String status, Object createdAt,
                             Object activatedAt, String evaluationJson) {
    }

    // ---------------------------------------------------------------- generations

    public List<Generation> generations() {
        return reader.query("MATCH (g:EmbeddingGeneration) RETURN properties(g) AS g ORDER BY g.createdAt, g.pke_id", Map.of())
                .stream().map(r -> generation(map(r.get("g")))).toList();
    }

    public Optional<Generation> generation(String id) {
        return reader.single("MATCH (g:EmbeddingGeneration {pke_id: $id}) RETURN properties(g) AS g", Map.of("id", id))
                .map(r -> generation(map(r.get("g"))));
    }

    public void createGeneration(String id, String model, int dimensions, String status) {
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", id);
        props.put("model", model);
        props.put("dimensions", dimensions);
        props.put("status", status);
        props.put("createdAt", now());
        if ("ACTIVE".equals(status)) {
            props.put("activatedAt", now());
        }
        transactions.write("index", null, ctx -> ctx.run("CREATE (g:EmbeddingGeneration) SET g = $props",
                Map.of("props", props)));
    }

    /** Sets a generation's status; ACTIVE retires the previously active generation in the same transaction. */
    public void setGenerationStatus(String id, String status, String evaluationJson) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", status);
        if (evaluationJson != null) {
            fields.put("evaluationJson", evaluationJson);
        }
        if ("ACTIVE".equals(status)) {
            fields.put("activatedAt", now());
        }
        transactions.write("index", null, ctx -> {
            if ("ACTIVE".equals(status)) {
                ctx.run("MATCH (g:EmbeddingGeneration {status: 'ACTIVE'}) WHERE g.pke_id <> $id SET g.status = 'RETIRED', g.retiredAt = $now",
                        Map.of("id", id, "now", now()));
            }
            return ctx.run("MATCH (g:EmbeddingGeneration {pke_id: $id}) SET g += $fields", Map.of("id", id, "fields", fields));
        });
    }

    // ---------------------------------------------------------------- collections

    /**
     * Creates or updates a collection node and its links.
     *
     * @param kind        {@code content} (an entity type's field) or {@code system}
     * @param generations non-retired generations the collection is embedded with
     */
    public void syncCollection(String name, String kind, String type, String field, String description, String provider,
                               List<String> generations, List<String> dependsOn, List<String> derivedFromTypes) {
        Map<String, Object> props = new HashMap<>();
        props.put("kind", kind);
        props.put("type", type);
        props.put("field", field);
        props.put("description", description);
        props.put("provider", provider);
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("props", props);
        params.put("generations", generations);
        params.put("dependsOn", dependsOn);
        params.put("types", derivedFromTypes);
        params.put("type", type);
        transactions.write("index", null, ctx -> {
            ctx.run("""
                    MERGE (c:VectorCollection {name: $name}) SET c += $props
                    WITH c
                    OPTIONAL MATCH (c)-[r:EMBEDDED_WITH|DEPENDS_ON|DERIVED_FROM|INDEXES]->() DELETE r""", params);
            ctx.run("""
                    MATCH (c:VectorCollection {name: $name})
                    UNWIND $generations AS gen
                    MATCH (g:EmbeddingGeneration {pke_id: gen}) MERGE (c)-[:EMBEDDED_WITH]->(g)""", params);
            ctx.run("""
                    MATCH (c:VectorCollection {name: $name}) WHERE $type IS NOT NULL
                    MATCH (s:SchemaDefinition {name: $type}) MERGE (c)-[:INDEXES]->(s)""", params);
            ctx.run("""
                    MATCH (c:VectorCollection {name: $name})
                    UNWIND $dependsOn AS other
                    MERGE (o:VectorCollection {name: other}) MERGE (c)-[:DEPENDS_ON]->(o)""", params);
            return ctx.run("""
                    MATCH (c:VectorCollection {name: $name})
                    UNWIND $types AS t
                    MATCH (s:SchemaDefinition {name: t}) MERGE (c)-[:DERIVED_FROM]->(s)""", params);
        });
    }

    public void setCollectionHash(String name, String generation, String hash) {
        transactions.write("index", null, ctx -> ctx.run(
                "MATCH (c:VectorCollection {name: $name}) SET c[$key] = $hash",
                Map.of("name", name, "key", "contentHash_" + generation, "hash", hash)));
    }

    public Optional<String> collectionHash(String name, String generation) {
        return reader.single("MATCH (c:VectorCollection {name: $name}) RETURN c[$key] AS hash",
                Map.of("name", name, "key", "contentHash_" + generation)).map(r -> (String) r.get("hash"));
    }

    public List<Map<String, Object>> collections() {
        return reader.query("""
                MATCH (c:VectorCollection)
                OPTIONAL MATCH (c)-[:EMBEDDED_WITH]->(g:EmbeddingGeneration)
                RETURN c.name AS name, c.kind AS kind, c.type AS type, c.field AS field, c.description AS description,
                       collect(g.pke_id) AS generations ORDER BY name""", Map.of());
    }

    // ---------------------------------------------------------------- what to index

    /** The current value of one field of one entity, with the payload the vector record needs; empty if gone. */
    public Optional<Map<String, Object>> indexable(String entityId, String field) {
        return reader.single("""
                MATCH (n:Entity {pke_id: $id})
                OPTIONAL MATCH (n)-[:SUPPORTED_BY]->(e:Evidence) WHERE e.sourceVersionId IS NOT NULL
                WITH n, e ORDER BY e.recordedSeq DESC
                WITH n, collect(e.sourceVersionId)[0] AS sourceVersionId
                RETURN n.pke_id AS id, n.pke_type AS type, n[$field] AS text, coalesce(n.pke_status, 'CURRENT') AS status,
                       n.pke_epistemic AS epistemic, toString(n.pke_validAt) AS validAt, sourceVersionId""",
                Map.of("id", entityId, "field", field));
    }

    /** How many current entities of a type have a value for the field (the router skips empty collections). */
    public long countIndexable(String type, String field) {
        return ((Number) reader.single("""
                MATCH (n:Entity {pke_type: $type}) WHERE coalesce(n.pke_status, 'CURRENT') = 'CURRENT'
                  AND n[$field] IS NOT NULL AND trim(toString(n[$field])) <> ''
                RETURN count(n) AS c""", Map.of("type", type, "field", field)).orElseThrow().get("c")).longValue();
    }

    /** Current entities of a type that have a value for the field (to build or reconcile a collection). */
    public List<Map<String, Object>> indexableOfType(String type, String field) {
        return reader.query("""
                MATCH (n:Entity {pke_type: $type}) WHERE coalesce(n.pke_status, 'CURRENT') = 'CURRENT'
                  AND n[$field] IS NOT NULL AND trim(toString(n[$field])) <> ''
                RETURN n.pke_id AS id ORDER BY id""", Map.of("type", type, "field", field));
    }

    private static Generation generation(Map<String, Object> g) {
        return new Generation((String) g.get("pke_id"), (String) g.get("model"), ((Number) g.get("dimensions")).intValue(),
                (String) g.get("status"), g.get("createdAt"), g.get("activatedAt"), (String) g.get("evaluationJson"));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
