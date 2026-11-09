package com.omnicore.pke.engine.schema;

import java.util.Map;

/** Common view of entity and relationship type definitions. */
public sealed interface TypeDefinition permits EntityTypeDefinition, RelationshipTypeDefinition {

    DefinitionKind kind();

    String name();

    String version();

    String description();

    LifecycleStatus status();

    /** Declared properties, in declaration order. */
    Map<String, PropertyDefinition> properties();

    boolean evidenceRequired();

    int evidenceMinItems();
}
