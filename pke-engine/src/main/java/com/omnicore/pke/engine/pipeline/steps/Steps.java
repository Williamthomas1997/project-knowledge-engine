package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Small helpers for reading step inputs and outputs (plain JSON values). */
final class Steps {

    static final String CARD_SKILL = "brd-to-knowledge-cards";
    static final String RECONCILE_SKILL = "reconcile-knowledge";
    static final String LINK_SKILL = "link-requirement-to-implementation";

    private Steps() {
    }

    static Map<String, Object> output(StepContext context, String stepId) {
        return context.output(stepId).orElseThrow(() -> new StepFailure(ErrorClass.PERMANENT,
                "step '" + context.stepId() + "' needs the output of step '" + stepId + "'"));
    }

    /** The output of the first earlier step that has {@code field}; pipelines may name their steps freely. */
    static Map<String, Object> outputWith(StepContext context, String field, String... candidates) {
        for (String id : candidates) {
            var output = context.output(id);
            if (output.isPresent() && output.get().containsKey(field)) {
                return output.get();
            }
        }
        throw new StepFailure(ErrorClass.PERMANENT, "step '" + context.stepId() + "' needs an earlier step with '"
                + field + "' (looked at " + List.of(candidates) + ")");
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    /** ISO-8601 with offset (what the writer accepts), from a driver temporal value. */
    static String isoDateTime(Object value) {
        if (value instanceof ZonedDateTime zoned) {
            return zoned.toOffsetDateTime().toString();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toString();
        }
        return value == null ? null : value.toString();
    }

    static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
