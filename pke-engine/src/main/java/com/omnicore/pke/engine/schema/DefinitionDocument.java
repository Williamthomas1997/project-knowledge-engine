package com.omnicore.pke.engine.schema;

import java.util.Map;

/**
 * A parsed and validated definition together with its canonical JSON form. The hash identifies the content:
 * a name + version can only ever be registered with one hash.
 */
public record DefinitionDocument(TypeDefinition definition, Map<String, Object> raw, String canonicalJson, String hash) {

    public DefinitionKind kind() {
        return definition.kind();
    }

    public String name() {
        return definition.name();
    }

    public String version() {
        return definition.version();
    }

    /** Registry key, e.g. {@code EntityType/BusinessRequirement/1.0.0}. */
    public String key() {
        return key(kind(), name(), version());
    }

    public static String key(DefinitionKind kind, String name, String version) {
        return kind.json() + "/" + name + "/" + version;
    }
}
