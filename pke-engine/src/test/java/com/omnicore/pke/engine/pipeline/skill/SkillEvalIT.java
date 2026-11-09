package com.omnicore.pke.engine.pipeline.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.KnowledgeIT;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs every eval case of every registered skill ({@code evalCases} in SKILL.md) through the {@link SkillRunner}
 * with the replay model and checks the expectations. With {@code -Precord-ai} the same cases record new fixtures
 * against the real model.
 * <p>
 * Expectation format: {@code {"pointer": "/cards", "minItems": 1}}, {@code "maxItems"}, {@code "equals"},
 * {@code "contains"} (case-insensitive substring of a text value), or
 * {@code "someItem": {"field": value, "field~": "substring"}} (one array item matches all fields).
 */
class SkillEvalIT extends KnowledgeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    SkillRegistry registry;

    @Autowired
    SkillRunner runner;

    @Test
    void everySkillPassesItsEvalCases() {
        List<String> failures = new ArrayList<>();
        int cases = 0;
        for (SkillDefinition skill : registry.activeSkills()) {
            assertThat(skill.evalCases()).as("skill %s has eval cases", skill.name()).isNotEmpty();
            for (String path : skill.evalCases()) {
                cases++;
                JsonNode evalCase = JSON.readTree(registry.readSkillFile(skill, path));
                @SuppressWarnings("unchecked")
                Map<String, Object> input = JSON.convertValue(evalCase.get("input"), Map.class);
                JsonNode output;
                try {
                    output = JSON.valueToTree(runner.run(skill.name(), input, null, "eval").output());
                } catch (RuntimeException e) {
                    failures.add(skill.name() + "/" + path + ": " + e.getMessage());
                    continue;
                }
                for (JsonNode expectation : evalCase.get("expect")) {
                    String problem = check(output, expectation);
                    if (problem != null) {
                        failures.add(skill.name() + "/" + path + ": " + problem + " in " + output);
                    }
                }
            }
        }
        assertThat(cases).isGreaterThanOrEqualTo(8);
        assertThat(failures).isEmpty();
    }

    static String check(JsonNode output, JsonNode expectation) {
        JsonNode node = output.at(expectation.get("pointer").asString());
        if (expectation.has("minItems") && node.size() < expectation.get("minItems").asInt()) {
            return expectation.get("pointer").asString() + " has " + node.size() + " items, expected at least "
                    + expectation.get("minItems").asInt();
        }
        if (expectation.has("maxItems") && node.size() > expectation.get("maxItems").asInt()) {
            return expectation.get("pointer").asString() + " has " + node.size() + " items, expected at most "
                    + expectation.get("maxItems").asInt();
        }
        if (expectation.has("equals") && !expectation.get("equals").equals(node)) {
            return expectation.get("pointer").asString() + " is " + node + ", expected " + expectation.get("equals");
        }
        if (expectation.has("contains") && !node.asString("").toLowerCase(java.util.Locale.ROOT)
                .contains(expectation.get("contains").asString().toLowerCase(java.util.Locale.ROOT))) {
            return expectation.get("pointer").asString() + " does not contain '" + expectation.get("contains").asString() + "'";
        }
        if (expectation.has("someItem")) {
            for (JsonNode item : node) {
                if (matches(item, expectation.get("someItem"))) {
                    return null;
                }
            }
            return "no item of " + expectation.get("pointer").asString() + " matches " + expectation.get("someItem");
        }
        return null;
    }

    private static boolean matches(JsonNode item, JsonNode pattern) {
        Iterator<Map.Entry<String, JsonNode>> fields = pattern.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().endsWith("~")) {
                String actual = item.path(field.getKey().substring(0, field.getKey().length() - 1)).asString("");
                if (!actual.contains(field.getValue().asString())) {
                    return false;
                }
            } else if (!field.getValue().equals(item.path(field.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
