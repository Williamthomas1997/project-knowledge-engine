package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Evolution requests (plan §10): a change the engine cannot make with metadata alone, e.g. a proposed relationship
 * type no existing capability can populate. Recorded {@code OPEN} here and consumed by the evolution pipeline (M7).
 * The id is derived from kind + subject, so proposing the same thing again does not duplicate it.
 */
@Service
public class EvolutionRequestStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public EvolutionRequestStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    /** Creates the request unless one with the same kind and subject exists; returns its id. */
    public String request(String kind, String subject, String reason, Map<String, Object> payload, String actor,
                          String operationId) {
        String id = PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, "evolution:" + kind + ":" + subject).toString();
        transactions.write(actor, operationId, ctx -> {
            Map<String, Object> props = new HashMap<>();
            props.put("kind", kind);
            props.put("subject", subject);
            props.put("reason", reason);
            props.put("payloadJson", CanonicalJson.write(payload));
            props.put("status", "OPEN");
            props.put("requestedBy", actor);
            props.put("operationId", operationId);
            props.put("createdAt", ctx.now());
            return ctx.run("MERGE (r:EvolutionRequest {pke_id: $id}) ON CREATE SET r += $props",
                    Map.of("id", id, "props", props));
        });
        return id;
    }

    public List<Map<String, Object>> list(String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        return reader.query("""
                MATCH (r:EvolutionRequest) WHERE $status IS NULL OR r.status = $status
                RETURN properties(r) AS r ORDER BY r.createdAt""", params)
                .stream().map(r -> {
                    Map<String, Object> view = new java.util.TreeMap<>(KnowledgeQueryService.map(r.get("r")));
                    view.put("id", view.remove("pke_id"));
                    view.put("payload", KnowledgeQueryService.json((String) view.remove("payloadJson")));
                    return (Map<String, Object>) view;
                }).toList();
    }
}
