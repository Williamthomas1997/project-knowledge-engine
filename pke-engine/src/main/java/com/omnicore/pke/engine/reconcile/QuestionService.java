package com.omnicore.pke.engine.reconcile;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.graph.QuestionStore;
import com.omnicore.pke.engine.graph.QuestionStore.QuestionView;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates and answers open questions. A conflict is never resolved by the engine: the question lists fixed
 * options, and the chosen option is applied through the single writer with {@code HUMAN_VERIFIED} evidence
 * (ADR-007 §4.4).
 * <ul>
 *   <li>{@code CARD_CONFLICT}: {@code keep-existing}, {@code use-new}, {@code keep-both}.</li>
 *   <li>{@code LINK_REVIEW} (a relationship whose policy is QUESTION): {@code accept}, {@code reject}.</li>
 * </ul>
 */
@Service
public class QuestionService {

    public static final String CARD_CONFLICT = "CARD_CONFLICT";
    public static final String LINK_REVIEW = "LINK_REVIEW";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final QuestionStore store;
    private final GraphStore writer;
    private final CardQueryService cards;

    public QuestionService(QuestionStore store, GraphStore writer, CardQueryService cards) {
        this.store = store;
        this.writer = writer;
        this.cards = cards;
    }

    public record Option(String id, String label) {
    }

    public record Answer(QuestionView question, ChangeResult applied) {
    }

