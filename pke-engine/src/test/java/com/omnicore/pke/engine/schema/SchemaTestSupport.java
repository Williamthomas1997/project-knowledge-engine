package com.omnicore.pke.engine.schema;

import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;

/** Builds schema objects for unit tests without Spring. */
final class SchemaTestSupport {

    static final SchemaDefinitionParser PARSER = new SchemaDefinitionParser(new MetaSchemaValidator());

    private SchemaTestSupport() {
    }

    static DefinitionDocument parse(String yaml) {
        return PARSER.parse(yaml, Format.YAML);
    }

    /** A registry loaded with every definition shipped in {@code definitions/schema/}. */
    static SchemaRegistry bundledRegistry() {
        SchemaRegistry registry = new SchemaRegistry();
        for (DefinitionSource.SourceFile file : new DefinitionSource("").schemaFiles()) {
            registry.install(PARSER.parse(file.text(), file.format()));
        }
        return registry;
    }
}
