package com.omnicore.pke.engine.pipeline.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SkillDefinitionParserTest {

    private static final String VALID = """
            ---
            name: demo-skill
            description: A demo skill.
            version: 1.2.0
            tools: [find-similar-cards]
            evalCases: [evals/a.json]
            inputSchema: { type: object, required: [text], properties: { text: { type: string } } }
            outputSchema: { type: object, required: [summary], properties: { summary: { type: string } } }
            ---
            # Instructions

            Summarise the text.
            """;

    @Test
    void parsesFrontMatterAndInstructions() {
        SkillDefinition skill = SkillDefinitionParser.parse(VALID.replace("\n", "\r\n"), "demo-skill", "test");
        assertThat(skill.ref()).isEqualTo("demo-skill@1.2.0");
        assertThat(skill.tools()).containsExactly("find-similar-cards");
        assertThat(skill.instructions()).startsWith("# Instructions").contains("Summarise the text.");
        assertThat(skill.hash()).isEqualTo(SkillDefinitionParser.parse(VALID, "demo-skill", "test").hash());
        assertThat(skill.runtime()).isEqualTo("engine");
    }

    @Test
    void rejectsInvalidSkills() {
        assertThatThrownBy(() -> SkillDefinitionParser.parse("# no front matter", "x", "test"))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> SkillDefinitionParser.parse(VALID, "other-directory", "test"))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::message).anyMatch(m -> m.contains("skill directory name")));
        assertThatThrownBy(() -> SkillDefinitionParser.parse(VALID.replace("version: 1.2.0", "version: one")
                .replace("tools: [find-similar-cards]", "tools: [Bad_Tool]"), "demo-skill", "test"))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::path).contains("/version", "/tools/0"));
        assertThatThrownBy(() -> SkillDefinitionParser.parse(VALID.replace("# Instructions\n\nSummarise the text.\n", ""),
                "demo-skill", "test")).hasMessageContaining("Invalid skill");
    }

    @Test
    void theBundledSkillsAreValid() throws Exception {
        try (var dirs = Files.list(Path.of("../definitions/skills"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                SkillDefinition skill = SkillDefinitionParser.parse(Files.readString(dir.resolve("SKILL.md")),
                        dir.getFileName().toString(), dir.toString());
                assertThat(skill.evalCases()).isNotEmpty();
                for (String evalCase : skill.evalCases()) {
                    assertThat(dir.resolve(evalCase)).exists();
                }
            }
        }
    }
}
