package com.omnicore.pke.engine.schema;

import java.util.List;

public record PropertyDefinition(
        String name,
        PropertyType type,
        boolean required,
        String description,
        List<String> enumValues,
        String pattern,
        boolean deprecated) {

    public PropertyDefinition {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
}
