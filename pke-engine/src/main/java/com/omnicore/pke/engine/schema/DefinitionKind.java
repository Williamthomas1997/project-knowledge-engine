package com.omnicore.pke.engine.schema;

import java.util.Arrays;
import java.util.Optional;

public enum DefinitionKind {
    ENTITY_TYPE("EntityType", "meta/entity-type.schema.json"),
    RELATIONSHIP_TYPE("RelationshipType", "meta/relationship-type.schema.json");

    private final String json;
    private final String metaSchema;

    DefinitionKind(String json, String metaSchema) {
        this.json = json;
        this.metaSchema = metaSchema;
    }

    /** The {@code kind} value used in definition files. */
    public String json() {
        return json;
    }

    /** Meta-schema location, relative to the definitions root. */
    public String metaSchema() {
        return metaSchema;
    }

    public static Optional<DefinitionKind> fromJson(String value) {
        return Arrays.stream(values()).filter(k -> k.json.equals(value)).findFirst();
    }
}
