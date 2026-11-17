package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.NotFoundException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Journey interviews in progress (M6): {@code (:InterviewSession {pke_id, journeyKey, status, stateJson, ...})}.
 * The state (transcript, draft steps, pending question) is one JSON document; the journey itself is written as
 * knowledge only when the interview completes. Operational records, no change events.
 */
@Service
public class InterviewSessionStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public InterviewSessionStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public void save(String id, String journeyKey, String status, Map<String, Object> state, String actor) {
        Map<String, Object> props = new HashMap<>();
        props.put("journeyKey", journeyKey);
        props.put("status", status);
        props.put("stateJson", CanonicalJson.write(state));
        props.put("updatedBy", actor);
        transactions.write(actor, null, ctx -> {
            props.put("updatedAt", ctx.now());
            return ctx.run("""
                    MERGE (s:InterviewSession {pke_id: $id})
                    ON CREATE SET s.createdAt = $props.updatedAt, s.createdBy = $props.updatedBy
                    SET s += $props""", Map.of("id", id, "props", props));
        });
    }

    /** {@code {id, journeyKey, status, state}}. */
    public Map<String, Object> get(String id) {
        return reader.single("MATCH (s:InterviewSession {pke_id: $id}) RETURN properties(s) AS s", Map.of("id", id))
                .map(r -> {
                    Map<String, Object> s = KnowledgeQueryService.map(r.get("s"));
                    Map<String, Object> view = new HashMap<>();
                    view.put("id", id);
                    view.put("journeyKey", s.get("journeyKey"));
                    view.put("status", s.get("status"));
                    view.put("state", KnowledgeQueryService.json((String) s.get("stateJson")));
                    return view;
                })
                .orElseThrow(() -> new NotFoundException("Interview session " + id + " not found"));
    }
}
