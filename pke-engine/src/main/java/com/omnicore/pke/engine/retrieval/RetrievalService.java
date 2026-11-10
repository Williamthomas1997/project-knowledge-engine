package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.ai.EmbeddingPort;
import com.omnicore.pke.engine.graph.RetrievalQueries;
import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.index.Collections;
import com.omnicore.pke.engine.index.EmbeddingGenerations;
import com.omnicore.pke.engine.index.Vectors;
import com.omnicore.pke.engine.schema.EntityTypeDefinition;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval (ADR-007 §7 steps 1, 2 and 5):
 * <ol>
 *   <li><b>Route</b>: search {@code CollectionCatalog} (vector) and match collection types named in the query (keyword)
 *       to choose content collections.</li>
 *   <li><b>Search</b>: vector search in the chosen collections (current records), Lucene fulltext over entities and
 *       over current document sections.</li>
 *   <li><b>Fuse</b>: reciprocal-rank fusion, dedup by id, filters (type, valid-now or as-of seq), optional MMR and an
 *       optional reranker.</li>
 * </ol>
 * Graph expansion from the hits is done by {@link ContextService} with query templates.
 */
@Service
public class RetrievalService {

    public record SearchRequest(String query, Integer k, List<String> types, List<String> collections, Long asOfSeq,
                                Boolean includeInvalid, Boolean mmr, String generation) {

        public static SearchRequest of(String query, int k) {
            return new SearchRequest(query, k, null, null, null, null, null, null);
        }
    }

    /**
     * @param kind    {@code ENTITY} or {@code SECTION}
     * @param sources the ranked lists the hit came from (e.g. {@code vector:KnowledgeCard_text}, {@code fulltext:entity})
     */
    public record Hit(String id, String kind, String type, String label, String text, double score, List<String> sources,
                      Map<String, Object> details) {
    }

    public record SearchResult(String query, String generation, List<String> collections, List<Hit> hits, long millis,
                               List<String> warnings) {
    }

    private final Vectors vectors;
    private final Collections collections;
    private final EmbeddingGenerations generations;
    private final RetrievalQueries queries;
    private final com.omnicore.pke.engine.graph.VectorCatalogStore catalog;
    private final SourceQueryService sources;
    private final SchemaRegistry schema;
    private final EmbeddingPort embeddings;
    private final Reranker reranker;
    private final double minScore;

    public RetrievalService(Vectors vectors, Collections collections, EmbeddingGenerations generations,
                            RetrievalQueries queries, SourceQueryService sources, SchemaRegistry schema, EmbeddingPort embeddings,
                            ObjectProvider<Reranker> reranker, com.omnicore.pke.engine.graph.VectorCatalogStore catalog,
                            @org.springframework.beans.factory.annotation.Value("${pke.retrieval.vector-min-score:0.3}") double minScore) {
        this.catalog = catalog;
        this.minScore = minScore;
        this.vectors = vectors;
        this.collections = collections;
        this.generations = generations;
        this.queries = queries;
        this.sources = sources;
        this.schema = schema;
        this.embeddings = embeddings;
        this.reranker = reranker.getIfAvailable(() -> Reranker.NONE);
    }

