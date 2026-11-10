package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import com.omnicore.pke.engine.reconcile.FulltextSimilarCardFinder;
import com.omnicore.pke.engine.reconcile.SimilarCardFinder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * M4 default {@link SimilarCardFinder}: vector search in {@code KnowledgeCard_text} (active generation, current
 * cards), then cards with the same requirement id (graph neighbours). Fulltext is only used when the vector search
 * finds nothing, e.g. while the index is still catching up or the vector store is unavailable.
 */
@Component
@Primary
public class VectorSimilarCardFinder implements SimilarCardFinder {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VectorSimilarCardFinder.class);
    static final String COLLECTION = "KnowledgeCard_text";
    static final double MIN_SCORE = 0.3;

    private final Vectors vectors;
    private final Collections collections;
    private final EmbeddingGenerations generations;
    private final CardQueryService cards;
    private final FulltextSimilarCardFinder fulltext;

    public VectorSimilarCardFinder(Vectors vectors, Collections collections, EmbeddingGenerations generations,
                                   CardQueryService cards, FulltextSimilarCardFinder fulltext) {
        this.vectors = vectors;
        this.collections = collections;
        this.generations = generations;
        this.cards = cards;
        this.fulltext = fulltext;
    }

    @Override
    public List<CardView> find(String text, List<String> keyTerms, String requirementId, List<String> excludeIds, int limit) {
        Set<String> seen = new LinkedHashSet<>(excludeIds);
        List<CardView> result = new ArrayList<>();
        String query = text + (keyTerms == null || keyTerms.isEmpty() ? "" : " " + String.join(" ", keyTerms));
        List<com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorHit> hits;
        try {
            hits = vectors.search(collections.ref(COLLECTION, generations.active()), query, limit + seen.size(), MIN_SCORE,
                    Map.of("status", "CURRENT"));
        } catch (RuntimeException e) {
            // the vector store is a projection: when it is unavailable, reconciliation falls back to fulltext
            log.warn("Vector search unavailable ({}); using fulltext for similar cards", e.getMessage());
            hits = List.of();
        }
        for (var hit : hits) {
            if (result.size() >= limit) {
                break;
            }
            if (seen.add(hit.pkeId())) {
                cards.card(hit.pkeId()).filter(c -> "CURRENT".equals(c.status())).ifPresent(result::add);
            }
        }
        if (requirementId != null && result.size() < limit) {
            for (CardView card : cards.similar(null, List.of(requirementId), List.copyOf(seen), limit - result.size())) {
                if (seen.add(card.id())) {
                    result.add(card);
                }
            }
        }
        if (hits.isEmpty() && result.size() < limit) {
            for (CardView card : fulltext.find(text, keyTerms, null, List.copyOf(seen), limit - result.size())) {
                if (seen.add(card.id())) {
                    result.add(card);
                }
            }
        }
        return result;
    }
}
