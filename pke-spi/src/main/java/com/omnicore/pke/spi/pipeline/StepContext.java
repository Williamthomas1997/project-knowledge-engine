package com.omnicore.pke.spi.pipeline;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** What a running step can see. */
public interface StepContext {

    String executionId();

    String pipelineId();

    String pipelineVersion();

    String stepId();

    /** 1 for the first attempt; retries and resumed executions count up. */
    int attempt();

    /** The actor that started the execution (recorded on change events). */
    String actor();

    /** The pipeline input. */
    Map<String, Object> input();

    /** Static parameters of this step from the pipeline definition ({@code with:}). */
    Map<String, Object> parameters();

    /** Output of an earlier, completed step of the same execution. */
    Optional<Map<String, Object>> output(String stepId);

    /** Throws {@link StepFailure} (cancelled) when the execution was cancelled; long steps call it between items. */
    void checkCancelled();

    /**
     * Returns the value stored under {@code key} for this step of this execution, or computes, stores and returns
     * it. Use it for expensive work inside a step (one model call per item), so a retry or a resumed execution
     * does not repeat it. Values must be plain JSON values.
     */
    Map<String, Object> memo(String key, Supplier<Map<String, Object>> compute);
}
