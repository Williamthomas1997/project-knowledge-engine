package com.omnicore.pke.engine.ai;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import java.util.Map;

/**
 * One structured chat call made on behalf of a skill.
 *
 * @param inputHash    SHA-256 of the canonical skill input; replay fixtures are keyed by skill id and this hash
 * @param input        the skill input (stored with recorded fixtures so they can be reviewed)
 * @param outputSchema JSON Schema the answer must satisfy (canonical JSON)
 */
public record ChatRequest(String skillId, String skillVersion, String system, String user, String outputSchema,
                          String inputHash, Map<String, Object> input) {

    /** SHA-256 of exactly what the model is sent (system and user messages). Recorded on evidence. */
    public String promptHash() {
        return CanonicalJson.sha256(Map.of("system", system, "user", user));
    }
}
