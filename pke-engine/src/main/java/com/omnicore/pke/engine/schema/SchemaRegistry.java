package com.omnicore.pke.engine.schema;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory view of the <em>current</em> definition of every type. The durable state lives in Neo4j
 * ({@code :SchemaDefinition}, written through {@link SchemaDefinitionStore}); {@link SchemaService} keeps this
 * cache in step with it. Lookups here never touch the database, which is what lets the writer reject unknown
 * labels, relationship types and properties before any Cypher runs.
 */
@Component
public class SchemaRegistry {

    private final Map<String, DefinitionDocument> entityTypes = new ConcurrentHashMap<>();
    private final Map<String, DefinitionDocument> relationshipTypes = new ConcurrentHashMap<>();

    public Optional<EntityTypeDefinition> entityType(String name) {
        return Optional.ofNullable(name).map(entityTypes::get).map(d -> (EntityTypeDefinition) d.definition());
    }

    public Optional<RelationshipTypeDefinition> relationshipType(String name) {
        return Optional.ofNullable(name).map(relationshipTypes::get).map(d -> (RelationshipTypeDefinition) d.definition());
    }

    public Optional<DefinitionDocument> current(DefinitionKind kind, String name) {
        return Optional.ofNullable(name).map(map(kind)::get);
    }

    public List<DefinitionDocument> all(DefinitionKind kind) {
        return map(kind).values().stream().sorted(Comparator.comparing(DefinitionDocument::name)).toList();
    }

    void install(DefinitionDocument document) {
        map(document.kind()).put(document.name(), document);
    }

    void clear() {
        entityTypes.clear();
        relationshipTypes.clear();
    }

    private Map<String, DefinitionDocument> map(DefinitionKind kind) {
        return kind == DefinitionKind.ENTITY_TYPE ? entityTypes : relationshipTypes;
    }
}
