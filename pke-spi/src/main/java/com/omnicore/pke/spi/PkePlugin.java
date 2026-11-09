package com.omnicore.pke.spi;

/**
 * Root contract for every engine plugin (source adapters, extractors, pipeline steps).
 * Level-3 evolution generates implementations of this interface under {@code pke-plugins/}.
 * The capability-specific sub-interfaces are introduced in later milestones.
 */
public interface PkePlugin {

    /** Stable identifier, e.g. {@code extract.java}. Never reused for a different capability. */
    String id();

    /** Semantic version of the plugin's behaviour; changes trigger reprocessing of affected sources. */
    String version();
}
