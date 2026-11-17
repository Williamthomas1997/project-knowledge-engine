package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.PkeIds;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The audit log (ADR-017): one {@code :AuditEvent} per mutating REST call or MCP tool call, including rejected
 * ones. Operational records, no change events: what changed is in the change events; this says who asked.
 */
@Service
public class AuditStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public AuditStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    /**
     * @param channel {@code REST} or {@code MCP}
     * @param action  e.g. {@code POST /api/history/restore} or {@code tool answer_question}
     * @param outcome HTTP status, or {@code OK} / {@code DENIED} / {@code ERROR} for tools
     */
    public void record(String actor, List<String> roles, String channel, String action, String outcome) {
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", PkeIds.uuidV7().toString());
        props.put("actor", actor);
        props.put("roles", roles);
        props.put("channel", channel);
        props.put("action", action.length() > 500 ? action.substring(0, 500) : action);
        props.put("outcome", outcome);
        transactions.write(actor, null, ctx -> {
            props.put("at", ctx.now());
            return ctx.run("CREATE (a:AuditEvent) SET a = $props", Map.of("props", props));
        });
    }

    public List<Map<String, Object>> recent(int limit) {
        return reader.query("""
                MATCH (a:AuditEvent) RETURN properties(a) AS a ORDER BY a.at DESC, a.pke_id DESC LIMIT $limit""",
                Map.of("limit", Math.clamp(limit, 1, 500)))
                .stream().map(r -> KnowledgeQueryService.map(r.get("a"))).toList();
    }
}