    public SearchResult search(SearchRequest request) {
        long started = System.nanoTime();
        String query = request.query() == null ? "" : request.query().strip();
        int k = request.k() == null ? 10 : Math.clamp(request.k(), 1, 100);
        boolean includeInvalid = Boolean.TRUE.equals(request.includeInvalid()) || request.asOfSeq() != null;
        Generation generation = request.generation() == null ? generations.active() : generations.get(request.generation());
        List<String> chosen = route(query, request.collections(), generation);

        List<List<String>> rankings = new ArrayList<>();
        Map<String, Set<String>> hitSources = new HashMap<>();
        Map<String, String> sectionText = new HashMap<>();
        Map<String, Object> filter = includeInvalid ? Map.of() : Map.of("status", "CURRENT");
        List<String> warnings = new ArrayList<>();
        for (String collection : chosen) {
            List<String> ranking = new ArrayList<>();
            try {
                for (VectorHit hit : vectors.search(collections.ref(collection, generation), query, k * 2, minScore, filter)) {
                    ranking.add(hit.pkeId());
                    hitSources.computeIfAbsent(hit.pkeId(), id -> new LinkedHashSet<>()).add("vector:" + collection);
                }
            } catch (RuntimeException e) {
                warnings.add("vector search in " + collection + " unavailable: " + e.getMessage());
            }
            rankings.add(ranking);
        }
        String lucene = Lucene.orQuery(query);
        // keyword search ranks each entity type separately, like the per-collection vector search: a method that is
        // the best keyword match among methods is not pushed down by many matching cards
        Map<String, List<String>> keywordRankings = new LinkedHashMap<>();
        queries.entityFulltext(lucene, k * 4, includeInvalid).forEach(r -> {
            keywordRankings.computeIfAbsent((String) r.get("type"), type -> new ArrayList<>()).add((String) r.get("id"));
            hitSources.computeIfAbsent((String) r.get("id"), id -> new LinkedHashSet<>()).add("fulltext:" + r.get("type"));
        });
        rankings.addAll(keywordRankings.values());
        List<String> sectionRanking = new ArrayList<>();
        if (!lucene.isBlank()) {
            sources.searchSections(lucene, k).forEach(s -> {
                sectionRanking.add(s.id());
                sectionText.put(s.id(), s.text());
                hitSources.computeIfAbsent(s.id(), id -> new LinkedHashSet<>()).add("fulltext:section");
            });
        }
        rankings.add(sectionRanking);

        Map<String, Double> fused = Fusion.rrf(rankings, Fusion.RRF_K);
        List<String> entityIds = fused.keySet().stream().filter(id -> !sectionText.containsKey(id)).toList();
        Map<String, Map<String, Object>> entities = new HashMap<>();
        queries.entities(entityIds).forEach(e -> entities.put((String) e.get("id"), e));
        Set<String> validAsOf = request.asOfSeq() == null ? null : Set.copyOf(queries.validAsOf(entityIds, request.asOfSeq()));
        Map<String, Map<String, Object>> sections = new HashMap<>();
        queries.sections(List.copyOf(sectionText.keySet())).forEach(s -> sections.put((String) s.get("id"), s));

        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            String id = e.getKey();
            List<String> from = List.copyOf(hitSources.getOrDefault(id, Set.of()));
            if (sections.containsKey(id)) {
                if (request.types() == null || request.types().isEmpty() || request.types().contains("Section")) {
                    Map<String, Object> s = sections.get(id);
                    hits.add(new Hit(id, "SECTION", "Section", s.get("artifactKey") + " — " + s.get("heading"),
                            (String) s.get("text"), e.getValue(), from, s));
                }
                continue;
            }
            Map<String, Object> entity = entities.get(id);
            if (entity == null) {
                continue; // a vector record whose entity is gone: the reconciliation job removes it
            }
            String type = (String) entity.get("type");
            if (request.types() != null && !request.types().isEmpty() && !request.types().contains(type)) {
                continue;
            }
            if (validAsOf != null ? !validAsOf.contains(id) : !includeInvalid && !"CURRENT".equals(entity.get("status"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) entity.get("props");
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", entity.get("status"));
            details.put("epistemic", entity.get("epistemic"));
            hits.add(new Hit(id, "ENTITY", type, label(type, props), text(type, props), e.getValue(), from, details));
        }
        if (Boolean.TRUE.equals(request.mmr()) && hits.size() > 1) {
            List<float[]> vecs = embeddings.embed(hits.stream().map(h -> h.label() + " " + h.text()).toList());
            Map<Hit, float[]> byHit = new HashMap<>();
            for (int i = 0; i < hits.size(); i++) {
                byHit.put(hits.get(i), vecs.get(i));
            }
            hits = Fusion.mmr(hits, byHit::get, embeddings.embed(List.of(query)).getFirst(), 0.5, hits.size());
        }
        hits = reranker.rerank(query, diversify(hits, k));
        return new SearchResult(query, generation.id(), chosen, hits.subList(0, Math.min(k, hits.size())),
                (System.nanoTime() - started) / 1_000_000, List.copyOf(warnings));
    }

    /**
     * Type diversity in the top k: no type takes more than half of the first k places while other types are waiting
     * (cards appear in two lists, code identifiers in one, so plain RRF would fill the top with cards).
     */
    static List<Hit> diversify(List<Hit> hits, int k) {
        int cap = Math.max(2, k / 2);
        Map<String, Integer> perType = new HashMap<>();
        List<Hit> head = new ArrayList<>();
        List<Hit> overflow = new ArrayList<>();
        for (Hit hit : hits) {
            if (head.size() < k && perType.merge(hit.type(), 1, Integer::sum) <= cap) {
                head.add(hit);
            } else {
                overflow.add(hit);
            }
        }
        List<Hit> result = new ArrayList<>(head);
        result.addAll(overflow);
        return result;
    }

    /** Content collections to search: explicit, else catalog hits plus collections whose type the query names. */
    List<String> route(String query, List<String> requested, Generation generation) {
        // only collections that hold records are worth routing to
        List<String> names = collections.content().stream()
                .filter(c -> catalog.countIndexable(c.type(), c.field()) > 0)
                .map(Collections.ContentCollection::name).toList();
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().filter(names::contains).toList();
        }
        Set<String> chosen = new LinkedHashSet<>();
        String q = query.toLowerCase(Locale.ROOT);
        for (Collections.ContentCollection c : collections.content()) {
            if (!names.contains(c.name())) {
                continue;
            }
            for (String word : c.type().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split(" ")) {
                if (word.length() > 3 && q.contains(word)) {
                    chosen.add(c.name());
                }
            }
        }
        try {
            for (VectorHit hit : vectors.search(collections.ref(Collections.CATALOG, generation), query, 3, 0.0, Map.of())) {
                Object target = hit.metadata().get("target");
                if (target != null && names.contains(String.valueOf(target))) {
                    chosen.add(String.valueOf(target));
                }
            }
        } catch (RuntimeException e) {
            // routing degrades to keyword matching when the vector store is unavailable
        }
        if (chosen.isEmpty()) {
            chosen.addAll(names);
        }
        return List.copyOf(chosen);
    }

    /** Human-readable label of an entity: the type's display property, with readable forms for code and APIs. */
    public String label(String type, Map<String, Object> props) {
        return switch (type) {
            case "JavaMethod" -> simple(props.get("fqcn")) + "#" + props.get("signature");
            case "JavaClass" -> String.valueOf(props.get("fqcn"));
            case "ApiOperation" -> props.get("httpMethod") + " " + props.get("pathTemplate") + " (" + props.get("layer") + ")";
            case "TestCase" -> simple(props.get("fqcn")) + "#" + props.get("methodName");
            case "LogStatement" -> props.get("level") + " " + props.get("template");
            default -> {
                String display = schema.entityType(type).map(EntityTypeDefinition::displayProperty).orElse(null);
                Object value = display == null ? null : props.get(display);
                yield value == null ? type : String.valueOf(value);
            }
        };
    }

    private static String text(String type, Map<String, Object> props) {
        Object text = props.get("text");
        if (text == null) {
            text = props.get("summary");
        }
        return text == null ? null : String.valueOf(text);
    }

    private static String simple(Object fqcn) {
        String s = String.valueOf(fqcn);
        return s.substring(s.lastIndexOf('.') + 1);
    }
}
