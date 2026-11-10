package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import com.omnicore.pke.engine.graph.FingerprintStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.ScopeRetraction;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.pipeline.LocalPipelineExecutor;
import com.omnicore.pke.engine.pipeline.skill.SkillRegistry;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillResult.Provenance;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.engine.reconcile.KnowledgeCards;
import com.omnicore.pke.engine.reconcile.QuestionService;
import com.omnicore.pke.engine.reconcile.RelationshipPolicies;
import com.omnicore.pke.engine.reconcile.RelationshipPolicies.Decision;
import com.omnicore.pke.engine.reconcile.RelationshipPolicies.Verdict;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code requirements-link}: links requirement and rule cards to the code that implements them
 * ({@code IMPLEMENTED_BY}) with the {@code link-requirement-to-implementation} skill.
 * <ul>
 *   <li>{@code with: {cards: changed}} links the cards the previous {@code cards-apply} step added or updated;
 *       {@code cards: all} links every current requirement and rule card (used after code changes).</li>
 *   <li>Candidates come from the declared {@code search-implementation} tool. The fingerprint (card content,
 *       candidates, skill version) skips cards whose inputs did not change: no model call, no write.</li>
 *   <li>Every proposed link passes the relationship policy: IMPLEMENTED_BY from AI is ACCEPT_AS_INFERRED with
 *       evidence (skill, model, prompt hash, rationale, confidence); QUESTION creates an open question; REJECT
 *       drops it. Links of a card that are not proposed again are invalidated (scope retraction).</li>
 * </ul>
 */
@Component
class RequirementsLinkStep implements PipelineStep {

    static final String FINGERPRINT_KIND = "requirement-link";
    static final String RELATIONSHIP = "IMPLEMENTED_BY";

    private final CardQueryService cards;
    private final SkillRunner skills;
    private final SkillRegistry registry;
    private final FingerprintStore fingerprints;
    private final GraphStore writer;
    private final RelationshipPolicies policies;
    private final QuestionService questions;

    RequirementsLinkStep(CardQueryService cards, SkillRunner skills, SkillRegistry registry, FingerprintStore fingerprints,
                         GraphStore writer, RelationshipPolicies policies, QuestionService questions) {
        this.cards = cards;
        this.skills = skills;
        this.registry = registry;
        this.fingerprints = fingerprints;
        this.writer = writer;
        this.policies = policies;
        this.questions = questions;
    }

