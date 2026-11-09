package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import com.omnicore.pke.engine.pipeline.skill.SkillTool;
import com.omnicore.pke.engine.reconcile.SimilarCardFinder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code find-similar-cards}: existing current cards similar to a text (see {@link SimilarCardFinder}). */
@Component
class FindSimilarCardsTool implements SkillTool {

    private final SimilarCardFinder finder;

    FindSimilarCardsTool(SimilarCardFinder finder) {
        this.finder = finder;
    }

    @Override
    public String name() {
        return "find-similar-cards";
    }

    @Override
    public String description() {
        return "Existing current knowledge cards similar to a text (fulltext and graph neighbours)";
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) {
        Object limit = arguments.get("limit");
        List<CardView> cards = finder.find(Steps.string(arguments, "text"), Steps.strings(arguments.get("keyTerms")),
                Steps.string(arguments, "requirementId"), Steps.strings(arguments.get("excludeIds")),
                limit == null ? 5 : ((Number) limit).intValue());
        return Map.of("cards", cards.stream().map(FindSimilarCardsTool::toMap).toList());
    }

    static Map<String, Object> toMap(CardView card) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", card.id());
        map.put("text", card.text());
        map.put("kind", card.kind());
        map.put("keyTerms", card.keyTerms());
        map.put("requirementId", card.requirementId());
        map.put("documentKey", card.documentKey());
        map.put("epistemic", card.epistemic());
        return map;
    }
}
