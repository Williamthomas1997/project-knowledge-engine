package com.omnicore.pke.engine.reconcile;

import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Card identity, card writes and evidence (de)serialisation shared by the pipeline steps and question answers. */
public final class KnowledgeCards {

    public static final String TYPE = "KnowledgeCard";
    public static final List<String> LINKABLE_KINDS = List.of("requirement", "rule");

    private KnowledgeCards() {
    }

    /** A card as proposed by the {@code brd-to-knowledge-cards} skill. */
    public record Candidate(String text, String kind, List<String> keyTerms, String requirementId) {

        public Map<String, Object> properties(String documentKey) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("text", text);
            props.put("kind", kind);
            props.put("documentKey", documentKey);
            if (keyTerms != null && !keyTerms.isEmpty()) {
                props.put("keyTerms", keyTerms);
            }
            if (requirementId != null && !requirementId.isBlank()) {
                props.put("requirementId", requirementId);
            }
            return props;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", text);
            map.put("kind", kind);
            map.put("keyTerms", keyTerms == null ? List.of() : keyTerms);
            map.put("requirementId", requirementId);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static Candidate fromMap(Map<String, Object> map) {
            Object terms = map.get("keyTerms");
            Object requirementId = map.get("requirementId");
            return new Candidate((String) map.get("text"), (String) map.get("kind"),
                    terms == null ? List.of() : List.copyOf((List<String>) terms),
                    requirementId == null || String.valueOf(requirementId).isBlank() ? null : String.valueOf(requirementId));
        }
    }

    /** New cards get a deterministic id from their document and normalised text. */
    public static String cardId(String documentKey, String text) {
        String normalised = normalise(text);
        return PkeIds.uuidV5(PkeIds.ENTITY_NAMESPACE, TYPE + "|" + documentKey + "|" + normalised).toString();
    }

    /**
     * The id for a new card: {@link #cardId}, unless that id already belongs to a card whose current text differs
     * (a card keeps its first id when its text is updated); then the id is salted so the new card cannot overwrite it.
     */
    public static String newCardId(String documentKey, String text, String salt,
                                   java.util.function.Function<String, java.util.Optional<String>> currentText) {
        String id = cardId(documentKey, text);
        java.util.Optional<String> existing = currentText.apply(id);
        if (existing.isPresent() && !normalise(existing.get()).equals(normalise(text))) {
            return cardId(documentKey, text + "|" + salt);
        }
        return id;
    }

    private static String normalise(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static EntityWrite write(String id, Map<String, Object> properties, EpistemicStatus epistemic, String validAt,
                                    List<EvidenceInput> evidence) {
        return new EntityWrite(TYPE, id, properties, epistemic, validAt, evidence);
    }

    /** Evidence of a human answer to an open question. */
    public static EvidenceInput humanAnswer(String questionId, String answer, String comment) {
        String excerpt = comment == null || comment.isBlank() ? answer : answer + ": " + comment;
        return new EvidenceInput("question:" + questionId, null, null, null, "human-answer", null, null, null, null,
                excerpt.length() > 2000 ? excerpt.substring(0, 2000) : excerpt, "HUMAN_VERIFIED", null,
                EpistemicStatus.HUMAN_VERIFIED);
    }

    public static Map<String, Object> toMap(EvidenceInput e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceArtifactId", e.sourceArtifactId());
        map.put("sourceVersion", e.sourceVersion());
        map.put("location", e.location());
        map.put("contentHash", e.contentHash());
        map.put("extractionMethod", e.extractionMethod());
        map.put("pipelineVersion", e.pipelineVersion());
        map.put("skillVersion", e.skillVersion());
        map.put("modelProvider", e.modelProvider());
        map.put("modelId", e.modelId());
        map.put("excerpt", e.excerpt());
        map.put("verification", e.verification());
        map.put("confidence", e.confidence());
        map.put("epistemic", e.epistemic() == null ? null : e.epistemic().name());
        map.put("scope", e.scope());
        map.put("promptHash", e.promptHash());
        return map;
    }

    public static EvidenceInput fromMap(Map<String, Object> m) {
        Object confidence = m.get("confidence");
        Object epistemic = m.get("epistemic");
        return new EvidenceInput((String) m.get("sourceArtifactId"), (String) m.get("sourceVersion"),
                (String) m.get("location"), (String) m.get("contentHash"), (String) m.get("extractionMethod"),
                (String) m.get("pipelineVersion"), (String) m.get("skillVersion"), (String) m.get("modelProvider"),
                (String) m.get("modelId"), (String) m.get("excerpt"), (String) m.get("verification"),
                confidence == null ? null : ((Number) confidence).doubleValue(),
                epistemic == null ? null : EpistemicStatus.valueOf((String) epistemic), (String) m.get("scope"),
                (String) m.get("promptHash"));
    }

    @SuppressWarnings("unchecked")
    public static List<EvidenceInput> fromMaps(Object list) {
        List<EvidenceInput> evidence = new ArrayList<>();
        if (list instanceof List<?> items) {
            items.forEach(i -> evidence.add(fromMap((Map<String, Object>) i)));
        }
        return evidence;
    }
}
