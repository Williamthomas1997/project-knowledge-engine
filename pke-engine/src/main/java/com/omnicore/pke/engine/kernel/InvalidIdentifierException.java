package com.omnicore.pke.engine.kernel;

/** Thrown when a schema identifier would be unsafe or invalid as a Neo4j label, type or property key. */
public class InvalidIdentifierException extends IllegalArgumentException {

    private final String kind;
    private final String value;

    public InvalidIdentifierException(String kind, String value, String pattern) {
        super("Invalid " + kind + " '" + value + "': must match " + pattern);
        this.kind = kind;
        this.value = value;
    }

    public String kind() {
        return kind;
    }

    public String value() {
        return value;
    }
}
