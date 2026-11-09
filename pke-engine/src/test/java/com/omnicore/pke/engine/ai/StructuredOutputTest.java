package com.omnicore.pke.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.ai.StructuredOutput.StructuredOutputException;
import com.omnicore.pke.engine.kernel.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StructuredOutputTest {

    private static final JsonNode SCHEMA = JsonMapper.builder().build().readTree("""
            {"type": "object", "additionalProperties": false, "required": ["cards"],
             "properties": {"cards": {"type": "array", "items": {"type": "object", "required": ["kind"],
               "properties": {"kind": {"enum": ["rule", "definition"]}}}}}}""");

    record Cards(List<Card> cards) {
        record Card(String kind) {
        }
    }

    @Test
    void validJsonIsMappedEvenInsideAMarkdownFence() {
        Cards cards = StructuredOutput.of(SCHEMA, Cards.class).parse("```json\n{\"cards\": [{\"kind\": \"rule\"}]}\n```");
        assertThat(cards.cards()).extracting(Cards.Card::kind).containsExactly("rule");
    }

    @Test
    void everySchemaViolationIsReported() {
        assertThatThrownBy(() -> StructuredOutput.of(SCHEMA, JsonNode.class)
                .parse("{\"cards\": [{\"kind\": \"wish\"}, {}], \"extra\": 1}"))
                .isInstanceOfSatisfying(StructuredOutputException.class, e -> assertThat(e.violations())
                        .extracting(Violation::path).contains("/cards/0/kind", "/cards/1"));
    }

    @Test
    void proseAndEmptyAnswersAreRejected() {
        assertThatThrownBy(() -> StructuredOutput.of(SCHEMA, JsonNode.class).parse("Here are your cards!"))
                .isInstanceOf(StructuredOutputException.class).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> StructuredOutput.of(SCHEMA, JsonNode.class).parse("  "))
                .isInstanceOf(StructuredOutputException.class).hasMessageContaining("no content");
        assertThatThrownBy(() -> StructuredOutput.of(SCHEMA, JsonNode.class).parse("[1, 2]"))
                .isInstanceOf(StructuredOutputException.class).hasMessageContaining("not a JSON object");
    }
}