    @Override
    public String name() {
        return "requirements-link";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        String pipeline = context.pipelineId() + "@" + context.pipelineVersion();
        String skillRef = registry.active(Steps.LINK_SKILL).ref();
        List<CardView> targets = selectCards(context);
        int linked = 0;
        int unchanged = 0;
        int noCandidates = 0;
        List<Map<String, Object>> rejected = new ArrayList<>();
        List<String> reviews = new ArrayList<>();
        for (CardView card : targets) {
            context.checkCancelled();
            Map<String, Object> search = skills.tool(Steps.LINK_SKILL, "search-implementation",
                    Map.of("text", card.text(), "keyTerms", card.keyTerms(), "limit", 12));
            List<Map<String, Object>> candidates = Steps.maps(search.get("candidates"));
            String fingerprint = CanonicalJson.sha256(Map.of("card", card.id(), "content", card.properties(),
                    "candidates", candidates.stream().map(c -> c.get("id")).toList(), "skill", skillRef,
                    "policies", policies.version()));
            if (fingerprints.contains(FINGERPRINT_KIND, fingerprint)) {
                unchanged++;
                continue;
            }
            String scope = "card:" + card.id();
            ScopeRetraction retraction = new ScopeRetraction(card.documentKey(), scope, "skill:" + Steps.LINK_SKILL,
                    "no longer proposed by " + skillRef);
            List<RelationshipWrite> writes = new ArrayList<>();
            if (candidates.isEmpty()) {
                noCandidates++;
            } else {
                if (!skills.modelAvailable()) {
                    return Map.of("_status", LocalPipelineExecutor.SKIPPED_NO_MODEL);
                }
                Map<String, Object> input = skillInput(card, candidates);
                Map<String, Object> run = context.memo("link:" + card.id() + ":" + fingerprint, () -> {
                    SkillResult r = skills.run(Steps.LINK_SKILL, input, context.executionId(), context.stepId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("output", r.output());
                    m.put("provenance", r.provenance().toMap());
                    return m;
                });
                Provenance provenance = Provenance.fromMap(Steps.map(run.get("provenance")));
                Set<String> seen = new HashSet<>();
                for (Map<String, Object> link : Steps.maps(Steps.map(run.get("output")).get("links"))) {
                    String ref = Steps.string(link, "candidate");
                    int index = refIndex(ref, candidates.size());
                    if (index < 0) {
                        throw new StepFailure(ErrorClass.PERMANENT, Steps.LINK_SKILL + " named '" + ref
                                + "', which is not one of the given candidates");
                    }
                    Map<String, Object> target = candidates.get(index);
                    if (!seen.add((String) target.get("id"))) {
                        continue;
                    }
                    Double confidence = link.get("confidence") == null ? null : ((Number) link.get("confidence")).doubleValue();
                    String rationale = Steps.truncate(Steps.string(link, "rationale"), 2000);
                    EvidenceInput evidence = provenance.evidence(card.documentKey(), null, scope, null, pipeline, rationale,
                            confidence, scope);
                    RelationshipWrite write = new RelationshipWrite(RELATIONSHIP, EntityRef.id(card.id()),
                            EntityRef.id((String) target.get("id")), Map.of("rationale", rationale == null ? "" : rationale),
                            EpistemicStatus.INFERRED_AI, null, List.of(evidence));
                    Verdict verdict = policies.decide(RELATIONSHIP, RelationshipPolicies.Origin.AI, 1, confidence);
                    if (verdict.decision() == Decision.AUTO_ACCEPT || verdict.decision() == Decision.ACCEPT_AS_INFERRED) {
                        writes.add(write);
                    } else if (verdict.decision() == Decision.QUESTION) {
                        reviews.add(questions.linkReview(write, card.id(), (String) target.get("id"), card.documentKey(),
                                "Is \"" + card.text() + "\" implemented by " + target.get("label") + "? " + rationale,
                                context.executionId(), context.actor()));
                    } else {
                        rejected.add(Map.of("card", card.id(), "target", target.get("label"), "reason", verdict.reason()));
                    }
                }
            }
            ChangeResult result = writer.apply(new ChangeSet(context.executionId(), List.of(), writes, List.of(),
                    List.of(retraction), true), context.actor());
            linked += (int) result.items().stream().filter(i -> "RELATIONSHIP".equals(i.kind())
                    && i.outcome() != com.omnicore.pke.engine.store.KnowledgeChanges.Outcome.INVALIDATED).count();
            fingerprints.record(FINGERPRINT_KIND, fingerprint, card.id(), context.executionId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", targets.size());
        out.put("links", linked);
        out.put("unchanged", unchanged);
        out.put("noCandidates", noCandidates);
        out.put("rejected", rejected);
        out.put("reviews", reviews);
        return out;
    }

    private List<CardView> selectCards(StepContext context) {
        String mode = String.valueOf(context.parameters().getOrDefault("cards", "changed"));
        if ("all".equals(mode)) {
            return cards.currentCards(KnowledgeCards.LINKABLE_KINDS);
        }
        Map<String, Object> applied = Steps.outputWith(context, "linkCards", "apply");
        List<CardView> selected = new ArrayList<>();
        for (String id : Steps.strings(applied.get("linkCards"))) {
            cards.card(id).filter(c -> "CURRENT".equals(c.status()) && KnowledgeCards.LINKABLE_KINDS.contains(c.kind()))
                    .ifPresent(selected::add);
        }
        return selected;
    }

    private static Map<String, Object> skillInput(CardView card, List<Map<String, Object>> candidates) {
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("text", card.text());
        requirement.put("kind", card.kind());
        if (card.requirementId() != null) {
            requirement.put("requirementId", card.requirementId());
        }
        requirement.put("keyTerms", card.keyTerms());
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            list.add(Map.of("ref", "I" + (i + 1), "type", c.get("type"), "label", c.get("label"), "detail", c.get("detail")));
        }
        return Map.of("requirement", requirement, "candidates", list);
    }

    private static int refIndex(String ref, int size) {
        if (ref == null || !ref.startsWith("I")) {
            return -1;
        }
        try {
            int index = Integer.parseInt(ref.substring(1)) - 1;
            return index >= 0 && index < size ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
