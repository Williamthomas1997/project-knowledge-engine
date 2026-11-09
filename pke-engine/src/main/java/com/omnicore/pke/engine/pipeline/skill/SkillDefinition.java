package com.omnicore.pke.engine.pipeline.skill;

import com.omnicore.pke.engine.ai.JsonSchemas;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A parsed, validated {@code SKILL.md}: front matter plus the Markdown body, which is the model's instructions.
 *
 * @param hash SHA-256 of the file (line endings normalised); a name + version is immutable
 */
public record SkillDefinition(String name, String version, String description, JsonNode inputSchema,
                              JsonNode outputSchema, List<String> tools, List<String> appliesTo, List<String> evalCases,
                              String runtime, String instructions, String hash, String location,
                              JsonSchemas.Compiled compiledInput, JsonSchemas.Compiled compiledOutput) {

    public String ref() {
        return name + "@" + version;
    }
}
