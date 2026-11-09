package com.omnicore.pke.engine.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RelationshipTypeDefinition(
        String name,
        String version,
        String description,
        String category,
        LifecycleStatus status,
        List<String> sourceTypes,
        List<String> targetTypes,
        Map<String, PropertyDefinition> properties,
        List<String> discriminator,
        boolean evidenceRequired,
        int evidenceMinItems,
        String temporalValidity,
        List<String> discovery,
        String approval,
        Map<String, Object> queryHints) implements TypeDefinition {

    public static final String ANY_TYPE = "*";

    public RelationshipTypeDefinition {
        sourceTypes = List.copyOf(sourceTypes);
        targetTypes = List.copyOf(targetTypes);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        discriminator = discriminator == null ? List.of() : List.copyOf(discriminator);
        discovery = discovery == null ? List.of() : List.copyOf(discovery);
        queryHints = queryHints == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(queryHints));
    }

    @Override
    public DefinitionKind kind() {
        return DefinitionKind.RELATIONSHIP_TYPE;
    }

    public boolean allowsSource(String entityType) {
        return sourceTypes.contains(ANY_TYPE) || sourceTypes.contains(entityType);
    }

    public boolean allowsTarget(String entityType) {
        return targetTypes.contains(ANY_TYPE) || targetTypes.contains(entityType);
    }
}
