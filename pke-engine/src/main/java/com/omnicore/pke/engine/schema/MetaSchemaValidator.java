package com.omnicore.pke.engine.schema;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.omnicore.pke.engine.kernel.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Validates definition documents against the JSON Schema 2020-12 meta-schemas in {@code definitions/meta/}. */
@Component
public class MetaSchemaValidator {

    private final Map<DefinitionKind, Schema> schemas = new EnumMap<>(DefinitionKind.class);

    public MetaSchemaValidator() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        for (DefinitionKind kind : DefinitionKind.values()) {
            try (InputStream in = new ClassPathResource("definitions/" + kind.metaSchema()).getInputStream()) {
                schemas.put(kind, registry.getSchema(in));
            } catch (IOException e) {
                throw new UncheckedIOException("Meta-schema missing from classpath: definitions/" + kind.metaSchema(), e);
            }
        }
    }

    public List<Violation> validate(DefinitionKind kind, JsonNode document) {
        List<Error> errors = schemas.get(kind).validate(document);
        return errors.stream()
                .map(e -> new Violation(pathOf(e), e.getMessage()))
                .toList();
    }

    private static String pathOf(Error error) {
        String path = String.valueOf(error.getInstanceLocation());
        return path.isEmpty() ? "$" : path;
    }
}