    /**
     * Records a conflict between a candidate card and an existing card. Nothing is written to knowledge.
     *
     * @return the question id (the same conflict always gets the same id, so re-running asks nothing new)
     */
    public String cardConflict(String documentKey, String sourceVersion, String sectionId, KnowledgeCards.Candidate candidate,
                               Map<String, Object> target, EvidenceInput candidateEvidence, String question,
                               String reason, String validAt, String executionId, String actor) {
        String id = PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, "question:" + CARD_CONFLICT + ":" + target.get("id") + ":"
                + CanonicalJson.sha256(candidate.toMap())).toString();
        List<Option> options = List.of(
                new Option("keep-existing", "Keep the existing statement: " + target.get("text")),
                new Option("use-new", "Replace it with: " + candidate.text()),
                new Option("keep-both", "Both are valid (different cases): keep both statements"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentKey", documentKey);
        payload.put("sourceVersion", sourceVersion);
        payload.put("sectionId", sectionId);
        payload.put("candidate", candidate.toMap());
        payload.put("target", target);
        payload.put("candidateEvidence", KnowledgeCards.toMap(candidateEvidence));
        payload.put("reason", reason);
        payload.put("validAt", validAt);
        store.create(new QuestionStore.NewQuestion(id, CARD_CONFLICT, question, JSON.writeValueAsString(options),
                JSON.writeValueAsString(payload), documentKey, executionId, List.of((String) target.get("id")),
                sectionId == null ? List.of() : List.of(sectionId)), actor);
        return id;
    }

    /** Records a proposed relationship that its policy sends to review. Nothing is written to knowledge. */
    public String linkReview(RelationshipWrite relationship, String subjectId, String objectId, String documentKey,
                             String question, String executionId, String actor) {
        String id = PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, "question:" + LINK_REVIEW + ":" + relationship.type() + ":"
                + subjectId + ":" + objectId).toString();
        List<Option> options = List.of(new Option("accept", "Yes, record this relationship"),
                new Option("reject", "No, do not record it"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", relationship.type());
        payload.put("subjectId", subjectId);
        payload.put("objectId", objectId);
        payload.put("properties", relationship.properties());
        payload.put("evidence", relationship.evidence().stream().map(KnowledgeCards::toMap).toList());
        store.create(new QuestionStore.NewQuestion(id, LINK_REVIEW, question, JSON.writeValueAsString(options),
                JSON.writeValueAsString(payload), documentKey, executionId, List.of(subjectId, objectId), List.of()), actor);
        return id;
    }

    public List<QuestionView> list(String status, int limit) {
        return store.list(status, limit);
    }

    public QuestionView get(String id) {
        return store.get(id);
    }

    @SuppressWarnings("unchecked")
    public Answer answer(String id, String optionId, String comment, String actor) {
        QuestionView question = store.get(id);
        if (!"OPEN".equals(question.status())) {
            throw new ConflictException("Question " + id + " is already " + question.status());
        }
        List<Map<String, Object>> options = JSON.readValue(question.optionsJson(), List.class);
        Map<String, Object> option = options.stream().filter(o -> o.get("id").equals(optionId)).findFirst()
                .orElseThrow(() -> new ValidationFailedException("Invalid answer", List.of(new Violation("/option",
                        "must be one of " + options.stream().map(o -> o.get("id")).toList()))));
        Map<String, Object> payload = JSON.readValue(question.payloadJson(), Map.class);
        EvidenceInput human = KnowledgeCards.humanAnswer(id, (String) option.get("label"), comment);
        ChangeSet changes = switch (question.kind()) {
            case CARD_CONFLICT -> cardConflictChanges(id, optionId, payload, human);
            case LINK_REVIEW -> linkReviewChanges(id, optionId, payload, human);
            default -> throw new IllegalStateException("Unknown question kind " + question.kind());
        };
        ChangeResult result = changes == null ? null : writer.apply(changes, actor);
        store.markAnswered(id, optionId, comment, actor, result == null ? null : result.firstSeq(),
                result == null ? null : result.lastSeq());
        return new Answer(store.get(id), result);
    }

    @SuppressWarnings("unchecked")
    private ChangeSet cardConflictChanges(String questionId, String optionId, Map<String, Object> payload,
                                                 EvidenceInput human) {
        Map<String, Object> target = (Map<String, Object>) payload.get("target");
        KnowledgeCards.Candidate candidate = KnowledgeCards.Candidate.fromMap((Map<String, Object>) payload.get("candidate"));
        String documentKey = (String) payload.get("documentKey");
        EvidenceInput candidateEvidence = KnowledgeCards.fromMap((Map<String, Object>) payload.get("candidateEvidence"));
        String validAt = (String) payload.get("validAt");
        String targetId = (String) target.get("id");
        Map<String, Object> targetProps = targetProperties(target);
        List<EntityWrite> writes = new ArrayList<>();
        switch (optionId) {
            case "keep-existing" -> writes.add(KnowledgeCards.write(targetId, targetProps, EpistemicStatus.HUMAN_VERIFIED,
                    null, List.of(human)));
            case "use-new" -> writes.add(KnowledgeCards.write(targetId, candidate.properties((String) target.get("documentKey")),
                    EpistemicStatus.HUMAN_VERIFIED, validAt, List.of(candidateEvidence, human)));
            case "keep-both" -> {
                writes.add(KnowledgeCards.write(targetId, targetProps, EpistemicStatus.HUMAN_VERIFIED, null, List.of(human)));
                String newId = KnowledgeCards.newCardId(documentKey, candidate.text(), "question:" + questionId,
                        id -> cards.card(id).map(CardQueryService.CardView::text));
                writes.add(KnowledgeCards.write(newId,
                        candidate.properties(documentKey), EpistemicStatus.HUMAN_VERIFIED, validAt,
                        List.of(candidateEvidence, human)));
            }
            default -> throw new IllegalStateException(optionId);
        }
        return new ChangeSet("question:" + questionId, writes, List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static ChangeSet linkReviewChanges(String questionId, String optionId, Map<String, Object> payload,
                                               EvidenceInput human) {
        if ("reject".equals(optionId)) {
            return null;
        }
        List<EvidenceInput> evidence = new ArrayList<>(KnowledgeCards.fromMaps(payload.get("evidence")));
        evidence.add(human);
        RelationshipWrite write = new RelationshipWrite((String) payload.get("type"),
                EntityRef.id((String) payload.get("subjectId")), EntityRef.id((String) payload.get("objectId")),
                (Map<String, Object>) payload.get("properties"), EpistemicStatus.HUMAN_VERIFIED, null, evidence);
        return new ChangeSet("question:" + questionId, List.of(), List.of(write), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> targetProperties(Map<String, Object> target) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", target.get("text"));
        props.put("kind", target.get("kind"));
        props.put("documentKey", target.get("documentKey"));
        List<String> keyTerms = (List<String>) target.get("keyTerms");
        if (keyTerms != null && !keyTerms.isEmpty()) {
            props.put("keyTerms", keyTerms);
        }
        if (target.get("requirementId") != null) {
            props.put("requirementId", target.get("requirementId"));
        }
        return props;
    }
}
