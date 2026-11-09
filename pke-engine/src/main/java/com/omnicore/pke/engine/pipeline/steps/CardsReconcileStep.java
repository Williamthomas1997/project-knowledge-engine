package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.pipeline.LocalPipelineExecutor;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.engine.reconcile.KnowledgeCards.Candidate;
import com.omnicore.pke.engine.reconcile.ReconcileDecisions;
import com.omnicore.pke.engine.reconcile.ReconcileDecisions.Decision;
import com.omnicore.pke.engine.reconcile.ReconcileDecisions.Resolved;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code cards-reconcile} (Mem0 pattern, ADR-013): compares each section's candidate cards with existing cards
 * (the same section in earlier versions, plus similar cards found by {@code find-similar-cards}) and decides
 * ADD | UPDATE | NOOP | INVALIDATE | CONFLICT per candidate with the {@code reconcile-knowledge} skill.
 * <ul>
 *   <li>No existing cards to compare with: every candidate is ADDed without a model call.</li>
 *   <li>The model only sees short ids; its answer is checked by {@link ReconcileDecisions}.</li>
 *   <li>Guard: an UPDATE of a human-verified card or of a card from another document becomes a CONFLICT
 *       (a question); an INVALIDATE of such a card is dropped and reported.</li>
 * </ul>
 * Nothing is written here; {@code cards-apply} writes the decisions.
 */
@Component
class CardsReconcileStep implements PipelineStep {

    static final int SIMILAR_PER_CANDIDATE = 5;
    static final int MAX_EXISTING = 12;

    private final CardQueryService cards;
    private final SkillRunner skills;

    CardsReconcileStep(CardQueryService cards, SkillRunner skills) {
        this.cards = cards;
        this.skills = skills;
    }

    @Override
    public String name() {
        return "cards-reconcile";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        Map<String, Object> sections = Steps.outputWith(context, "cardsInput", "sections");
        Map<String, Object> cardStep = Steps.outputWith(context, "results", "cards");
        String documentKey = Steps.string(sections, "documentKey");
        Map<String, Map<String, Object>> results = new HashMap<>();
        Steps.maps(cardStep.get("results")).forEach(r -> results.put(Steps.string(r, "key"), r));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> section : Steps.maps(sections.get("sections"))) {
            context.checkCancelled();
            String sectionId = Steps.string(section, "sectionId");
            Map<String, Object> result = results.get(itemKey(section));
            if (result == null) {
                throw new StepFailure(ErrorClass.PERMANENT, "no card result for section " + itemKey(section));
            }
            List<Candidate> candidates = candidates(Steps.map(result.get("output")));
            Map<String, Object> reconciled = reconcile(context, documentKey, section, candidates);
            if (reconciled == null) {
                return Map.of("_status", LocalPipelineExecutor.SKIPPED_NO_MODEL);
            }
            reconciled.put("cardProvenance", result.get("provenance"));
            out.add(reconciled);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("documentKey", documentKey);
        output.put("version", sections.get("version"));
        output.put("validAt", sections.get("validAt"));
        output.put("currentPaths", sections.get("currentPaths"));
        output.put("sections", out);
        return output;
    }

