package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Pipeline execution state: {@code (:PipelineExecution)-[:HAS_STEP]->(:StepExecution)} and per-step memos
 * ({@code :StepMemo}, results computed inside a step, e.g. one model call per section, so a retry or resume does
 * not repeat them). Operational records: no change events. Knowledge written by a step carries the execution
 * id as its change events' {@code operationId}.
 */
@Service
public class PipelineExecutionStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public PipelineExecutionStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public record NewStep(String stepId, String uses, int order) {
    }

    public record StepView(String stepId, String uses, int order, String status, int attempts, String errorClass,
                           String error, String outputJson, Object startedAt, Object finishedAt) {
    }

    public record ExecutionView(String id, String pipelineId, String pipelineVersion, String status, String inputJson,
                                String idempotencyKey, String actor, String errorClass, String error,
                                boolean cancelRequested, Object createdAt, Object startedAt, Object finishedAt,
                                List<StepView> steps) {
    }

    public String create(String pipelineId, String pipelineVersion, String inputJson, String idempotencyKey,
                         String actor, List<NewStep> steps) {
        String id = PkeIds.uuidV7().toString();
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", id);
        props.put("pipelineId", pipelineId);
        props.put("pipelineVersion", pipelineVersion);
        props.put("status", "PENDING");
        props.put("inputJson", inputJson);
        props.put("idempotencyKey", idempotencyKey);
        props.put("actor", actor);
        props.put("cancelRequested", false);
        props.put("createdAt", now());
        List<Map<String, Object>> stepRows = new ArrayList<>();
        for (NewStep step : steps) {
            Map<String, Object> row = new HashMap<>();
            row.put("pke_id", id + "#" + step.stepId());
            row.put("executionId", id);
            row.put("stepId", step.stepId());
            row.put("uses", step.uses());
            row.put("order", step.order());
            row.put("status", "PENDING");
            row.put("attempts", 0);
            stepRows.add(row);
        }
        transactions.write(actor, id, ctx -> ctx.run("""
                CREATE (e:PipelineExecution) SET e = $props
                WITH e
                UNWIND $steps AS row
                CREATE (e)-[:HAS_STEP]->(s:StepExecution) SET s = row""", Map.of("props", props, "steps", stepRows)));
        return id;
    }

    /** Sets the given fields (null removes a field). */
    public void updateExecution(String id, Map<String, Object> fields) {
        transactions.write("pipeline", id, ctx -> ctx.run("MATCH (e:PipelineExecution {pke_id: $id}) SET e += $fields",
                Map.of("id", id, "fields", fields)));
    }

    public void updateStep(String executionId, String stepId, Map<String, Object> fields) {
        transactions.write("pipeline", executionId, ctx -> ctx.run(
                "MATCH (s:StepExecution {pke_id: $id}) SET s += $fields",
                Map.of("id", executionId + "#" + stepId, "fields", fields)));
    }

    /** The newest execution with this key whose status is one of {@code statuses}. */
    public Optional<String> findByKey(String idempotencyKey, List<String> statuses) {
        return reader.single("""
                MATCH (e:PipelineExecution {idempotencyKey: $key}) WHERE e.status IN $statuses
                RETURN e.pke_id AS id ORDER BY e.createdAt DESC LIMIT 1""",
                Map.of("key", idempotencyKey, "statuses", statuses)).map(r -> (String) r.get("id"));
    }

    public Optional<ExecutionView> find(String id) {
        return reader.single("""
                MATCH (e:PipelineExecution {pke_id: $id})
                OPTIONAL MATCH (e)-[:HAS_STEP]->(s:StepExecution)
                WITH e, s ORDER BY s.order
                RETURN properties(e) AS e, collect(properties(s)) AS steps""", Map.of("id", id))
                .map(r -> view(map(r.get("e")), maps(r.get("steps"))));
    }

    public ExecutionView get(String id) {
        return find(id).orElseThrow(() -> new NotFoundException("Pipeline execution " + id + " does not exist"));
    }

    public List<ExecutionView> recent(String pipelineId, String status, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("pipelineId", pipelineId);
        params.put("status", status);
        params.put("limit", Math.clamp(limit, 1, 200));
        return reader.query("""
                MATCH (e:PipelineExecution)
                WHERE ($pipelineId IS NULL OR e.pipelineId = $pipelineId) AND ($status IS NULL OR e.status = $status)
                RETURN properties(e) AS e ORDER BY e.createdAt DESC LIMIT $limit""", params)
                .stream().map(r -> view(map(r.get("e")), List.of())).toList();
    }

    public Optional<String> memo(String executionId, String stepId, String key) {
        return reader.single("MATCH (m:StepMemo {pke_id: $id}) RETURN m.valueJson AS value",
                Map.of("id", memoId(executionId, stepId, key))).map(r -> (String) r.get("value"));
    }

    public void putMemo(String executionId, String stepId, String key, String valueJson) {
        transactions.write("pipeline", executionId, ctx -> ctx.run("""
                MERGE (m:StepMemo {pke_id: $id})
                SET m.executionId = $executionId, m.stepId = $stepId, m.key = $key, m.valueJson = $value, m.at = $at""",
                Map.of("id", memoId(executionId, stepId, key), "executionId", executionId, "stepId", stepId,
                        "key", key, "value", valueJson, "at", now())));
    }

    private static String memoId(String executionId, String stepId, String key) {
        return PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, "memo:" + executionId + "#" + stepId + "#" + key).toString();
    }

    private static ExecutionView view(Map<String, Object> e, List<Map<String, Object>> steps) {
        return new ExecutionView((String) e.get("pke_id"), (String) e.get("pipelineId"), (String) e.get("pipelineVersion"),
                (String) e.get("status"), (String) e.get("inputJson"), (String) e.get("idempotencyKey"),
                (String) e.get("actor"), (String) e.get("errorClass"), (String) e.get("error"),
                Boolean.TRUE.equals(e.get("cancelRequested")), e.get("createdAt"), e.get("startedAt"), e.get("finishedAt"),
                steps.stream().filter(s -> s.get("stepId") != null).map(s -> new StepView((String) s.get("stepId"),
                        (String) s.get("uses"), ((Number) s.get("order")).intValue(), (String) s.get("status"),
                        ((Number) s.getOrDefault("attempts", 0L)).intValue(), (String) s.get("errorClass"),
                        (String) s.get("error"), (String) s.get("outputJson"), s.get("startedAt"), s.get("finishedAt")))
                        .toList());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
