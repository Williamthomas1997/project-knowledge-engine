package com.omnicore.pke.engine.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Historical reconstruction from versions (plan §5, {@code docs/versioning.md}): the state as of {@code seq S} is
 * every version with {@code validFromSeq <= S < validToSeq} (open-ended when {@code validToSeq} is null) whose status
 * is {@code CURRENT}. Events say why; versions say what.
 */
@Service
public class HistoryQueries {

    public static final int MAX_ITEMS = 5000;

    private final GraphReader reader;

    public HistoryQueries(GraphReader reader) {
        this.reader = reader;
    }

    /** One entity or relationship as it was at a seq. {@code subjectId}/{@code objectId} only for relationships. */
    public record ItemState(String kind, String id, String type, long version, String status, String epistemic,
                            String subjectId, String objectId, Map<String, Object> properties) {
    }

    /** An item that differs between two seqs; {@code before} or {@code after} is null when it did not exist / was invalid. */
    public record ItemChange(String kind, String id, String type, String change, ItemState before, ItemState after,
                             String reason, List<Map<String, Object>> evidence) {
    }

    private static final String VALID_AT = "v.validFromSeq <= $seq AND (v.validToSeq IS NULL OR v.validToSeq > $seq)";

    /** Entities and relationships that were current at {@code seq}, optionally of one type. */
    public List<ItemState> stateAt(long seq, String type, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("seq", seq);
        params.put("type", type);
        params.put("limit", Math.clamp(limit, 1, MAX_ITEMS));
        List<ItemState> items = new ArrayList<>();
        reader.query("""
                MATCH (n:Entity)-[:HAS_VERSION]->(v:EntityVersion)
                WHERE ($type IS NULL OR n.pke_type = $type) AND v.status = 'CURRENT' AND\s""" + VALID_AT + """

                RETURN n.pke_id AS id, n.pke_type AS type, properties(v) AS v ORDER BY id LIMIT $limit""", params)
                .forEach(r -> items.add(state("ENTITY", r, null, null)));
        reader.query("""
                MATCH (a:Assertion)-[:HAS_VERSION]->(v:AssertionVersion)
                WHERE ($type IS NULL OR a.pke_type = $type) AND v.status = 'CURRENT' AND\s""" + VALID_AT + """

                RETURN a.pke_id AS id, a.pke_type AS type, a.pke_subjectId AS subjectId, a.pke_objectId AS objectId,
                       properties(v) AS v ORDER BY id LIMIT $limit""", params)
                .forEach(r -> items.add(state("RELATIONSHIP", r, (String) r.get("subjectId"), (String) r.get("objectId"))));
        return items;
    }

    /**
     * Items whose state differs between {@code from} and {@code to} ({@code from < to}): {@code ADDED} (not current at
     * {@code from}, current at {@code to}), {@code INVALIDATED} (the reverse) and {@code CHANGED} (current at both,
     * different content). An item created and invalidated in between is not listed. Evidence is what was recorded
     * in between.
     */
    public List<ItemChange> diff(long from, long to) {
        List<ItemChange> changes = new ArrayList<>();
        Map<String, Object> params = Map.of("from", from, "to", to, "limit", MAX_ITEMS);
        reader.query("""
                MATCH (v:EntityVersion) WHERE v.validFromSeq > $from AND v.validFromSeq <= $to
                WITH DISTINCT v.entityId AS id LIMIT $limit
                MATCH (n:Entity {pke_id: id})
                OPTIONAL MATCH (n)-[:HAS_VERSION]->(b:EntityVersion)
                  WHERE b.validFromSeq <= $from AND (b.validToSeq IS NULL OR b.validToSeq > $from)
                OPTIONAL MATCH (n)-[:HAS_VERSION]->(a:EntityVersion)
                  WHERE a.validFromSeq <= $to AND (a.validToSeq IS NULL OR a.validToSeq > $to)
                OPTIONAL MATCH (n)-[:SUPPORTED_BY]->(e:Evidence) WHERE e.recordedSeq > $from AND e.recordedSeq <= $to
                RETURN id, n.pke_type AS type, properties(b) AS before, properties(a) AS after,
                       n.pke_invalidationReason AS reason, collect(properties(e)) AS evidence ORDER BY id""", params)
                .forEach(r -> change("ENTITY", r, null, null).ifPresent(changes::add));
        reader.query("""
                MATCH (v:AssertionVersion) WHERE v.validFromSeq > $from AND v.validFromSeq <= $to
                WITH DISTINCT v.assertionId AS id LIMIT $limit
                MATCH (x:Assertion {pke_id: id})
                OPTIONAL MATCH (x)-[:HAS_VERSION]->(b:AssertionVersion)
                  WHERE b.validFromSeq <= $from AND (b.validToSeq IS NULL OR b.validToSeq > $from)
                OPTIONAL MATCH (x)-[:HAS_VERSION]->(a:AssertionVersion)
                  WHERE a.validFromSeq <= $to AND (a.validToSeq IS NULL OR a.validToSeq > $to)
                OPTIONAL MATCH (x)-[:SUPPORTED_BY]->(e:Evidence) WHERE e.recordedSeq > $from AND e.recordedSeq <= $to
                RETURN id, x.pke_type AS type, x.pke_subjectId AS subjectId, x.pke_objectId AS objectId,
                       properties(b) AS before, properties(a) AS after, x.pke_invalidationReason AS reason,
                       collect(properties(e)) AS evidence ORDER BY id""", params)
                .forEach(r -> change("RELATIONSHIP", r, (String) r.get("subjectId"), (String) r.get("objectId"))
                        .ifPresent(changes::add));
        return changes;
    }

