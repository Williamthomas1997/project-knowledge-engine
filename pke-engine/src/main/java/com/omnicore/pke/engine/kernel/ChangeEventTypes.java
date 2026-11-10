package com.omnicore.pke.engine.kernel;

/**
 * Names of the change events the engine records today. Event types are plain strings, so later milestones
 * (and plugins) can add new ones without changing this class.
 */
public final class ChangeEventTypes {

    public static final String ENTITY_CREATED = "ENTITY_CREATED";
    public static final String ENTITY_UPDATED = "ENTITY_UPDATED";
    public static final String ENTITY_RENAMED = "ENTITY_RENAMED";
    public static final String ENTITY_INVALIDATED = "ENTITY_INVALIDATED";
    public static final String ENTITY_REVALIDATED = "ENTITY_REVALIDATED";
    public static final String EVIDENCE_ADDED = "EVIDENCE_ADDED";
    public static final String RELATIONSHIP_CREATED = "RELATIONSHIP_CREATED";
    public static final String RELATIONSHIP_UPDATED = "RELATIONSHIP_UPDATED";
    public static final String RELATIONSHIP_INVALIDATED = "RELATIONSHIP_INVALIDATED";
    public static final String RELATIONSHIP_REVALIDATED = "RELATIONSHIP_REVALIDATED";
    public static final String SCHEMA_REGISTERED = "SCHEMA_REGISTERED";
    public static final String SOURCE_REGISTERED = "SOURCE_REGISTERED";
    public static final String DOCUMENT_VERSION_ADDED = "DOCUMENT_VERSION_ADDED";
    public static final String COMMITS_RECORDED = "COMMITS_RECORDED";
    public static final String EXPORT_VERSION_RECORDED = "EXPORT_VERSION_RECORDED";
    public static final String SECTIONS_REPARSED = "SECTIONS_REPARSED";
    public static final String SOURCE_REMOVED = "SOURCE_REMOVED";

    private ChangeEventTypes() {
    }
}
