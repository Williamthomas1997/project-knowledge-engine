package com.omnicore.pke.engine.reconcile;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Similar cards by fulltext over card text (key terms and significant words of the candidate, OR-ed), plus cards
 * with the same requirement id (graph neighbours). Deterministic for a given graph state.
 */
@Component
public class FulltextSimilarCardFinder implements SimilarCardFinder {

    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "when", "that", "this", "shall",
            "must", "from", "into", "than", "then", "only", "each", "every", "after", "before", "which", "will",
            "system", "customer", "customers");

    private final CardQueryService cards;

    public FulltextSimilarCardFinder(CardQueryService cards) {
        this.cards = cards;
    }

    @Override
    public List<CardView> find(String text, List<String> keyTerms, String requirementId, List<String> excludeIds, int limit) {
        return cards.similar(luceneQuery(text, keyTerms), requirementId == null ? List.of() : List.of(requirementId),
                excludeIds, limit);
    }

    static String luceneQuery(String text, List<String> keyTerms) {
        Set<String> words = new LinkedHashSet<>();
        for (String term : keyTerms == null ? List.<String>of() : keyTerms) {
            words.addAll(tokens(term));
        }
        words.addAll(tokens(text == null ? "" : text));
        return words.stream().limit(24).collect(Collectors.joining(" OR "));
    }

    private static List<String> tokens(String text) {
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 4 || w.chars().allMatch(Character::isDigit) && w.length() >= 3)
                .filter(w -> !STOP_WORDS.contains(w))
                .toList();
    }
}
