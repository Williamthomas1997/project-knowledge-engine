package com.omnicore.pke.engine.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * Roles of an API key (ADR-017). Higher roles include lower ones: {@code SCHEMA_ADMIN} and
 * {@code EVOLUTION_OPERATOR} both include {@code CONTRIBUTOR}, which includes {@code READER}.
 */
public enum Role {
    /** Reads, searches, task context, log explanations. */
    READER,
    /** Ingestion, pipelines, answers to open questions, proposals, journey interviews, evolution requests. */
    CONTRIBUTOR,
    /** Schema registration, restore, data migrations. */
    SCHEMA_ADMIN,
    /** Runs evolution (M7). */
    EVOLUTION_OPERATOR;

    /** This role and every role it includes. */
    public Set<Role> implied() {
        return switch (this) {
            case READER -> EnumSet.of(READER);
            case CONTRIBUTOR -> EnumSet.of(READER, CONTRIBUTOR);
            case SCHEMA_ADMIN -> EnumSet.of(READER, CONTRIBUTOR, SCHEMA_ADMIN);
            case EVOLUTION_OPERATOR -> EnumSet.of(READER, CONTRIBUTOR, EVOLUTION_OPERATOR);
        };
    }
}
