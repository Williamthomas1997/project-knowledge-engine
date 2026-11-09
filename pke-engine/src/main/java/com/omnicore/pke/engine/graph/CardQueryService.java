package com.omnicore.pke.engine.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Read queries over knowledge cards and the code elements they may be implemented by. Cards are linked to their
 * source sections through evidence: {@code (card)-[:SUPPORTED_BY]->(:Evidence)-[:CITES]->(:Section)}.
 */
@Service
public class CardQueryService {

    private final GraphReader reader;

    public CardQueryService(GraphReader reader) {
        this.reader = reader;
    }

    public record CardView(String id, String text, String kind, List<String> keyTerms, String requirementId,
                           String documentKey, String epistemic, String status, long version) {

        /** The card's domain properties as written. */
        public Map<String, Object> properties() {
            Map<String, Object> props = new HashMap<>();
            props.put("text", text);
            props.put("kind", kind);
            props.put("documentKey", documentKey);
            if (keyTerms != null && !keyTerms.isEmpty()) {
                props.put("keyTerms", keyTerms);
            }
            if (requirementId != null) {
                props.put("requirementId", requirementId);
            }
            return props;
        }
    }

    private static final String CARD = """
            {id: c.pke_id, text: c.text, kind: c.kind, keyTerms: c.keyTerms, requirementId: c.requirementId,
             documentKey: c.documentKey, epistemic: c.pke_epistemic, status: coalesce(c.pke_status, 'CURRENT'),
             version: c.pke_version}""";

    public Optional<CardView> card(String id) {
        return reader.single("MATCH (c:KnowledgeCard {pke_id: $id}) RETURN " + CARD + " AS card", Map.of("id", id))
                .map(r -> view(r.get("card")));
    }

    /** Current cards whose evidence cites a section of {@code documentKey} with this heading path (any version). */
    public List<CardView> cardsInScope(String documentKey, List<String> headingPath) {
        return reader.query("""
                MATCH (c:KnowledgeCard)-[:SUPPORTED_BY]->(:Evidence)-[:CITES]->(s:Section {artifactKey: $doc})
                WHERE coalesce(c.pke_status, 'CURRENT') = 'CURRENT' AND s.headingPath = $path
                WITH DISTINCT c ORDER BY c.pke_createdSeq, c.pke_id
                RETURN""" + " " + CARD + " AS card", Map.of("doc", documentKey, "path", headingPath))
                .stream().map(r -> view(r.get("card"))).toList();
    }

    /**
     * Current cards derived from {@code documentKey} none of whose cited sections of that document has a heading
     * path in {@code currentPaths}: the source no longer contains the section they came from.
     */
    public List<CardView> cardsOutsidePaths(String documentKey, List<List<String>> currentPaths) {
        return reader.query("""
                MATCH (c:KnowledgeCard)
                WHERE coalesce(c.pke_status, 'CURRENT') = 'CURRENT'
                  AND EXISTS { (c)-[:SUPPORTED_BY]->(:Evidence)-[:CITES]->(:Section {artifactKey: $doc}) }
                  AND NOT EXISTS {
                    MATCH (c)-[:SUPPORTED_BY]->(:Evidence)-[:CITES]->(s:Section {artifactKey: $doc})
                    WHERE s.headingPath IN $paths
                  }
                WITH c ORDER BY c.pke_id
                RETURN""" + " " + CARD + " AS card", Map.of("doc", documentKey, "paths", currentPaths))
                .stream().map(r -> view(r.get("card"))).toList();
    }

    /**
     * Fulltext search over current card texts (Lucene syntax), plus graph neighbours: current cards with one of
     * {@code requirementIds}. Excludes {@code excludeIds}.
     */
    public List<CardView> similar(String luceneQuery, List<String> requirementIds, List<String> excludeIds, int limit) {
        List<CardView> result = new ArrayList<>();
        List<String> seen = new ArrayList<>(excludeIds);
        if (luceneQuery != null && !luceneQuery.isBlank()) {
            reader.query("""
                    CALL db.index.fulltext.queryNodes('pke_card_text', $q) YIELD node, score
                    WITH node AS c, score WHERE coalesce(c.pke_status, 'CURRENT') = 'CURRENT' AND NOT c.pke_id IN $exclude
                    WITH c, score ORDER BY score DESC, c.pke_id LIMIT $limit
                    RETURN""" + " " + CARD + " AS card", Map.of("q", luceneQuery, "exclude", seen, "limit", limit))
                    .forEach(r -> {
                        CardView card = view(r.get("card"));
                        result.add(card);
                        seen.add(card.id());
                    });
        }
        if (!requirementIds.isEmpty()) {
            reader.query("""
                    MATCH (c:KnowledgeCard) WHERE c.requirementId IN $ids AND coalesce(c.pke_status, 'CURRENT') = 'CURRENT'
                      AND NOT c.pke_id IN $exclude
                    WITH c ORDER BY c.pke_createdSeq, c.pke_id LIMIT $limit
                    RETURN""" + " " + CARD + " AS card", Map.of("ids", requirementIds, "exclude", seen, "limit", limit))
                    .forEach(r -> result.add(view(r.get("card"))));
        }
        return result;
    }

