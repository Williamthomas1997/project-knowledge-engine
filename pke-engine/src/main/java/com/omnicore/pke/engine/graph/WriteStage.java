package com.omnicore.pke.engine.graph;

/** Points inside a write transaction where a {@link WriteFaultInjector} is consulted. */
public enum WriteStage {
    AFTER_ENTITY,
    AFTER_ASSERTION,
    AFTER_EDGE,
    AFTER_EVIDENCE,
    AFTER_VERSION,
    BEFORE_EVENTS
}
