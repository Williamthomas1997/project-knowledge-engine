package com.omnicore.pke.engine.kernel;

import java.util.Set;

/**
 * Labels, relationship types and property prefixes owned by the engine. Registered domain types may not use
 * them, so engine infrastructure can never be confused with (or overwritten by) domain knowledge.
 */
public final class ReservedNames {

    /** Prefix of every system property on knowledge nodes and edges. */
    public static final String SYSTEM_PROPERTY_PREFIX = "pke_";

    public static final Set<String> LABELS = Set.of(
            "Entity", "Assertion", "Evidence", "EntityVersion", "AssertionVersion", "ChangeEvent", "Sequence",
            "SchemaDefinition", "Snapshot", "SourceArtifact", "SourceVersion", "Chunk", "Section", "Conflict",
            "IndexTask", "VectorCollection", "EmbeddingGeneration", "OpenQuestion", "PipelineExecution",
            "StepExecution", "Alias");

    public static final Set<String> RELATIONSHIP_TYPES = Set.of(
            "SUBJECT", "OBJECT", "SUPPORTED_BY", "HAS_VERSION", "ABOUT", "FROM", "HAS_CHUNK", "BETWEEN",
            "REPLACED_BY", "INDEXES", "EMBEDDED_WITH");

    private ReservedNames() {
    }

    public static boolean isReservedLabel(String name) {
        return LABELS.contains(name);
    }

    public static boolean isReservedRelationshipType(String name) {
        return RELATIONSHIP_TYPES.contains(name);
    }

    public static boolean isSystemProperty(String name) {
        return name != null && name.startsWith(SYSTEM_PROPERTY_PREFIX);
    }
}
