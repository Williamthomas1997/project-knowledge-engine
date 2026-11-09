package com.omnicore.pke.engine.pipeline.skill;

import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of one skill run: the validated output and the provenance every fact derived from it must carry.
 *
 * @param skipped true when no model is configured ({@code SKIPPED_NO_MODEL}); output is then empty
 */
public record SkillResult(boolean skipped, Map<String, Object> output, Provenance provenance) {

    public record Provenance(String skill, String skillVersion, String provider, String model, String promptHash,
                             String inputHash, boolean replayed) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("skill", skill);
            map.put("skillVersion", skillVersion);
            map.put("provider", provider);
            map.put("model", model);
            map.put("promptHash", promptHash);
            map.put("inputHash", inputHash);
            map.put("replayed", replayed);
            return map;
        }

        public static Provenance fromMap(Map<String, Object> map) {
            return new Provenance((String) map.get("skill"), (String) map.get("skillVersion"), (String) map.get("provider"),
                    (String) map.get("model"), (String) map.get("promptHash"), (String) map.get("inputHash"),
                    Boolean.TRUE.equals(map.get("replayed")));
        }

        /** {@code skill:<id>}: the extraction method recorded on evidence. */
        public String method() {
            return "skill:" + skill;
        }

        /** AI-inferred evidence for a fact produced by this skill run. */
        public EvidenceInput evidence(String sourceArtifactId, String sourceVersion, String location, String contentHash,
                                      String pipelineVersion, String excerpt, Double confidence, String scope) {
            return new EvidenceInput(sourceArtifactId, sourceVersion, location, contentHash, method(), pipelineVersion,
                    skill + "@" + skillVersion, provider, model, excerpt, "UNVERIFIED", confidence,
                    EpistemicStatus.INFERRED_AI, scope, promptHash);
        }
    }

    public static SkillResult skippedNoModel(String skill, String version) {
        return new SkillResult(true, Map.of(), new Provenance(skill, version, null, null, null, null, false));
    }
}
