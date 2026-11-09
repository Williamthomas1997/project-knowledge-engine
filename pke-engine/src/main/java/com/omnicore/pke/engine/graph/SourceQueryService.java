package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Read side of sources: artifacts, versions, sections and fulltext section search. */
@Service
public class SourceQueryService {

    private final GraphReader reader;

    public SourceQueryService(GraphReader reader) {
        this.reader = reader;
    }

    public record SourceView(String id, String key, String kind, String status, String currentVersion,
                             Map<String, Object> properties) {
    }

    public record VersionView(String id, String version, String kind, Map<String, Object> properties) {
    }

    public record SectionView(String id, String artifactKey, String version, long order, List<String> headingPath,
                              String heading, Object page, String text, Double score) {
    }

    public List<SourceView> sources(String kind) {
        Map<String, Object> params = new HashMap<>();
        params.put("kind", kind);
        return reader.query("""
                MATCH (a:SourceArtifact) WHERE $kind IS NULL OR a.kind = $kind
                RETURN properties(a) AS a ORDER BY a.key""", params)
                .stream().map(r -> source(map(r.get("a")))).toList();
    }

    public Optional<SourceView> findByKey(String key) {
        return reader.single("MATCH (a:SourceArtifact {pke_id: $id}) RETURN properties(a) AS a",
                Map.of("id", PkeIds.sourceArtifactId(key))).map(r -> source(map(r.get("a"))));
    }

    public SourceView byKey(String key) {
        return findByKey(key).orElseThrow(() -> new NotFoundException("Source " + key + " is not registered"));
    }

    public List<VersionView> versions(String key) {
        byKey(key);
        return reader.query("""
                MATCH (:SourceArtifact {pke_id: $id})-[:HAS_VERSION]->(v:SourceVersion)
                RETURN properties(v) AS v ORDER BY v.recordedSeq, v.version""", Map.of("id", PkeIds.sourceArtifactId(key)))
                .stream().map(r -> {
                    Map<String, Object> v = new TreeMap<>(map(r.get("v")));
                    return new VersionView((String) v.remove("pke_id"), (String) v.get("version"), (String) v.get("kind"), v);
                }).toList();
    }

    /** Sections of a version (the current one when {@code version} is null), in document order. */
    public List<SectionView> sections(String key, String version) {
        String resolved = version != null ? version : byKey(key).currentVersion();
        if (resolved == null) {
            return List.of();
        }
        return reader.query("""
                MATCH (:SourceVersion {pke_id: $id})-[:HAS_SECTION]->(s:Section)
                RETURN properties(s) AS s ORDER BY s.order""", Map.of("id", PkeIds.sourceVersionId(key, resolved)))
                .stream().map(r -> section(map(r.get("s")), null)).toList();
    }

    /** Fulltext (Lucene) search over current sections. Raw sections are never embedded (ADR-007 §4). */
    public List<SectionView> searchSections(String query, int limit) {
        return reader.query("""
                CALL db.index.fulltext.queryNodes('pke_section_text', $query) YIELD node, score
                MATCH (a:SourceArtifact)-[:CURRENT_VERSION]->(:SourceVersion)-[:HAS_SECTION]->(node)
                RETURN properties(node) AS s, score ORDER BY score DESC LIMIT $limit""",
                Map.of("query", query, "limit", Math.clamp(limit, 1, 100)))
                .stream().map(r -> section(map(r.get("s")), ((Number) r.get("score")).doubleValue())).toList();
    }

    private static SourceView source(Map<String, Object> a) {
        Map<String, Object> props = new TreeMap<>(a);
        return new SourceView((String) props.remove("pke_id"), (String) a.get("key"), (String) a.get("kind"),
                (String) a.get("status"), (String) a.get("currentVersion"), props);
    }

    @SuppressWarnings("unchecked")
    private static SectionView section(Map<String, Object> s, Double score) {
        return new SectionView((String) s.get("pke_id"), (String) s.get("artifactKey"), (String) s.get("version"),
                ((Number) s.get("order")).longValue(), (List<String>) s.get("headingPath"), (String) s.get("heading"),
                s.get("page"), (String) s.get("text"), score);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