    private Map<String, Object> reconcile(StepContext context, String documentKey, Map<String, Object> section,
                                          List<Candidate> candidates) {
        String sectionId = Steps.string(section, "sectionId");
        List<String> headingPath = Steps.strings(section.get("headingPath"));
        List<CardView> sameSection = cards.cardsInScope(documentKey, headingPath);
        List<CardView> existing = new ArrayList<>(sameSection);
        Set<String> seen = new LinkedHashSet<>(sameSection.stream().map(CardView::id).toList());
        for (Candidate candidate : candidates) {
            if (existing.size() >= MAX_EXISTING) {
                break;
            }
            Map<String, Object> args = new HashMap<>();
            args.put("text", candidate.text());
            args.put("keyTerms", candidate.keyTerms());
            args.put("requirementId", candidate.requirementId());
            args.put("excludeIds", List.copyOf(seen));
            args.put("limit", SIMILAR_PER_CANDIDATE);
            for (Map<String, Object> card : Steps.maps(skills.tool(Steps.RECONCILE_SKILL, "find-similar-cards", args).get("cards"))) {
                if (existing.size() < MAX_EXISTING && seen.add(Steps.string(card, "id"))) {
                    existing.add(cards.card(Steps.string(card, "id")).orElseThrow());
                }
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>(section);
        List<Map<String, Object>> decisions = new ArrayList<>();
        if (candidates.isEmpty() && sameSection.isEmpty()) {
            entry.put("decidedBy", "rule:no-cards");
        } else if (existing.isEmpty()) {
            entry.put("decidedBy", "rule:no-existing-cards");
            candidates.forEach(c -> decisions.add(decision(Decision.ADD, c, null, "no existing card to compare with", null)));
        } else {
            // A long section split in parts: other parts may state the section's earlier cards, so this part must not
            // treat them as "its own" (no INVALIDATE based on absence; removed sections are still handled).
            boolean part = !Objects.equals(itemKey(section), Steps.string(section, "sectionId"));
            Map<String, Object> input = skillInput(documentKey, section, candidates, existing,
                    part ? List.of() : sameSection);
            Map<String, Object> run = context.memo("reconcile:" + itemKey(section) + ":" + CanonicalJson.sha256(input), () -> {
                SkillResult r = skills.run(Steps.RECONCILE_SKILL, input, context.executionId(), context.stepId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skipped", r.skipped());
                m.put("output", r.output());
                m.put("provenance", r.provenance().toMap());
                return m;
            });
            if (Boolean.TRUE.equals(run.get("skipped"))) {
                return null;
            }
            List<Resolved> resolved;
            try {
                resolved = ReconcileDecisions.resolve(candidates.size(), existing.size(),
                        Steps.maps(Steps.map(run.get("output")).get("decisions")));
            } catch (ValidationFailedException e) {
                throw new StepFailure(ErrorClass.PERMANENT, e.getMessage() + ": " + e.violations(), e);
            }
            entry.put("decidedBy", "skill:" + Steps.RECONCILE_SKILL);
            entry.put("reconcileProvenance", run.get("provenance"));
            List<String> dropped = new ArrayList<>();
            for (Resolved r : resolved) {
                Candidate candidate = r.candidate() == null ? null : candidates.get(r.candidate());
                CardView target = r.target() == null ? null : existing.get(r.target());
                Decision decision = r.decision();
                String question = r.question();
                String reason = r.reason();
                if (part && decision == Decision.INVALIDATE) {
                    dropped.add(target.id());
                    continue;
                }
                if (target != null && (decision == Decision.UPDATE || decision == Decision.INVALIDATE) && protectedCard(target, documentKey)) {
                    if (decision == Decision.INVALIDATE) {
                        dropped.add(target.id());
                        continue;
                    }
                    decision = Decision.CONFLICT;
                    reason = "policy: the existing statement is " + (isHumanVerified(target) ? "human-verified"
                            : "from " + target.documentKey()) + "; " + reason;
                    question = "\"" + documentKey + "\" now states: \"" + candidate.text() + "\". The existing statement is: \""
                            + target.text() + "\". Which one is correct?";
                }
                decisions.add(decision(decision, candidate, target, reason, question));
            }
            entry.put("droppedInvalidations", dropped);
        }
        entry.put("decisions", decisions);
        return entry;
    }

    private static Map<String, Object> skillInput(String documentKey, Map<String, Object> section, List<Candidate> candidates,
                                                  List<CardView> existing, List<CardView> sameSection) {
        List<Map<String, Object>> c = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ReconcileDecisions.candidateRef(i));
            m.putAll(candidates.get(i).toMap());
            m.values().removeIf(Objects::isNull);
            c.add(m);
        }
        Set<String> same = new LinkedHashSet<>(sameSection.stream().map(CardView::id).toList());
        List<Map<String, Object>> e = new ArrayList<>();
        for (int i = 0; i < existing.size(); i++) {
            CardView card = existing.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ReconcileDecisions.existingRef(i));
            m.put("text", card.text());
            m.put("kind", card.kind());
            if (card.requirementId() != null) {
                m.put("requirementId", card.requirementId());
            }
            m.put("documentKey", card.documentKey());
            m.put("sameSection", same.contains(card.id()));
            m.put("humanVerified", isHumanVerified(card));
            e.add(m);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("document", Map.of("key", documentKey));
        input.put("section", Map.of("headingPath", section.get("headingPath"), "heading",
                section.get("heading") == null ? "" : section.get("heading")));
        input.put("candidates", c);
        input.put("existing", e);
        return input;
    }

    private static Map<String, Object> decision(Decision decision, Candidate candidate, CardView target, String reason,
                                                String question) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("decision", decision.name());
        d.put("reason", reason);
        d.put("question", question);
        d.put("candidate", candidate == null ? null : candidate.toMap());
        d.put("target", target == null ? null : targetMap(target));
        return d;
    }

    /** The card-step item key: the section id, or {@code <sectionId>#<part>} for a long section split in parts. */
    private static String itemKey(Map<String, Object> section) {
        String key = Steps.string(section, "key");
        return key != null ? key : Steps.string(section, "sectionId");
    }

    static Map<String, Object> targetMap(CardView card) {
        Map<String, Object> m = new LinkedHashMap<>(FindSimilarCardsTool.toMap(card));
        m.put("version", card.version());
        return m;
    }

    private static boolean protectedCard(CardView card, String documentKey) {
        return isHumanVerified(card) || !documentKey.equals(card.documentKey());
    }

    private static boolean isHumanVerified(CardView card) {
        return EpistemicStatus.HUMAN_VERIFIED.name().equals(card.epistemic());
    }

    private static List<Candidate> candidates(Map<String, Object> output) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> texts = new LinkedHashSet<>();
        for (Map<String, Object> card : Steps.maps(output.get("cards"))) {
            Candidate candidate = Candidate.fromMap(card);
            if (texts.add(candidate.text().strip().toLowerCase(java.util.Locale.ROOT))) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }
}
