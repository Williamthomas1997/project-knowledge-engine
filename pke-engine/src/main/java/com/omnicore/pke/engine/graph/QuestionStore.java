package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.NotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Open questions ({@code :OpenQuestion}): a conflict the engine must not guess. Nothing is written to knowledge
 * until a human answers; the answer is then applied as {@code HUMAN_VERIFIED} evidence through the writer.
 * <p>
 * Links: {@code CONCERNS} the entities involved, {@code CITES} the candidate's source section and the existing
 * entity's evidence. Operational records: no change events (the applied answer produces them).
 */
@Service
public class QuestionStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public QuestionStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public record NewQuestion(String id, String kind, String question, String optionsJson, String payloadJson,
                              String documentKey, String executionId, List<String> concernsIds, List<String> sectionIds) {
    }

    public record QuestionView(String id, String kind, String status, String question, String optionsJson,
                               String payloadJson, String documentKey, String executionId, Object createdAt,
                               Object answeredAt, String answeredBy, String answer, String comment,
                               List<String> concerns, List<Map<String, Object>> citedEvidence, List<String> citedSections) {
    }

    /** Creates the question unless it already exists (same id: same conflict). Returns true when created. */
    public boolean create(NewQuestion q, String actor) {
        Map<String, Object> props = new HashMap<>();
        props.put("kind", q.kind());
        props.put("status", "OPEN");
        props.put("question", q.question());
        props.put("optionsJson", q.optionsJson());
        props.put("payloadJson", q.payloadJson());
        props.put("documentKey", q.documentKey());
        props.put("executionId", q.executionId());
        props.put("createdBy", actor);
        props.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC));
        return transactions.write(actor, q.executionId(), ctx -> {
            if (ctx.single("MATCH (q:OpenQuestion {pke_id: $id}) RETURN q.pke_id AS id", Map.of("id", q.id())).isPresent()) {
                return false;
            }
            ctx.run("""
                    CREATE (q:OpenQuestion {pke_id: $id}) SET q += $props
                    WITH q
                    UNWIND $concerns AS entityId
                    MATCH (n:Entity {pke_id: entityId})
                    CREATE (q)-[:CONCERNS]->(n)
                    WITH DISTINCT q, n
                    OPTIONAL MATCH (n)-[:SUPPORTED_BY]->(ev:Evidence)
                    FOREACH (_ IN CASE WHEN ev IS NULL THEN [] ELSE [1] END | MERGE (q)-[:CITES]->(ev))""",
                    Map.of("id", q.id(), "props", props, "concerns", q.concernsIds()));
            ctx.run("""
                    MATCH (q:OpenQuestion {pke_id: $id})
                    UNWIND $sections AS sectionId
                    MATCH (s:Section {pke_id: sectionId})
                    MERGE (q)-[:CITES]->(s)""", Map.of("id", q.id(), "sections", q.sectionIds()));
            return true;
        });
    }

    public void markAnswered(String id, String answer, String comment, String actor, Long appliedFirstSeq,
                             Long appliedLastSeq) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", "ANSWERED");
        fields.put("answer", answer);
        fields.put("comment", comment);
        fields.put("answeredBy", actor);
        fields.put("answeredAt", OffsetDateTime.now(ZoneOffset.UTC));
        fields.put("appliedFirstSeq", appliedFirstSeq);
        fields.put("appliedLastSeq", appliedLastSeq);
        transactions.write(actor, id, ctx -> ctx.run("MATCH (q:OpenQuestion {pke_id: $id}) SET q += $fields",
                Map.of("id", id, "fields", fields)));
    }

    public Optional<QuestionView> find(String id) {
        return reader.single("""
                MATCH (q:OpenQuestion {pke_id: $id})
                OPTIONAL MATCH (q)-[:CONCERNS]->(n:Entity)
                WITH q, collect(n.pke_id) AS concerns
                OPTIONAL MATCH (q)-[:CITES]->(ev:Evidence)
                WITH q, concerns, collect(properties(ev)) AS evidence
                OPTIONAL MATCH (q)-[:CITES]->(s:Section)
                RETURN properties(q) AS q, concerns, evidence, collect(s.pke_id) AS sections""", Map.of("id", id))
                .map(r -> view(map(r.get("q")), strings(r.get("concerns")), maps(r.get("evidence")), strings(r.get("sections"))));
    }

    public QuestionView get(String id) {
        return find(id).orElseThrow(() -> new NotFoundException("Question " + id + " does not exist"));
    }

    public List<QuestionView> list(String status, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        params.put("limit", Math.clamp(limit, 1, 200));
        return reader.query("""
                MATCH (q:OpenQuestion) WHERE $status IS NULL OR q.status = $status
                OPTIONAL MATCH (q)-[:CONCERNS]->(n:Entity)
                WITH q, collect(n.pke_id) AS concerns
                RETURN properties(q) AS q, concerns ORDER BY q.createdAt LIMIT $limit""", params)
                .stream().map(r -> view(map(r.get("q")), strings(r.get("concerns")), List.of(), List.of())).toList();
    }

    private static QuestionView view(Map<String, Object> q, List<String> concerns, List<Map<String, Object>> evidence,
                                     List<String> sections) {
        return new QuestionView((String) q.get("pke_id"), (String) q.get("kind"), (String) q.get("status"),
                (String) q.get("question"), (String) q.get("optionsJson"), (String) q.get("payloadJson"),
                (String) q.get("documentKey"), (String) q.get("executionId"), q.get("createdAt"), q.get("answeredAt"),
                (String) q.get("answeredBy"), (String) q.get("answer"), (String) q.get("comment"), concerns,
                evidence.stream().map(e -> {
                    Map<String, Object> view = new HashMap<>(e);
                    view.put("id", view.remove("pke_id"));
                    return view;
                }).toList(), sections);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
