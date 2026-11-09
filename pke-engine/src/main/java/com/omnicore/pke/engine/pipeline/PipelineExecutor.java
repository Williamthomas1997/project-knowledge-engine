package com.omnicore.pke.engine.pipeline;

import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import java.util.List;
import java.util.Map;

/**
 * Runs pipelines. {@link LocalPipelineExecutor} runs them in-process, one at a time; a workflow engine can
 * implement this interface later without changing steps, skills or REST.
 */
public interface PipelineExecutor {

    /**
     * Starts the active version of a pipeline. Unless {@code force}, an execution with the same idempotency key
     * (pipeline version + the input sources' current versions + input) that is completed or still running is
     * returned instead of starting a new one.
     */
    ExecutionView start(String pipelineId, Map<String, Object> input, String actor, boolean force, boolean wait);

    /** Continues a FAILED, CANCELLED or PARTIAL execution from its first step that did not complete. */
    ExecutionView resume(String executionId, String actor, boolean wait);

    /** Cancels a pending or running execution (a running step stops at its next cancellation check). */
    ExecutionView cancel(String executionId, String actor);

    ExecutionView get(String executionId);

    List<ExecutionView> recent(String pipelineId, String status, int limit);
}
