package com.omnicore.pke.engine.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Read queries for hybrid retrieval and context generation (ADR-007 §7). */
@Service
public class RetrievalQueries {

    private final GraphReader reader;

    public RetrievalQueries(GraphReader reader) {
        this.reader = reader;
    }

    /** Lucene search over entity names, signatures, paths, codes and texts ({@code pke_entity_search} over {@code pke_searchText}). */
    public List<Map<String, Object>> entityFulltext(String luceneQuery, int limit, boolean includeInvalid) {
        if (luceneQuery == null || luceneQuery.isBlank()) {
            return List.of();
        }
        return reader.query("""
                CALL db.index.fulltext.queryNodes('pke_entity_search', $q) YIELD node, score
                WITH node, score WHERE ($includeInvalid OR coalesce(node.pke_status, 'CURRENT') = 'CURRENT')
                  AND NOT coalesce(node.test, false)
                RETURN node.pke_id AS id, node.pke_type AS type, score ORDER BY score DESC, id LIMIT $limit""",
                Map.of("q", luceneQuery, "limit", limit, "includeInvalid", includeInvalid));
    }

    /** Properties of entities (domain and the system ones retrieval needs), by id. */
    public List<Map<String, Object>> entities(List<String> ids) {
        return reader.query("""
                UNWIND $ids AS id MATCH (n:Entity {pke_id: id})
                RETURN n.pke_id AS id, n.pke_type AS type, coalesce(n.pke_status, 'CURRENT') AS status,
                       n.pke_epistemic AS epistemic, n.pke_createdSeq AS createdSeq, properties(n) AS props""",
                Map.of("ids", ids));
    }

    /** Ids (among {@code ids}) of entities that were current at {@code seq}. */
    public List<String> validAsOf(List<String> ids, long seq) {
        return reader.query("""
                UNWIND $ids AS id MATCH (n:Entity {pke_id: id})-[:HAS_VERSION]->(v:EntityVersion)
                WHERE v.validFromSeq <= $seq AND (v.validToSeq IS NULL OR v.validToSeq > $seq)
                  AND coalesce(v.status, 'CURRENT') = 'CURRENT'
                RETURN DISTINCT n.pke_id AS id""", Map.of("ids", ids, "seq", seq))
                .stream().map(r -> (String) r.get("id")).toList();
    }

    /**
     * Facts with their epistemic status and evidence: entities and assertions by id. Evidence carries its source,
     * version, location and the heading of a cited section, for citations {@code source@version#location}.
     */
    public List<Map<String, Object>> facts(List<String> ids) {
        return reader.query("""
                UNWIND $ids AS id
                CALL (id) {
                  MATCH (n:Entity {pke_id: id}) RETURN n, 'ENTITY' AS kind
                  UNION
                  MATCH (n:Assertion {pke_id: id}) RETURN n, 'RELATIONSHIP' AS kind
                }
                OPTIONAL MATCH (n)-[:SUPPORTED_BY]->(e:Evidence)
                OPTIONAL MATCH (e)-[:CITES]->(s:Section)
                WITH n, kind, e, s ORDER BY e.recordedSeq
                RETURN n.pke_id AS id, kind, n.pke_type AS type, n.pke_epistemic AS epistemic,
                       coalesce(n.pke_status, 'CURRENT') AS status, n.pke_subjectId AS subjectId, n.pke_objectId AS objectId,
                       collect(CASE WHEN e IS NULL THEN NULL ELSE {sourceArtifactId: e.sourceArtifactId,
                         sourceVersion: e.sourceVersion, location: e.location, section: s.heading,
                         extractionMethod: e.extractionMethod, epistemic: e.epistemic, excerpt: e.excerpt,
                         modelId: e.modelId, confidence: e.confidenceReported} END) AS evidence""", Map.of("ids", ids));
    }

    /** Document sections cited by the evidence of the entities (a card's source section), with the citing entity. */
    public List<Map<String, Object>> citedSections(List<String> entityIds) {
        return reader.query("""
                MATCH (n:Entity)-[:SUPPORTED_BY]->(:Evidence)-[:CITES]->(s:Section) WHERE n.pke_id IN $ids
                RETURN DISTINCT n.pke_id AS entityId, s.pke_id AS sectionId, s.heading AS heading""", Map.of("ids", entityIds));
    }

    /** Open questions concerning any of the entities. */
    public List<Map<String, Object>> openQuestions(List<String> entityIds) {
        return reader.query("""
                MATCH (q:OpenQuestion {status: 'OPEN'})-[:CONCERNS]->(n:Entity) WHERE n.pke_id IN $ids
                RETURN DISTINCT q.pke_id AS id, q.kind AS kind, q.question AS question, collect(n.pke_id) AS concerns""",
                Map.of("ids", entityIds));
    }

    /** Sections of a document version (for section hits): artifact key, version and heading. */
    public List<Map<String, Object>> sections(List<String> ids) {
        return reader.query("""
                UNWIND $ids AS id MATCH (v:SourceVersion)-[:HAS_SECTION]->(s:Section {pke_id: id})
                RETURN s.pke_id AS id, s.artifactKey AS artifactKey, v.version AS version, s.heading AS heading,
                       s.headingPath AS headingPath, s.text AS text""", Map.of("ids", ids));
    }

    /** Whether citations resolve: a source with this version exists (version may be null for unversioned sources). */
    public boolean sourceVersionExists(String artifactKey, String version) {
        Map<String, Object> params = new HashMap<>();
        params.put("key", artifactKey);
        params.put("version", version);
        return reader.single("""
                OPTIONAL MATCH (a:SourceArtifact {key: $key})
                OPTIONAL MATCH (a)-[:HAS_VERSION]->(v:SourceVersion {version: $version})
                RETURN a IS NOT NULL AND ($version IS NULL OR v IS NOT NULL) AS ok""", params)
                .map(r -> Boolean.TRUE.equals(r.get("ok"))).orElse(false);
    }

    /** Entities around one entity, over current relationships, with the relationships. */
    public Map<String, Object> neighbourhood(String id, int depth, int limit) {
        List<Map<String, Object>> rows = reader.query("""
                MATCH (start:Entity {pke_id: $id})
                OPTIONAL MATCH path = (start)-[*1..3]-(other:Entity)
                WHERE length(path) <= $depth AND all(r IN relationships(path) WHERE r.pke_relId IS NOT NULL)
                WITH start, path ORDER BY length(path) LIMIT $limit
                UNWIND (CASE WHEN path IS NULL THEN [null] ELSE relationships(path) END) AS r
                WITH start, r WHERE r IS NOT NULL
                RETURN DISTINCT r.pke_relId AS id, type(r) AS type, startNode(r).pke_id AS from, endNode(r).pke_id AS to""",
                Map.of("id", id, "depth", Math.clamp(depth, 1, 3), "limit", Math.clamp(limit, 1, 500)));
        List<String> nodeIds = new java.util.ArrayList<>(List.of(id));
        rows.forEach(r -> {
            if (!nodeIds.contains((String) r.get("from"))) {
                nodeIds.add((String) r.get("from"));
            }
            if (!nodeIds.contains((String) r.get("to"))) {
                nodeIds.add((String) r.get("to"));
            }
        });
        return Map.of("nodes", entities(nodeIds), "relationships", rows);
    }
}
