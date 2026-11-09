package com.omnicore.pke.engine.ai;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.omnicore.pke.engine.kernel.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Compiles JSON Schema 2020-12 documents (skill input/output schemas) and validates instances against them. */
public final class JsonSchemas {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private JsonSchemas() {
    }

    public static Compiled compile(JsonNode schema) {
        return new Compiled(REGISTRY.getSchema(schema));
    }

    /** Loads and compiles a schema from the classpath, e.g. {@code definitions/meta/skill.schema.json}. */
    public static Compiled classpath(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return compile(JsonMapper.builder().build().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Schema missing from classpath: " + location, e);
        }
    }

    public static final class Compiled {

        private final Schema schema;

        private Compiled(Schema schema) {
            this.schema = schema;
        }

        public List<Violation> validate(JsonNode instance) {
            List<Error> errors = schema.validate(instance);
            return errors.stream().map(e -> {
                String path = String.valueOf(e.getInstanceLocation());
                return new Violation(path.isEmpty() ? "$" : path, e.getMessage());
            }).toList();
        }
    }
}
