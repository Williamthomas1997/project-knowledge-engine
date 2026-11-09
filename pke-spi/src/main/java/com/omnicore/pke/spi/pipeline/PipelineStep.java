package com.omnicore.pke.spi.pipeline;

import java.util.Map;

/**
 * A pipeline step implementation. Pipelines ({@code definitions/pipelines/*.yaml}) reference steps by
 * {@link #name()}. The executor persists every step's output, so a resumed execution never runs a completed step
 * again: outputs must therefore be plain JSON values (maps, lists, strings, numbers, booleans, null).
 * <p>
 * Throw {@link StepFailure} to classify a failure; any other exception is treated as {@code TRANSIENT} when it
 * looks like an I/O or timeout problem and {@code PERMANENT} otherwise.
 */
public interface PipelineStep {

    /** Name used in pipeline definitions ({@code uses: <name>}), kebab-case. */
    String name();

    Map<String, Object> execute(StepContext context);
}
