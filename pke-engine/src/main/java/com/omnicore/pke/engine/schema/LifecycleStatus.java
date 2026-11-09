package com.omnicore.pke.engine.schema;

/**
 * Lifecycle of a type definition. Deprecated types still accept writes; retired types are kept so historical
 * knowledge stays interpretable (MASTER_SPEC §13.4) but reject new writes.
 */
public enum LifecycleStatus {
    ACTIVE, DEPRECATED, RETIRED;

    public static LifecycleStatus fromJson(String value) {
        return value == null ? ACTIVE : valueOf(value.toUpperCase());
    }

    public boolean acceptsWrites() {
        return this != RETIRED;
    }
}
