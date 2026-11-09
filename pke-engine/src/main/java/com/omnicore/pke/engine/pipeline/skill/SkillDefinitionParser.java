package com.omnicore.pke.engine.pipeline.skill;

import com.omnicore.pke.engine.ai.JsonSchemas;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.pipeline.Hashes;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses {@code SKILL.md}: YAML front matter between {@code ---} lines, then the Markdown instructions. The front
 * matter is validated against {@code definitions/meta/skill.schema.json}; input and output schemas must compile.
 */
public final class SkillDefinitionParser {

    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final JsonSchemas.Compiled META = JsonSchemas.classpath("definitions/meta/skill.schema.json");

    private SkillDefinitionParser() {
    }

    /**
     * @param directoryName the skill's directory; it must equal {@code name}
     */
    public static SkillDefinition parse(String content, String directoryName, String location) {
        String text = content.replace("\r\n", "\n");
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---\n")) {
            throw invalid(location, "$", "SKILL.md must start with YAML front matter between '---' lines");
        }
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) {
            throw invalid(location, "$", "the front matter is not closed with '---'");
        }
        JsonNode front;
        try {
            front = YAML.readTree(text.substring(4, end));
        } catch (JacksonException e) {
            throw invalid(location, "$", "front matter is not valid YAML: " + e.getOriginalMessage());
        }
        if (front == null || !front.isObject()) {
            throw invalid(location, "$", "front matter must be a YAML mapping");
        }
        List<Violation> violations = new ArrayList<>(META.validate(front));
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid skill " + location, violations);
        }
        String name = front.get("name").asString();
        if (directoryName != null && !directoryName.equals(name)) {
            violations.add(new Violation("/name", "must equal the skill directory name '" + directoryName + "'"));
        }
        String instructions = text.substring(end + 5).strip();
        if (instructions.isEmpty()) {
            violations.add(new Violation("$", "the skill has no instructions after the front matter"));
        }
        JsonSchemas.Compiled input = compile(front.get("inputSchema"), "/inputSchema", violations);
        JsonSchemas.Compiled output = compile(front.get("outputSchema"), "/outputSchema", violations);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid skill " + location, violations);
        }
        return new SkillDefinition(name, front.get("version").asString(), front.get("description").asString(),
                front.get("inputSchema"), front.get("outputSchema"), strings(front.get("tools")),
                strings(front.get("appliesTo")), strings(front.get("evalCases")),
                front.path("runtime").asString("engine"), instructions, Hashes.sha256(text), location, input, output);
    }

    private static JsonSchemas.Compiled compile(JsonNode schema, String path, List<Violation> violations) {
        try {
            return JsonSchemas.compile(schema);
        } catch (RuntimeException e) {
            violations.add(new Violation(path, "is not a valid JSON Schema: " + e.getMessage()));
            return null;
        }
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asString()));
        return List.copyOf(values);
    }

    private static ValidationFailedException invalid(String location, String path, String message) {
        return new ValidationFailedException("Invalid skill " + location, List.of(new Violation(path, message)));
    }
}
