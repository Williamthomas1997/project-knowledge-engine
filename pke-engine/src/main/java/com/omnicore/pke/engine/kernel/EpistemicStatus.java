package com.omnicore.pke.engine.kernel;

/**
 * How we know a fact (MASTER_SPEC §8). This is engine semantics, not domain vocabulary, so a closed enum is
 * deliberate. Confidence values are stored separately and are never treated as calibrated probabilities.
 */
public enum EpistemicStatus {
    /** Seen directly in a source, e.g. a document states it verbatim. */
    OBSERVED,
    /** Produced by a deterministic parser, e.g. a method call found by JavaParser. */
    EXTRACTED_DETERMINISTIC,
    /** Inferred by an AI model; must carry evidence and model metadata. */
    INFERRED_AI,
    /** Confirmed by a person. */
    HUMAN_VERIFIED,
    /** Known to be contradicted by other evidence. */
    CONTRADICTED,
    /** Replaced by a newer fact. */
    SUPERSEDED
}
