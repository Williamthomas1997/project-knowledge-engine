package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.PkeIds;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Evaluation runs ({@code :EvalRun}) and the lookups that resolve expected results. Operational: no events. */
@Service
public class EvalRunStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public EvalRunStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public String save(String dataset, String generation, String summaryJson, String resultsJson) {
        String id = PkeIds.uuidV7().toString();
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", id);
        props.put("dataset", dataset);
        props.put("generation", generation);
        props.put("summaryJson", summaryJson);
        props.put("resultsJson", resultsJson);
        props.put("at", OffsetDateTime.now(ZoneOffset.UTC));
        transactions.write("eval", id, ctx -> ctx.run("CREATE (r:EvalRun) SET r = $props", Map.of("props", props)));
        return id;
    }

    public List<Map<String, Object>> recent(int limit) {
        return reader.query("""
                MATCH (r:EvalRun) RETURN r.pke_id AS id, r.dataset AS dataset, r.generation AS generation,
                       r.summaryJson AS summaryJson, r.at AS at ORDER BY r.at DESC LIMIT $limit""",
                Map.of("limit", Math.clamp(limit, 1, 100)));
    }

    /** Current entities of {@code type} matching every property exactly and, if given, containing {@code text}. */
    public List<String> resolve(String type, Map<String, Object> props, String text) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        params.put("props", props == null ? Map.of() : props);
        params.put("text", text == null ? null : text.toLowerCase(java.util.Locale.ROOT));
        return reader.query("""
                MATCH (n:Entity {pke_type: $type}) WHERE coalesce(n.pke_status, 'CURRENT') = 'CURRENT'
                  AND all(k IN keys($props) WHERE toString(n[k]) = toString($props[k]))
                  AND ($text IS NULL OR toLower(coalesce(n.text, '') + ' ' + coalesce(n.summary, '') + ' '
                       + coalesce(n.signature, '') + ' ' + coalesce(n.fqcn, '') + ' ' + coalesce(n.pathTemplate, '')) CONTAINS $text)
                RETURN n.pke_id AS id""", params).stream().map(r -> (String) r.get("id")).toList();
    }
}