    /** Current cards of the given kinds, for linking every requirement after a code change. */
    public List<CardView> currentCards(List<String> kinds) {
        return reader.query("""
                MATCH (c:KnowledgeCard) WHERE c.kind IN $kinds AND coalesce(c.pke_status, 'CURRENT') = 'CURRENT'
                WITH c ORDER BY c.documentKey, c.pke_createdSeq, c.pke_id
                RETURN""" + " " + CARD + " AS card", Map.of("kinds", kinds))
                .stream().map(r -> view(r.get("card"))).toList();
    }

    /** Code elements throwing one of the error codes (non-test methods). */
    public List<Map<String, Object>> methodsThrowing(List<String> codes) {
        return reader.query("""
                MATCH (m:JavaMethod)-[:THROWS_ERROR]->(e:ErrorCode) WHERE e.code IN $codes AND coalesce(m.test, false) = false
                RETURN m.pke_id AS id, 'JavaMethod' AS type, m.fqcn AS fqcn, m.signature AS signature,
                       collect(DISTINCT e.code) AS errorCodes ORDER BY fqcn, signature""", Map.of("codes", codes));
    }

    /** Non-test methods whose signature, or whose class name, contains one of the (lower-case) terms. */
    public List<Map<String, Object>> methodsMatching(List<String> terms, int limit) {
        return reader.query("""
                MATCH (m:JavaMethod) WHERE coalesce(m.test, false) = false
                WITH m, [t IN $terms WHERE toLower(m.signature) CONTAINS t
                         OR toLower(split(m.fqcn, '.')[-1]) CONTAINS t] AS hits
                WHERE size(hits) > 0
                OPTIONAL MATCH (m)-[:THROWS_ERROR]->(e:ErrorCode)
                WITH m, hits, collect(DISTINCT e.code) AS errorCodes
                RETURN m.pke_id AS id, 'JavaMethod' AS type, m.fqcn AS fqcn, m.signature AS signature,
                       size(hits) AS hits, errorCodes
                ORDER BY hits DESC, fqcn, signature LIMIT $limit""", Map.of("terms", terms, "limit", limit));
    }

    /** APIs (gateway and service layer) whose path contains one of the terms, with their handler methods. */
    public List<Map<String, Object>> apisMatching(List<String> terms, int limit) {
        return reader.query("""
                MATCH (a:ApiOperation)
                WITH a, [t IN $terms WHERE toLower(a.pathTemplate) CONTAINS t] AS hits WHERE size(hits) > 0
                OPTIONAL MATCH (a)-[:HANDLED_BY]->(m:JavaMethod)
                RETURN a.pke_id AS id, 'ApiOperation' AS type, a.layer AS layer, a.service AS service,
                       a.httpMethod AS httpMethod, a.pathTemplate AS pathTemplate, size(hits) AS hits,
                       collect(m.fqcn + '#' + m.signature) AS handlers
                ORDER BY hits DESC, layer DESC, pathTemplate LIMIT $limit""", Map.of("terms", terms, "limit", limit));
    }

    @SuppressWarnings("unchecked")
    private static CardView view(Object value) {
        Map<String, Object> c = (Map<String, Object>) value;
        return new CardView((String) c.get("id"), (String) c.get("text"), (String) c.get("kind"),
                c.get("keyTerms") == null ? List.of() : (List<String>) c.get("keyTerms"), (String) c.get("requirementId"),
                (String) c.get("documentKey"), (String) c.get("epistemic"), (String) c.get("status"),
                c.get("version") == null ? 0 : ((Number) c.get("version")).longValue());
    }
}
