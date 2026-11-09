package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.graph.CardQueryService.CardView;
import com.omnicore.pke.engine.graph.FingerprintStore;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityInvalidation;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ItemOutcome;
import com.omnicore.pke.engine.graph.KnowledgeWriter;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.pipeline.skill.SkillResult.Provenance;
import com.omnicore.pke.engine.reconcile.KnowledgeCards;
import com.omnicore.pke.engine.reconcile.KnowledgeCards.Candidate;
import com.omnicore.pke.engine.reconcile.QuestionService;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * {@code cards-apply}: writes the reconcile decisions through the single writer, one change set per section.
 * <ul>
 *   <li>ADD: a new card ({@code INFERRED_AI}) supported by the section; UPDATE: a new version of the target card;
 *       NOOP: the target gains evidence from this version; INVALIDATE: the target stops being current
 *       ({@code invalidAt} = this version's time), with history.</li>
 *   <li>CONFLICT: an {@code OpenQuestion}; nothing is written to knowledge until it is answered.</li>
 *   <li>Cards whose source section no longer exists in this version are invalidated (deterministic).</li>
 *   <li>After a section is applied its fingerprint is recorded, so the same section is never sent to a model again.</li>
 * </ul>
 */
@Component
class CardsApplyStep implements PipelineStep {

    private final KnowledgeWriter writer;
    private final QuestionService questions;
    private final FingerprintStore fingerprints;
    private final CardQueryService cards;

    CardsApplyStep(KnowledgeWriter writer, QuestionService questions, FingerprintStore fingerprints, CardQueryService cards) {
        this.writer = writer;
        this.questions = questions;
        this.fingerprints = fingerprints;
        this.cards = cards;
    }

    @Override
    public String name() {
        return "cards-apply";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        Map<String, Object> reconciled = Steps.outputWith(context, "currentPaths", "reconcile");
        String documentKey = Steps.string(reconciled, "documentKey");
        String version = Steps.string(reconciled, "version");
        String validAt = Steps.string(reconciled, "validAt");
        String pipeline = context.pipelineId() + "@" + context.pipelineVersion();
        Map<String, Integer> decisions = new TreeMap<>();
        Map<String, Integer> outcomes = new TreeMap<>();
        List<String> linkCards = new ArrayList<>();
        List<String> questionIds = new ArrayList<>();
        for (Map<String, Object> section : Steps.maps(reconciled.get("sections"))) {
            context.checkCancelled();
            String sectionId = Steps.string(section, "sectionId");
            Provenance cardProvenance = Provenance.fromMap(Steps.map(section.get("cardProvenance")));
            Map<String, Object> reconcileProvenance = Steps.map(section.get("reconcileProvenance"));
            EvidenceInput cardEvidence = cardProvenance.evidence(documentKey, version, sectionId,
                    Steps.string(section, "hash"), pipeline, Steps.string(section, "excerpt"), null, null);
            Map<String, EntityWrite> writes = new LinkedHashMap<>();
            List<EntityInvalidation> invalidations = new ArrayList<>();
            for (Map<String, Object> d : Steps.maps(section.get("decisions"))) {
                String decision = Steps.string(d, "decision");
                decisions.merge(decision, 1, Integer::sum);
                Candidate candidate = d.get("candidate") == null ? null : Candidate.fromMap(Steps.map(d.get("candidate")));
                Map<String, Object> target = Steps.map(d.get("target"));
                String reason = Steps.string(d, "reason");
                List<EvidenceInput> decisionEvidence = new ArrayList<>(List.of(cardEvidence));
                if (!reconcileProvenance.isEmpty()) {
                    decisionEvidence.add(Provenance.fromMap(reconcileProvenance).evidence(documentKey, version, sectionId,
                            Steps.string(section, "hash"), pipeline, Steps.truncate(reason, 2000), null, null));
                }
                switch (decision) {
                    case "ADD" -> {
                        String id = KnowledgeCards.newCardId(documentKey, candidate.text(), sectionId + "|" + version,
                                cardId -> cards.card(cardId).map(CardView::text));
                        merge(writes, KnowledgeCards.write(id, candidate.properties(documentKey), EpistemicStatus.INFERRED_AI,
                                validAt, List.of(cardEvidence)));
                        linkable(linkCards, id, candidate.kind());
                    }
                    case "UPDATE" -> {
                        String id = Steps.string(target, "id");
                        writes.put(id, KnowledgeCards.write(id, candidate.properties(Steps.string(target, "documentKey")),
                                EpistemicStatus.INFERRED_AI, validAt, decisionEvidence));
                        linkable(linkCards, id, candidate.kind());
                    }
                    case "NOOP" -> {
                        if (!target.isEmpty()) {
                            String id = Steps.string(target, "id");
                            CardView current = cards.card(id).orElse(null);
                            if (current != null && "CURRENT".equals(current.status())) {
                                merge(writes, KnowledgeCards.write(id, current.properties(),
                                        EpistemicStatus.valueOf(current.epistemic()), null, List.of(cardEvidence)));
                            }
                        }
                    }
                    case "INVALIDATE" -> invalidations.add(new EntityInvalidation(Steps.string(target, "id"),
                            "superseded by " + documentKey + " version " + version + ": " + reason, validAt,
                            decisionEvidence.subList(decisionEvidence.size() - 1, decisionEvidence.size())));
                    case "CONFLICT" -> questionIds.add(questions.cardConflict(documentKey, version, sectionId, candidate,
                            target, cardEvidence, Steps.string(d, "question"), reason, validAt, context.executionId(),
                            context.actor()));
                    default -> throw new IllegalStateException(decision);
                }
            }
            invalidations.removeIf(i -> writes.containsKey(i.entityId()));
            if (!writes.isEmpty() || !invalidations.isEmpty()) {
                ChangeResult result = writer.apply(new ChangeSet(context.executionId(), List.copyOf(writes.values()),
                        List.of(), List.of(), List.of(), false, invalidations), context.actor());
                count(outcomes, result);
            }
            fingerprints.record(BrdSectionsStep.FINGERPRINT_KIND, Steps.string(section, "fingerprint"), sectionId,
                    context.executionId());
        }
        int removed = invalidateRemovedSections(context, reconciled, documentKey, version, validAt, pipeline, outcomes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentKey", documentKey);
        out.put("version", version);
        out.put("decisions", decisions);
        out.put("outcomes", outcomes);
        out.put("questions", questionIds);
        out.put("removedSectionCards", removed);
        out.put("linkCards", linkCards);
        return out;
    }

    @SuppressWarnings("unchecked")
    private int invalidateRemovedSections(StepContext context, Map<String, Object> reconciled, String documentKey,
                                          String version, String validAt, String pipeline, Map<String, Integer> outcomes) {
        List<List<String>> currentPaths = (List<List<String>>) reconciled.get("currentPaths");
        List<CardView> orphaned = cards.cardsOutsidePaths(documentKey, currentPaths == null ? List.of() : currentPaths);
        if (orphaned.isEmpty()) {
            return 0;
        }
        EvidenceInput evidence = new EvidenceInput(documentKey, version, null, null, "pipeline:section-removed", pipeline,
                null, null, null, "The section this card came from is not in version " + version, null, null,
                EpistemicStatus.EXTRACTED_DETERMINISTIC);
        List<EntityInvalidation> invalidations = orphaned.stream().map(c -> new EntityInvalidation(c.id(),
                "its source section was removed from " + documentKey + " in version " + version, validAt,
                List.of(evidence))).toList();
        count(outcomes, writer.apply(new ChangeSet(context.executionId(), List.of(), List.of(), List.of(), List.of(), false,
                invalidations), context.actor()));
        return orphaned.size();
    }

    private static void merge(Map<String, EntityWrite> writes, EntityWrite write) {
        EntityWrite existing = writes.get(write.id());
        if (existing == null) {
            writes.put(write.id(), write);
            return;
        }
        List<EvidenceInput> evidence = new ArrayList<>(existing.evidence());
        write.evidence().stream().filter(e -> !evidence.contains(e)).forEach(evidence::add);
        writes.put(write.id(), new EntityWrite(existing.type(), existing.id(), existing.properties(), existing.epistemic(),
                existing.validAt(), evidence));
    }

    private static void linkable(List<String> linkCards, String id, String kind) {
        if (KnowledgeCards.LINKABLE_KINDS.contains(kind) && !linkCards.contains(id)) {
            linkCards.add(id);
        }
    }

    private static void count(Map<String, Integer> outcomes, ChangeResult result) {
        for (ItemOutcome item : result.items()) {
            outcomes.merge(item.outcome().name(), 1, Integer::sum);
        }
    }
}
