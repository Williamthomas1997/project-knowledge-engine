package com.omnicore.pke.engine.ai;

import com.omnicore.pke.engine.kernel.Violation;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses a model answer as JSON, validates it against the skill's output schema and maps it to {@code T}.
 * A model may wrap JSON in a Markdown code fence; that is tolerated. Anything else that is not valid JSON, or
 * JSON that breaks the schema, is rejected with every violation listed ({@link StructuredOutputException}).
 */
public final class StructuredOutput<T> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JsonSchemas.Compiled schema;
    private final Class<T> type;

    private StructuredOutput(JsonSchemas.Compiled schema, Class<T> type) {
        this.schema = schema;
        this.type = type;
    }

    public static <T> StructuredOutput<T> of(JsonNode outputSchema, Class<T> type) {
        return new StructuredOutput<>(JsonSchemas.compile(outputSchema), type);
    }

    public T parse(String content) {
        JsonNode tree = tree(content);
        List<Violation> violations = schema.validate(tree);
        if (!violations.isEmpty()) {
            throw new StructuredOutputException("The model answer does not satisfy the output schema", violations);
        }
        try {
            return JSON.treeToValue(tree, type);
        } catch (JacksonException e) {
            throw new StructuredOutputException("The model answer cannot be mapped to " + type.getSimpleName(),
                    List.of(new Violation("$", e.getOriginalMessage())));
        }
    }

    static JsonNode tree(String content) {
        if (content == null || content.isBlank()) {
            throw new StructuredOutputException("The model returned no content", List.of(new Violation("$", "is empty")));
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int fenceEnd = text.lastIndexOf("```");
            if (firstLine > 0 && fenceEnd > firstLine) {
                text = text.substring(firstLine + 1, fenceEnd).strip();
            }
        }
        try {
            JsonNode tree = JSON.readTree(text);
            if (tree == null || !tree.isObject()) {
                throw new StructuredOutputException("The model answer is not a JSON object",
                        List.of(new Violation("$", "must be a JSON object")));
            }
            return tree;
        } catch (JacksonException e) {
            throw new StructuredOutputException("The model answer is not valid JSON",
                    List.of(new Violation("$", e.getOriginalMessage())));
        }
    }

    /** The answer broke the contract: never retried, the step fails PERMANENT and nothing is written. */
    public static class StructuredOutputException extends RuntimeException {

        private final List<Violation> violations;

        public StructuredOutputException(String message, List<Violation> violations) {
            super(message + ": " + violations);
            this.violations = List.copyOf(violations);
        }

        public List<Violation> violations() {
            return violations;
        }
    }
}
