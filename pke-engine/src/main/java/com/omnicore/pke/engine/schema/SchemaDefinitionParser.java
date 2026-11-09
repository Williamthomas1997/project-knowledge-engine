package com.omnicore.pke.engine.schema;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ReservedNames;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.schema.EntityTypeDefinition.EmbeddingSpec;
import com.omnicore.pke.engine.schema.EntityTypeDefinition.IdentityStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Turns a YAML or JSON definition into a validated {@link DefinitionDocument}:
 * <ol>
 *   <li>parse, 2. validate against the kind's JSON Schema 2020-12 meta-schema,</li>
 *   <li>check the rules a JSON Schema cannot express (keys must be declared properties, reserved names...).</li>
 * </ol>
 * Checks that need other definitions (relationship endpoint types exist) live in {@link SchemaService}.
 */
@Component
public class SchemaDefinitionParser {

    public enum Format { YAML, JSON }

    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final MetaSchemaValidator metaSchemaValidator;

    public SchemaDefinitionParser(MetaSchemaValidator metaSchemaValidator) {
        this.metaSchemaValidator = metaSchemaValidator;
    }

    public DefinitionDocument parse(String text, Format format) {
        JsonNode node;
        try {
            node = (format == Format.YAML ? YAML : JSON).readTree(text);
        } catch (JacksonException e) {
            throw ValidationFailedException.of("$", "Not valid " + format + ": " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw ValidationFailedException.of("$", "A definition must be a " + format + " object");
        }
        JsonNode kindNode = node.get("kind");
        DefinitionKind kind = kindNode == null ? null : DefinitionKind.fromJson(kindNode.asString()).orElse(null);
        if (kind == null) {
            throw ValidationFailedException.of("/kind", "must be one of EntityType, RelationshipType");
        }

        List<Violation> violations = new ArrayList<>(metaSchemaValidator.validate(kind, node));
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Definition does not match the " + kind.json() + " meta-schema", violations);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = JSON.convertValue(node, LinkedHashMap.class);
        TypeDefinition definition = kind == DefinitionKind.ENTITY_TYPE
                ? entityType(raw, violations)
                : relationshipType(raw, violations);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid " + kind.json() + " definition", violations);
        }
        String canonical = CanonicalJson.write(raw);
        return new DefinitionDocument(definition, raw, canonical, CanonicalJson.sha256(raw));
    }

    private EntityTypeDefinition entityType(Map<String, Object> raw, List<Violation> violations) {
        String name = (String) raw.get("name");
        if (ReservedNames.isReservedLabel(name)) {
            violations.add(new Violation("/name", "'" + name + "' is reserved by the engine"));
        }
        Map<String, PropertyDefinition> properties = properties(map(raw.get("properties")), violations);

        Map<String, Object> identity = map(raw.get("identity"));
        IdentityStrategy strategy = switch (identity.getOrDefault("strategy", "generated").toString()) {
            case "naturalKey" -> IdentityStrategy.NATURAL_KEY;
            case "explicit" -> IdentityStrategy.EXPLICIT;
            default -> IdentityStrategy.GENERATED;
        };
        List<String> naturalKey = strings(identity.get("naturalKey"));
        for (String key : naturalKey) {
            PropertyDefinition property = properties.get(key);
            if (property == null) {
                violations.add(new Violation("/identity/naturalKey", "'" + key + "' is not a declared property"));
            } else if (!property.required()) {
                violations.add(new Violation("/identity/naturalKey", "'" + key + "' must be a required property"));
            } else if (property.type() == PropertyType.STRING_LIST) {
                violations.add(new Violation("/identity/naturalKey", "'" + key + "' cannot be a string-list"));
            }
        }

        EmbeddingSpec embedding = null;
        if (raw.get("embedding") instanceof Map<?, ?> embeddingMap) {
            List<String> indexFields = strings(embeddingMap.get("indexFields"));
            for (String field : indexFields) {
                PropertyDefinition property = properties.get(field);
                if (property == null || !property.type().isTextual()) {
                    violations.add(new Violation("/embedding/indexFields",
                            "'" + field + "' must be a declared string or text property"));
                }
            }
            embedding = new EmbeddingSpec(indexFields, (String) embeddingMap.get("collection"));
        }

        Map<String, Object> retrieval = map(raw.get("retrieval"));
        String displayProperty = (String) retrieval.get("displayProperty");
        List<String> searchFields = strings(retrieval.get("searchFields"));
        if (displayProperty != null && !properties.containsKey(displayProperty)) {
            violations.add(new Violation("/retrieval/displayProperty", "'" + displayProperty + "' is not a declared property"));
        }
        searchFields.stream().filter(f -> !properties.containsKey(f)).forEach(f ->
                violations.add(new Violation("/retrieval/searchFields", "'" + f + "' is not a declared property")));

        Map<String, Object> evidence = map(raw.get("evidence"));
        return new EntityTypeDefinition(
                name,
                (String) raw.get("version"),
                (String) raw.get("displayName"),
                (String) raw.get("description"),
                (String) raw.get("category"),
                LifecycleStatus.fromJson((String) raw.get("status")),
                properties,
                strategy,
                naturalKey,
                Boolean.TRUE.equals(evidence.get("required")),
                minItems(evidence),
                embedding,
                displayProperty,
                searchFields,
                map(raw.get("extraction")));
    }

