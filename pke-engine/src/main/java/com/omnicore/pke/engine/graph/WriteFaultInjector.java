package com.omnicore.pke.engine.graph;

/**
 * Hook called at each {@link WriteStage} of a knowledge write. Production registers none. Failure tests register
 * one that throws, to prove that a failure anywhere rolls back the whole transaction (entity, edge, assertion,
 * evidence, version and change event together).
 */
@FunctionalInterface
public interface WriteFaultInjector {

    void reached(WriteStage stage);
}
