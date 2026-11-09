package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.graph.PendingEvent.SubjectKind;
import com.omnicore.pke.engine.kernel.ChangeEventTypes;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.schema.DefinitionDocument;
import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.SchemaDefinitionStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Component;

/**
 * Stores definitions as {@code :SchemaDefinition} nodes. Every version is kept (historical knowledge must stay
 * interpretable); exactly one per kind + name is {@code current}. Registration and its {@code SCHEMA_REGISTERED}
 * event share one transaction and one {@code seq}.
 */
@Component
class Neo4jSchemaDefinitionStore implements SchemaDefinitionStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    Neo4jSchemaDefinitionStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    @Override
    public List<StoredDefinition> loadCurrent() {
        return reader.query("""
                MATCH (d:SchemaDefinition {current: true})
                RETURN d.kind AS kind, d.name AS name, d.version AS version, d.status AS status, d.hash AS hash,
                       d.definitionJson AS json, d.registeredSeq AS seq, d.current AS current
                ORDER BY CASE d.kind WHEN 'EntityType' THEN 0 ELSE 1 END, d.name""", Map.of())
                .stream().map(Neo4jSchemaDefinitionStore::stored).toList();
    }

    @Override
    public List<StoredDefinition> history(DefinitionKind kind, String name) {
        return reader.query("""
                MATCH (d:SchemaDefinition {kind: $kind, name: $name})
                RETURN d.kind AS kind, d.name AS name, d.version AS version, d.status AS status, d.hash AS hash,
                       d.definitionJson AS json, d.registeredSeq AS seq, d.current AS current
                ORDER BY d.registeredSeq""", Map.of("kind", kind.json(), "name", name))
                .stream().map(Neo4jSchemaDefinitionStore::stored).toList();
    }

    @Override
    public Registration register(DefinitionDocument document, String actor) {
        String key = document.key();
        return transactions.write(actor, PkeIds.uuidV7().toString(), ctx -> {
            Record existing = ctx.single("MATCH (d:SchemaDefinition {pke_id: $key}) RETURN d.hash AS hash",
                    Map.of("key", key)).orElse(null);
            if (existing != null) {
                if (existing.get("hash").asString().equals(document.hash())) {
                    return new Registration(key, false, null);
                }
                throw new ConflictException(key + " is already registered with different content. Versions are immutable.");
            }
            String previousVersion = ctx.single("""
                    MATCH (d:SchemaDefinition {kind: $kind, name: $name, current: true})
                    RETURN d.version AS version""", Map.of("kind", document.kind().json(), "name", document.name()))
                    .map(r -> r.get("version").asString()).orElse(null);
            long seq = ctx.nextSeq();
            Map<String, Object> params = new HashMap<>();
            params.put("key", key);
            params.put("kind", document.kind().json());
            params.put("name", document.name());
            params.put("version", document.version());
            params.put("status", document.definition().status().name());
            params.put("hash", document.hash());
            params.put("json", document.canonicalJson());
            params.put("seq", seq);
            params.put("at", ctx.now());
            params.put("actor", actor);
            ctx.run("""
                    OPTIONAL MATCH (prev:SchemaDefinition {kind: $kind, name: $name, current: true})
                    SET prev.current = false, prev.supersededSeq = $seq
                    CREATE (:SchemaDefinition {pke_id: $key, kind: $kind, name: $name, version: $version, status: $status,
                            hash: $hash, definitionJson: $json, registeredSeq: $seq, registeredAt: $at,
                            registeredBy: $actor, current: true})""", params);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", document.kind().json());
            details.put("name", document.name());
            details.put("version", document.version());
            details.put("hash", document.hash());
            if (previousVersion != null) {
                details.put("previousVersion", previousVersion);
            }
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.SCHEMA_REGISTERED, SubjectKind.SCHEMA, key,
                    document.kind().json(), null, details));
            return new Registration(key, true, seq);
        });
    }

    @Override
    public long instanceCount(DefinitionKind kind, String name) {
        String cypher = kind == DefinitionKind.ENTITY_TYPE
                ? "MATCH (n:Entity {pke_type: $name}) RETURN count(n) AS c"
                : "MATCH (a:Assertion {pke_type: $name}) RETURN count(a) AS c";
        return ((Number) reader.single(cypher, Map.of("name", name)).orElseThrow().get("c")).longValue();
    }

    private static StoredDefinition stored(Map<String, Object> row) {
        return new StoredDefinition((String) row.get("kind"), (String) row.get("name"), (String) row.get("version"),
                (String) row.get("status"), (String) row.get("hash"), (String) row.get("json"),
                ((Number) row.get("seq")).longValue(), Boolean.TRUE.equals(row.get("current")));
    }
}
