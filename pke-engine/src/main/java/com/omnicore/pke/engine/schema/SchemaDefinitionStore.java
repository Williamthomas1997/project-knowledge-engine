package com.omnicore.pke.engine.schema;

import java.util.List;

/**
 * Durable storage of definitions, implemented by the graph writer (the only component allowed to write to
 * Neo4j). Defined here so {@code schema} never depends on {@code graph}.
 */
public interface SchemaDefinitionStore {

    /** The current definition of every type name, entity types first. */
    List<StoredDefinition> loadCurrent();

    /** Every registered version of one type, oldest first. */
    List<StoredDefinition> history(DefinitionKind kind, String name);

    /**
     * Registers a definition in one transaction with its {@code SCHEMA_REGISTERED} event, making it the current
     * definition of its name. Registering the same key with the same hash again is a no-op ({@code seq == null});
     * the same key with a different hash fails with a {@code ConflictException}.
     */
    Registration register(DefinitionDocument document, String actor);

    record StoredDefinition(String kind, String name, String version, String status, String hash, String definitionJson,
                            long registeredSeq, boolean current) {
    }

    record Registration(String key, boolean registered, Long seq) {
    }
}
