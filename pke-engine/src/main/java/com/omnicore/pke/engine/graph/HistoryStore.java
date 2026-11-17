package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Snapshots and event exports (plan §5, ADR-016). A {@code :Snapshot} is an immutable record of a {@code seq} plus
 * the configuration that produced the knowledge at that point. It is not a knowledge change, so it allocates no seq
 * and has no change event; it is written inside a knowledge write transaction only to read a stable {@code seq}.
 */
@Service
public class HistoryStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public HistoryStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public record SnapshotView(String id, String label, long seq, Object createdAt, String actor,
                               Map<String, Object> contents) {
    }

    /**
     * Versions of everything the graph itself records: active schema definitions, skills and pipelines, sources and
     * their current versions, embedding generations and the generation each vector collection is embedded with.
     */
    public Map<String, Object> graphConfiguration() {
        Map<String, Object> config = new TreeMap<>();
        config.put("schema", pairs("""
                MATCH (d:SchemaDefinition {current: true}) RETURN d.kind + ':' + d.name AS k, d.version AS v"""));
        config.put("skills", pairs("MATCH (d:SkillDefinition {active: true}) RETURN d.name AS k, d.version AS v"));
        config.put("pipelines", pairs("MATCH (d:PipelineDefinition {active: true}) RETURN d.name AS k, d.version AS v"));
        config.put("sources", pairs("""
                MATCH (a:SourceArtifact) WHERE a.currentVersion IS NOT NULL RETURN a.key AS k, a.currentVersion AS v"""));
        config.put("embeddingGenerations", pairs("MATCH (g:EmbeddingGeneration) RETURN g.pke_id AS k, g.status + ' ' + g.model AS v"));
        config.put("indexGenerations", pairs("""
                MATCH (c:VectorCollection)-[:EMBEDDED_WITH]->(g:EmbeddingGeneration)
                RETURN c.name AS k, g.pke_id AS v"""));
        return config;
    }

    private Map<String, Object> pairs(String cypher) {
        Map<String, Object> pairs = new TreeMap<>();
        reader.query(cypher, Map.of()).forEach(r -> {
            Object key = r.get("k");
            if (key != null) {
                pairs.merge(String.valueOf(key), r.get("v"), (a, b) -> a + "," + b);
            }
        });
        return pairs;
    }

    /** Records a snapshot at the current seq. {@code contents} is stored as JSON (it holds no knowledge values). */
    public SnapshotView create(String label, Map<String, Object> contents, String actor) {
        String id = PkeIds.uuidV7().toString();
        return transactions.write(actor, id, ctx -> {
            long seq = ctx.single("MATCH (s:Sequence {name: $name}) RETURN s.value AS value",
                    Map.of("name", GraphTransactions.SEQUENCE_NAME)).map(r -> r.get("value").asLong()).orElse(0L);
            Map<String, Object> props = new HashMap<>();
            props.put("pke_id", id);
            props.put("label", label);
            props.put("seq", seq);
            props.put("actor", actor);
            props.put("createdAt", ctx.now());
            props.put("contentsJson", CanonicalJson.write(contents));
            ctx.run("CREATE (s:Snapshot) SET s = $props", Map.of("props", props));
            return new SnapshotView(id, label, seq, ctx.now(), actor, contents);
        });
    }

    public SnapshotView snapshot(String id) {
        return reader.single("MATCH (s:Snapshot {pke_id: $id}) RETURN properties(s) AS s", Map.of("id", id))
                .map(r -> view(KnowledgeQueryService.map(r.get("s"))))
                .orElseThrow(() -> new NotFoundException("Snapshot " + id + " does not exist"));
    }

    public List<SnapshotView> snapshots(int limit) {
        return reader.query("MATCH (s:Snapshot) RETURN properties(s) AS s ORDER BY s.seq DESC, s.createdAt DESC LIMIT $limit",
                Map.of("limit", Math.clamp(limit, 1, 200))).stream().map(r -> view(KnowledgeQueryService.map(r.get("s")))).toList();
    }

    private static SnapshotView view(Map<String, Object> s) {
        return new SnapshotView((String) s.get("pke_id"), (String) s.get("label"), KnowledgeQueryService.number(s.get("seq")),
                s.get("createdAt"), (String) s.get("actor"), KnowledgeQueryService.json((String) s.get("contentsJson")));
    }

    // ---------------------------------------------------------------- JSONL export of change events

    /** Raw events (all properties, details as stored JSON) with {@code from < seq <= to}, in seq order. */
    public List<Map<String, Object>> eventRows(long from, long to, int limit) {
        return reader.query("""
                MATCH (e:ChangeEvent) WHERE e.seq > $from AND e.seq <= $to
                RETURN properties(e) AS e ORDER BY e.seq LIMIT $limit""", Map.of("from", from, "to", to, "limit", limit))
                .stream().map(r -> KnowledgeQueryService.map(r.get("e"))).toList();
    }

    /** Highest seq covered by a recorded export, 0 when none. */
    public long lastExportedSeq() {
        return reader.single("MATCH (x:HistoryExport) RETURN max(x.toSeq) AS seq", Map.of())
                .map(r -> r.get("seq") == null ? 0L : KnowledgeQueryService.number(r.get("seq"))).orElse(0L);
    }

    public void recordExport(long fromSeq, long toSeq, String ref, String sha256, long events) {
        String id = PkeIds.uuidV7().toString();
        transactions.write("history-export", id, ctx -> ctx.run("CREATE (x:HistoryExport) SET x = $props",
                Map.of("props", Map.of("pke_id", id, "fromSeq", fromSeq, "toSeq", toSeq, "ref", ref, "sha256", sha256,
                        "events", events, "createdAt", ctx.now()))));
    }

    public List<Map<String, Object>> exports() {
        return reader.query("MATCH (x:HistoryExport) RETURN properties(x) AS x ORDER BY x.toSeq", Map.of())
                .stream().map(r -> KnowledgeQueryService.map(r.get("x"))).toList();
    }
}
