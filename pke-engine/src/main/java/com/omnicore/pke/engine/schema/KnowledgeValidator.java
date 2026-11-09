package com.omnicore.pke.engine.schema;

import com.omnicore.pke.engine.kernel.CypherIdentifiers;
import com.omnicore.pke.engine.kernel.ReservedNames;
import com.omnicore.pke.engine.kernel.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Checks knowledge writes against the <em>active</em> schema, in memory, before the writer opens a transaction.
 * Only names that pass here can ever reach Cypher as labels, relationship types or property keys.
 */
@Component
public class KnowledgeValidator {

    private final SchemaRegistry registry;

    public KnowledgeValidator(SchemaRegistry registry) {
        this.registry = registry;
    }

    public record ValidatedEntity(EntityTypeDefinition definition, Map<String, Object> properties) {
    }

    public record ValidatedRelationship(RelationshipTypeDefinition definition, Map<String, Object> properties) {
    }

    /** Returns null (with violations) if the type is unknown; otherwise the normalised properties, sorted by key. */
    public ValidatedEntity entity(String type, Map<String, Object> properties, String path, List<Violation> violations) {
        EntityTypeDefinition definition = entityType(type, path + "/type", violations);
        if (definition == null) {
            return null;
        }
        return new ValidatedEntity(definition, properties(definition, properties, path + "/properties", violations));
    }

    public ValidatedRelationship relationship(String type, Map<String, Object> properties, String path,
                                              List<Violation> violations) {
        if (type == null || !CypherIdentifiers.isRelationshipType(type)) {
            violations.add(new Violation(path + "/type", "'" + type + "' is not a valid relationship type name"));
            return null;
        }
        RelationshipTypeDefinition definition = registry.relationshipType(type).orElse(null);
        if (definition == null) {
            violations.add(new Violation(path + "/type", "relationship type '" + type + "' is not in the active schema"));
            return null;
        }
        if (!definition.status().acceptsWrites()) {
            violations.add(new Violation(path + "/type", "relationship type '" + type + "' is retired"));
            return null;
        }
        return new ValidatedRelationship(definition, properties(definition, properties, path + "/properties", violations));
    }

    public EntityTypeDefinition entityType(String type, String path, List<Violation> violations) {
        if (type == null || !CypherIdentifiers.isLabel(type) || ReservedNames.isReservedLabel(type)) {
            violations.add(new Violation(path, "'" + type + "' is not a valid entity type name"));
            return null;
        }
        EntityTypeDefinition definition = registry.entityType(type).orElse(null);
        if (definition == null) {
            violations.add(new Violation(path, "entity type '" + type + "' is not in the active schema"));
            return null;
        }
        if (!definition.status().acceptsWrites()) {
            violations.add(new Violation(path, "entity type '" + type + "' is retired"));
            return null;
        }
        return definition;
    }

    /** Values of the natural key, in declared order, taken from {@code source} and normalised. */
    public List<Object> naturalKey(EntityTypeDefinition definition, Map<String, Object> source, String path,
                                   List<Violation> violations) {
        List<Object> values = new ArrayList<>();
        for (String key : definition.naturalKey()) {
            Object value = source == null ? null : source.get(key);
            if (value == null) {
                violations.add(new Violation(path + "/" + key, "natural key property is missing"));
                continue;
            }
            values.add(PropertyValues.coerce(definition.properties().get(key), value, path + "/" + key, violations));
        }
        return values;
    }

    /** Values of the relationship discriminator, in declared order (already-normalised properties). */
    public List<Object> discriminator(RelationshipTypeDefinition definition, Map<String, Object> properties) {
        return definition.discriminator().stream().map(properties::get).toList();
    }

    public void checkEndpoints(RelationshipTypeDefinition definition, String subjectType, String objectType, String path,
                               List<Violation> violations) {
        if (subjectType != null && !definition.allowsSource(subjectType)) {
            violations.add(new Violation(path + "/subject", definition.name() + " does not allow source type '"
                    + subjectType + "' (allowed: " + definition.sourceTypes() + ")"));
        }
        if (objectType != null && !definition.allowsTarget(objectType)) {
            violations.add(new Violation(path + "/object", definition.name() + " does not allow target type '"
                    + objectType + "' (allowed: " + definition.targetTypes() + ")"));
        }
    }

    private Map<String, Object> properties(TypeDefinition definition, Map<String, Object> input, String path,
                                           List<Violation> violations) {
        Map<String, Object> normalised = new TreeMap<>();
        Map<String, Object> values = input == null ? Map.of() : input;
        values.forEach((key, value) -> {
            PropertyDefinition property = definition.properties().get(key);
            if (property == null) {
                violations.add(new Violation(path + "/" + key, "property '" + key + "' is not declared by "
                        + definition.name() + " " + definition.version()));
                return;
            }
            Object coerced = PropertyValues.coerce(property, value, path + "/" + key, violations);
            if (coerced != null) {
                normalised.put(CypherIdentifiers.propertyKey(key), coerced);
            }
        });
        definition.properties().values().stream()
                .filter(PropertyDefinition::required)
                .filter(p -> values.get(p.name()) == null)
                .forEach(p -> violations.add(new Violation(path + "/" + p.name(), "required property is missing")));
        return normalised;
    }
}
