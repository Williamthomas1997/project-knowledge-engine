package com.omnicore.pke.engine.pipeline;

import com.omnicore.pke.engine.ai.JsonSchemas;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A parsed, validated pipeline definition ({@code definitions/pipelines/*.yaml}). */
public record PipelineDefinition(String id, String version, String description, JsonSchemas.Compiled input,
                                 List<SourceField> idempotencySources, List<Step> steps, String hash, String location) {

    public record SourceField(String field, String prefix) {
    }

    /**
     * @param uses    a {@code PipelineStep} bean name or {@code skill:<name>}
     * @param forEach skill steps: {@code <stepId>.<field>} of an earlier step holding {@code [{key, input}]}
     */
    public record Step(String id, String uses, String forEach, Map<String, Object> with, Duration timeout,
                       int maxAttempts, Duration backoff) {

        public boolean isSkill() {
            return uses.startsWith("skill:");
        }

        public String skillName() {
            return uses.substring("skill:".length());
        }
    }

    public String ref() {
        return id + "@" + version;
    }
}
