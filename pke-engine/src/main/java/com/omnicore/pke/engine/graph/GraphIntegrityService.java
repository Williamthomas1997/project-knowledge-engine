package com.omnicore.pke.engine.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Checks the invariant behind the dual relationship representation (ADR-001): every CURRENT assertion has
 * exactly one native edge of its type between its subject and object, and every native knowledge edge belongs to
 * a CURRENT assertion with matching type and endpoints. Read-only.
 */
@Service
public class GraphIntegrityService {

    static final int MAX_VIOLATIONS = 1000;

    private final GraphReader reader;

    public GraphIntegrityService(GraphReader reader) {
        this.reader = reader;
    }

    public enum Kind { ASSERTION_WITHOUT_EDGE, DUPLICATE_EDGE, EDGE_WITHOUT_ASSERTION, EDGE_FOR_INVALID_ASSERTION, EDGE_MISMATCH }

    public record IntegrityViolation(Kind kind, String relationshipId, String detail) {
    }

    public record IntegrityReport(long currentAssertions, long knowledgeEdges, List<IntegrityViolation> violations) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    public IntegrityReport check() {
        long assertions = count("MATCH (a:Assertion {pke_status: 'CURRENT'}) RETURN count(a) AS c");
        long edges = count("MATCH (:Entity)-[r]->(:Entity) WHERE r.pke_relId IS NOT NULL RETURN count(r) AS c");
        List<IntegrityViolation> violations = new ArrayList<>();
        reader.query("""
                MATCH (a:Assertion {pke_status: 'CURRENT'})
                OPTIONAL MATCH (s:Entity {pke_id: a.pke_subjectId})-[r]->(o:Entity {pke_id: a.pke_objectId})
                  WHERE r.pke_relId = a.pke_id AND type(r) = a.pke_type
                WITH a, count(r) AS edges WHERE edges <> 1
                RETURN a.pke_id AS id, a.pke_type AS type, edges LIMIT $limit""", Map.of("limit", MAX_VIOLATIONS))
                .forEach(row -> {
                    long n = ((Number) row.get("edges")).longValue();
                    violations.add(new IntegrityViolation(n == 0 ? Kind.ASSERTION_WITHOUT_EDGE : Kind.DUPLICATE_EDGE,
                            (String) row.get("id"), row.get("type") + " assertion has " + n + " native edges"));
                });
        reader.query("""
                MATCH (s:Entity)-[r]->(o:Entity) WHERE r.pke_relId IS NOT NULL
                OPTIONAL MATCH (a:Assertion {pke_id: r.pke_relId})
                WITH s, r, o, a
                WHERE a IS NULL OR a.pke_status <> 'CURRENT' OR a.pke_type <> type(r)
                   OR a.pke_subjectId <> s.pke_id OR a.pke_objectId <> o.pke_id
                RETURN r.pke_relId AS id, type(r) AS type,
                       CASE WHEN a IS NULL THEN 'EDGE_WITHOUT_ASSERTION'
                            WHEN a.pke_status <> 'CURRENT' THEN 'EDGE_FOR_INVALID_ASSERTION'
                            ELSE 'EDGE_MISMATCH' END AS kind
                LIMIT $limit""", Map.of("limit", MAX_VIOLATIONS))
                .forEach(row -> violations.add(new IntegrityViolation(Kind.valueOf((String) row.get("kind")),
                        (String) row.get("id"), "native " + row.get("type") + " edge")));
        return new IntegrityReport(assertions, edges, violations);
    }

    private long count(String cypher) {
        return ((Number) reader.single(cypher, Map.of()).orElseThrow().get("c")).longValue();
    }
}