    /** Current entities of a type with their domain properties (migrations). */
    public List<ItemState> currentEntities(String type) {
        return reader.query("""
                MATCH (n:Entity {pke_type: $type, pke_status: 'CURRENT'})
                RETURN n.pke_id AS id, n.pke_type AS type, properties(n) AS n ORDER BY id""", Map.of("type", type))
                .stream().map(r -> {
                    Map<String, Object> n = KnowledgeQueryService.map(r.get("n"));
                    return new ItemState("ENTITY", (String) r.get("id"), (String) r.get("type"),
                            KnowledgeQueryService.number(n.get("pke_version")), "CURRENT", (String) n.get("pke_epistemic"),
                            null, null, KnowledgeWriter.domainProperties(n));
                }).toList();
    }

    private static java.util.Optional<ItemChange> change(String kind, Map<String, Object> r, String subjectId,
                                                         String objectId) {
        ItemState before = versionState(kind, r, "before", subjectId, objectId);
        ItemState after = versionState(kind, r, "after", subjectId, objectId);
        String change;
        if (before == null && after == null) {
            return java.util.Optional.empty();
        } else if (before == null) {
            change = "ADDED";
        } else if (after == null) {
            change = "INVALIDATED";
        } else if (before.version() != after.version() && !before.properties().equals(after.properties())
                || !java.util.Objects.equals(before.epistemic(), after.epistemic())) {
            change = "CHANGED";
        } else {
            return java.util.Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = ((List<Map<String, Object>>) r.get("evidence")).stream()
                .map(e -> {
                    Map<String, Object> view = new java.util.TreeMap<>(e);
                    view.put("id", view.remove("pke_id"));
                    return (Map<String, Object>) view;
                }).toList();
        return java.util.Optional.of(new ItemChange(kind, (String) r.get("id"), (String) r.get("type"), change, before,
                after, "INVALIDATED".equals(change) ? (String) r.get("reason") : null, evidence));
    }

    /** The version as a state, or null when absent or not CURRENT (an invalidated item does not exist at that seq). */
    private static ItemState versionState(String kind, Map<String, Object> r, String column, String subjectId,
                                          String objectId) {
        Object value = r.get(column);
        if (value == null) {
            return null;
        }
        Map<String, Object> v = KnowledgeQueryService.map(value);
        if (!"CURRENT".equals(v.get("status"))) {
            return null;
        }
        return new ItemState(kind, (String) r.get("id"), (String) r.get("type"), KnowledgeQueryService.number(v.get("version")),
                "CURRENT", (String) v.get("epistemic"), subjectId, objectId,
                KnowledgeQueryService.json((String) v.get("propsJson")));
    }

    private static ItemState state(String kind, Map<String, Object> r, String subjectId, String objectId) {
        Map<String, Object> v = KnowledgeQueryService.map(r.get("v"));
        return new ItemState(kind, (String) r.get("id"), (String) r.get("type"), KnowledgeQueryService.number(v.get("version")),
                (String) v.get("status"), (String) v.get("epistemic"), subjectId, objectId,
                KnowledgeQueryService.json((String) v.get("propsJson")));
    }
}
