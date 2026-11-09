package com.omnicore.pke.engine.reconcile;

import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import java.util.List;

/**
 * Finds existing cards similar to a candidate, for reconciliation. M3: fulltext plus graph neighbours
 * ({@link FulltextSimilarCardFinder}); M4 switches the default to vector search in the {@code KnowledgeCard_text}
 * collection (ADR-007 §4.3).
 */
public interface SimilarCardFinder {

    List<CardView> find(String text, List<String> keyTerms, String requirementId, List<String> excludeIds, int limit);
}