    private RelationshipTypeDefinition relationshipType(Map<String, Object> raw, List<Violation> violations) {
        String name = (String) raw.get("name");
        if (ReservedNames.isReservedRelationshipType(name)) {
            violations.add(new Violation("/name", "'" + name + "' is reserved by the engine"));
        }
        Map<String, PropertyDefinition> properties = properties(map(raw.get("properties")), violations);
        List<String> discriminator = strings(map(raw.get("identity")).get("discriminator"));
        for (String key : discriminator) {
            if (!properties.containsKey(key)) {
                violations.add(new Violation("/identity/discriminator", "'" + key + "' is not a declared property"));
            } else if (!properties.get(key).required()) {
                violations.add(new Violation("/identity/discriminator", "'" + key + "' must be a required property"));
            }
        }
        Map<String, Object> evidence = map(raw.get("evidence"));
        Map<String, Object> policy = map(raw.get("policy"));
        Map<String, Object> temporal = map(raw.get("temporal"));
        return new RelationshipTypeDefinition(
                name,
                (String) raw.get("version"),
                (String) raw.get("description"),
                (String) raw.get("category"),
                LifecycleStatus.fromJson((String) raw.get("status")),
                strings(raw.get("sourceTypes")),
                strings(raw.get("targetTypes")),
                properties,
                discriminator,
                Boolean.TRUE.equals(evidence.get("required")),
                minItems(evidence),
                (String) temporal.getOrDefault("validity", "interval"),
                strings(policy.get("discovery")),
                (String) policy.getOrDefault("approval", "auto"),
                map(raw.get("queryHints")));
    }

    private Map<String, PropertyDefinition> properties(Map<String, Object> raw, List<Violation> violations) {
        Map<String, PropertyDefinition> properties = new LinkedHashMap<>();
        raw.forEach((propertyName, value) -> {
            Map<String, Object> spec = map(value);
            PropertyType type = PropertyType.fromJson((String) spec.get("type"));
            List<String> enumValues = strings(spec.get("enum"));
            String pattern = (String) spec.get("pattern");
            String path = "/properties/" + propertyName;
            if (!enumValues.isEmpty() && !type.isTextual()) {
                violations.add(new Violation(path + "/enum", "enum is only allowed on string or text properties"));
            }
            if (pattern != null) {
                if (!type.isTextual()) {
                    violations.add(new Violation(path + "/pattern", "pattern is only allowed on string or text properties"));
                }
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException e) {
                    violations.add(new Violation(path + "/pattern", "not a valid regular expression: " + e.getDescription()));
                }
            }
            properties.put(propertyName, new PropertyDefinition(propertyName, type,
                    Boolean.TRUE.equals(spec.get("required")), (String) spec.get("description"), enumValues, pattern,
                    Boolean.TRUE.equals(spec.get("deprecated"))));
        });
        return properties;
    }

    private static int minItems(Map<String, Object> evidence) {
        Object minItems = evidence.get("minItems");
        if (minItems instanceof Number n) {
            return n.intValue();
        }
        return Boolean.TRUE.equals(evidence.get("required")) ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
