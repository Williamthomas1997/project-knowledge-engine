package com.omnicore.pke.engine.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EntityTypeDefinition(
        String name,
        String version,
        String displayName,
        String description,
        String category,
        LifecycleStatus status,
        Map<String, PropertyDefinition> properties,
        IdentityStrategy identityStrategy,
        List<String> naturalKey,
        boolean evidenceRequired,
        int evidenceMinItems,
        EmbeddingSpec embedding,
        String displayProperty,
        List<String> searchFields,
        Map<String, Object> extraction) implements TypeDefinition {

    public enum IdentityStrategy { NATURAL_KEY, EXPLICIT, GENERATED }

    /**
     * Fields to project into vector collections (ADR-007). Stored and validated in M1; embedding behaviour
     * arrives in M4. {@code collection} overrides the default collection name {@code <Type>_<field>}.
     */
    public record EmbeddingSpec(List<String> indexFields, String collection) {
        public EmbeddingSpec {
            indexFields = List.copyOf(indexFields);
        }
    }

    public EntityTypeDefinition {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        naturalKey = naturalKey == null ? List.of() : List.copyOf(naturalKey);
        searchFields = searchFields == null ? List.of() : List.copyOf(searchFields);
        extraction = extraction == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extraction));
    }

    @Override
    public DefinitionKind kind() {
        return DefinitionKind.ENTITY_TYPE;
    }
}
