package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.schema.EntityTypeDefinition;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The vector outbox (ADR-007 §3): {@code (:IndexTask {entityId, entityType, field, op: UPSERT|DELETE, status})}.
 * Tasks for knowledge writes are created by {@link KnowledgeWriter} in the same transaction as the change
 * ({@link #enqueue}); the index worker claims and completes them. Tasks are operational: no change events.
 */
@Service
public class IndexTaskStore {

    public enum Op { UPSERT, DELETE }

    public record Task(String id, String entityId, String entityType, String field, Op op, int attempts,
                       String operationId) {
    }

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public IndexTaskStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    /**
     * Inside a knowledge write: one task per index field whose value changed. {@code oldProps} is empty for a new
     * entity; {@code removed} = the entity stopped being current (every field is deleted from the index).
     */
    static void enqueue(GraphTransactions.WriteContext ctx, EntityTypeDefinition definition, String entityId,
                        Map<String, Object> oldProps, Map<String, Object> newProps, boolean forceUpsert, boolean removed) {
        if (definition == null || definition.embedding() == null) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String field : definition.embedding().indexFields()) {
            Object before = oldProps.get(field);
            Object after = removed ? null : newProps.get(field);
            Op op;
            if (after == null || String.valueOf(after).isBlank()) {
                if (before == null && !removed) {
                    continue;
                }
                op = Op.DELETE;
            } else if (forceUpsert || !Objects.equals(before, after)) {
                op = Op.UPSERT;
            } else {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("pke_id", PkeIds.uuidV7().toString());
            row.put("entityId", entityId);
            row.put("entityType", definition.name());
            row.put("field", field);
            row.put("op", op.name());
            row.put("status", "PENDING");
            row.put("attempts", 0);
            row.put("operationId", ctx.operationId());
            row.put("createdAt", ctx.now());
            row.put("nextAttemptAt", ctx.now());
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            ctx.run("UNWIND $rows AS row CREATE (t:IndexTask) SET t = row", Map.of("rows", rows));
        }
    }

    /** Outside a knowledge write (reconciliation): enqueue tasks in their own transaction. */
    public void enqueue(List<Task> tasks, String operationId) {
        if (tasks.isEmpty()) {
            return;
        }
        OffsetDateTime now = now();
        List<Map<String, Object>> rows = tasks.stream().map(t -> {
            Map<String, Object> row = new HashMap<>();
            row.put("pke_id", PkeIds.uuidV7().toString());
            row.put("entityId", t.entityId());
            row.put("entityType", t.entityType());
            row.put("field", t.field());
            row.put("op", t.op().name());
            row.put("status", "PENDING");
            row.put("attempts", 0);
            row.put("operationId", operationId);
            row.put("createdAt", now);
            row.put("nextAttemptAt", now);
            return row;
        }).toList();
        transactions.write("index", operationId, ctx -> ctx.run("UNWIND $rows AS row CREATE (t:IndexTask) SET t = row",
                Map.of("rows", rows)));
    }

    /** Tasks that are due (PENDING, or RETRY whose backoff has passed), oldest first. */
    public List<Task> due(int limit) {
        return reader.query("""
                MATCH (t:IndexTask) WHERE t.status IN ['PENDING', 'RETRY'] AND t.nextAttemptAt <= datetime()
                RETURN t.pke_id AS id, t.entityId AS entityId, t.entityType AS entityType, t.field AS field, t.op AS op,
                       t.attempts AS attempts, t.operationId AS operationId
                ORDER BY t.createdAt, t.pke_id LIMIT $limit""", Map.of("limit", limit))
                .stream().map(r -> new Task((String) r.get("id"), (String) r.get("entityId"), (String) r.get("entityType"),
                        (String) r.get("field"), Op.valueOf((String) r.get("op")), ((Number) r.get("attempts")).intValue(),
                        (String) r.get("operationId"))).toList();
    }

    /** Completed tasks are removed (the vector store now reflects the graph). */
    public void done(List<String> taskIds) {
        if (!taskIds.isEmpty()) {
            transactions.write("index", null, ctx -> ctx.run("MATCH (t:IndexTask) WHERE t.pke_id IN $ids DELETE t",
                    Map.of("ids", taskIds)));
        }
    }

    /** RETRY with exponential backoff, or FAILED after {@code maxAttempts}. */
    public void failed(List<Task> tasks, String error, long baseBackoffMs, int maxAttempts) {
        List<Map<String, Object>> rows = tasks.stream().map(t -> {
            int attempts = t.attempts() + 1;
            Map<String, Object> row = new HashMap<>();
            row.put("id", t.id());
            row.put("attempts", attempts);
            row.put("status", attempts >= maxAttempts ? "FAILED" : "RETRY");
            row.put("nextAttemptAt", now().plusNanos(Math.min(baseBackoffMs << Math.min(attempts - 1, 10), 600_000L) * 1_000_000));
            return row;
        }).toList();
        String message = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        transactions.write("index", null, ctx -> ctx.run("""
                UNWIND $rows AS row MATCH (t:IndexTask {pke_id: row.id})
                SET t.attempts = row.attempts, t.status = row.status, t.nextAttemptAt = row.nextAttemptAt, t.error = $error""",
                Map.of("rows", rows, "error", message == null ? "" : message)));
    }

    /** FAILED tasks become PENDING again (after the cause was fixed). */
    public long retryFailed() {
        return transactions.write("index", null, ctx -> ctx.single("""
                MATCH (t:IndexTask {status: 'FAILED'}) SET t.status = 'PENDING', t.attempts = 0, t.nextAttemptAt = datetime()
                RETURN count(t) AS c""", Map.of()).orElseThrow().get("c").asLong());
    }

    public long open(String operationId) {
        return ((Number) reader.single("""
                MATCH (t:IndexTask) WHERE t.operationId = $op RETURN count(t) AS c""", Map.of("op", operationId))
                .orElseThrow().get("c")).longValue();
    }

    /** Counts per status, and the oldest open task's creation time. */
    public Map<String, Object> summary() {
        Map<String, Object> summary = new HashMap<>(Map.of("PENDING", 0L, "RETRY", 0L, "FAILED", 0L));
        reader.query("MATCH (t:IndexTask) RETURN t.status AS status, count(t) AS c, min(t.createdAt) AS oldest", Map.of())
                .forEach(r -> {
                    summary.put((String) r.get("status"), r.get("c"));
                    summary.merge("oldest", r.get("oldest"), (a, b) -> String.valueOf(a).compareTo(String.valueOf(b)) < 0 ? a : b);
                });
        return summary;
    }

    public List<Map<String, Object>> perCollection() {
        return reader.query("""
                MATCH (t:IndexTask) RETURN t.entityType AS type, t.field AS field, t.status AS status, count(t) AS c""", Map.of());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
