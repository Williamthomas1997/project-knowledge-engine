package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.PkeIds;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The cost ledger's storage: one {@code :ModelCall} node per model call. Operational records written through
 * the single write path, without change events (they are not knowledge).
 */
@Service
public class ModelCallStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public ModelCallStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public record ModelCall(String provider, String model, String skill, String skillVersion, int tokensIn,
                            int tokensOut, Double costEstimate, String executionId, String stepId, String inputHash,
                            String promptHash, long durationMs, boolean replayed, String outcome, String error) {
    }

    public void record(ModelCall call) {
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", PkeIds.uuidV7().toString());
        props.put("provider", call.provider());
        props.put("model", call.model());
        props.put("skill", call.skill());
        props.put("skillVersion", call.skillVersion());
        props.put("tokensIn", call.tokensIn());
        props.put("tokensOut", call.tokensOut());
        props.put("costEstimate", call.costEstimate());
        props.put("executionId", call.executionId());
        props.put("stepId", call.stepId());
        props.put("inputHash", call.inputHash());
        props.put("promptHash", call.promptHash());
        props.put("durationMs", call.durationMs());
        props.put("replayed", call.replayed());
        props.put("outcome", call.outcome());
        props.put("error", call.error());
        props.put("at", OffsetDateTime.now(ZoneOffset.UTC));
        transactions.write("ai", call.executionId(), ctx -> ctx.run("CREATE (c:ModelCall) SET c = $props",
                Map.of("props", props)));
    }

    /** Most recent calls, optionally of one pipeline execution. */
    public List<Map<String, Object>> recent(String executionId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("executionId", executionId);
        params.put("limit", Math.clamp(limit, 1, 500));
        return reader.query("""
                MATCH (c:ModelCall) WHERE $executionId IS NULL OR c.executionId = $executionId
                RETURN properties(c) AS c ORDER BY c.at DESC LIMIT $limit""", params)
                .stream().map(r -> map(r.get("c"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        Map<String, Object> view = new HashMap<>((Map<String, Object>) value);
        view.put("id", view.remove("pke_id"));
        return view;
    }
}
