package com.omnicore.pke.engine.schema;

import java.util.Arrays;

/** Property data types a schema may declare. Values are stored as native Neo4j values (see {@link PropertyValues}). */
public enum PropertyType {
    STRING("string"),
    /** Long free text. Stored like STRING; marks fields worth embedding or full-text indexing. */
    TEXT("text"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    /** ISO-8601 date-time with offset, stored as its normalised string form. */
    DATETIME("datetime"),
    STRING_LIST("string-list");

    private final String json;

    PropertyType(String json) {
        this.json = json;
    }

    public String json() {
        return json;
    }

    public boolean isTextual() {
        return this == STRING || this == TEXT;
    }

    public static PropertyType fromJson(String value) {
        return Arrays.stream(values()).filter(t -> t.json.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown property type: " + value));
    }
}
