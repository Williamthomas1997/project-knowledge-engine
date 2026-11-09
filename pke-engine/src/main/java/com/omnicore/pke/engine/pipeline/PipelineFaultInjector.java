package com.omnicore.pke.engine.pipeline;

/** Test hook: called before every step attempt; tests throw from it to simulate crashes. No production bean. */
public interface PipelineFaultInjector {

    void beforeStep(String pipelineId, String stepId, int attempt);
}
