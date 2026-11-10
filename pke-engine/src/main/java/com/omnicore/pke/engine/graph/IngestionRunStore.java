package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Operational records of ingestion runs ({@code :IngestionRun}). These are not knowledge: they are written through
 * the single write path but produce no change events. The knowledge a run writes carries the run id as its
 * change events' {@code operationId}.
 */
@Service
public class IngestionRunStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public IngestionRunStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public enum Status { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED }

    public record Run(String id, String kind, String target, String mode, Status status, int attempt, String retryOf,
                      Object createdAt, Object startedAt, Object finishedAt, String fromVersion, String toVersion,
                      String statsJson, String unresolvedJson, String error) {
    }

    public Run create(String kind, String target, String mode, String retryOf, int attempt, String actor) {
        String id = PkeIds.uuidV7().toString();
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", id);
        props.put("kind", kind);
        props.put("target", target);
        props.put("mode", mode);
        props.put("status", Status.PENDING.name());
        props.put("attempt", attempt);
        props.put("retryOf", retryOf);
        props.put("requestedBy", actor);
        props.put("createdAt", OffsetDateTime.now());
        transactions.write(actor, id, ctx -> ctx.run("CREATE (r:IngestionRun) SET r = $props", Map.of("props", props)));
        return get(id);
    }

    /** Sets the given fields (null values remove a field). */
    public void update(String id, Map<String, Object> fields) {
        transactions.write("ingestion", id, ctx -> ctx.run("MATCH (r:IngestionRun {pke_id: $id}) SET r += $fields",
                Map.of("id", id, "fields", fields)));
    }

    /** True when the run ended PARTIAL only because its vector index tasks were still open. */
    public boolean indexPending(String id) {
        return reader.single("MATCH (r:IngestionRun {pke_id: $id}) RETURN coalesce(r.indexPending, false) AS p", Map.of("id", id))
                .map(r -> Boolean.TRUE.equals(r.get("p"))).orElse(false);
    }

    public Run get(String id) {
        return find(id).orElseThrow(() -> new NotFoundException("Ingestion run " + id + " does not exist"));
    }

    public Optional<Run> find(String id) {
        return reader.single("MATCH (r:IngestionRun {pke_id: $id}) RETURN properties(r) AS r", Map.of("id", id))
                .map(row -> run(map(row.get("r"))));
    }

    public List<Run> recent(String target, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("target", target);
        params.put("limit", Math.clamp(limit, 1, 200));
        return reader.query("""
                MATCH (r:IngestionRun) WHERE $target IS NULL OR r.target = $target
                RETURN properties(r) AS r ORDER BY r.createdAt DESC LIMIT $limit""", params)
                .stream().map(row -> run(map(row.get("r")))).toList();
    }

    /** The last successful (COMPLETED or PARTIAL) run's target version for a target, used for incremental runs. */
    public Optional<String> lastProcessedVersion(String kind, String target) {
        return reader.single("""
                MATCH (r:IngestionRun {kind: $kind, target: $target}) WHERE r.status IN ['COMPLETED', 'PARTIAL']
                  AND r.toVersion IS NOT NULL
                RETURN r.toVersion AS version ORDER BY r.finishedAt DESC LIMIT 1""", Map.of("kind", kind, "target", target))
                .map(row -> (String) row.get("version"));
    }

    private static Run run(Map<String, Object> r) {
        return new Run((String) r.get("pke_id"), (String) r.get("kind"), (String) r.get("target"), (String) r.get("mode"),
                Status.valueOf((String) r.get("status")), ((Number) r.getOrDefault("attempt", 1L)).intValue(),
                (String) r.get("retryOf"), r.get("createdAt"), r.get("startedAt"), r.get("finishedAt"),
                (String) r.get("fromVersion"), (String) r.get("toVersion"), (String) r.get("statsJson"),
                (String) r.get("unresolvedJson"), (String) r.get("error"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
