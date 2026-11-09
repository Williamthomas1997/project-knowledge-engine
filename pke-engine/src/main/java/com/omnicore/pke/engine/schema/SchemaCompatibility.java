package com.omnicore.pke.engine.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a new version of a type can replace the current one without migrating existing data.
 * Allowed in place: new optional properties, documentation, wider endpoints, new enum values, embedding and
 * retrieval hints, lifecycle changes. Everything else is reported as a breaking change; breaking changes need a
 * declared migration, which the engine does not support yet, so they are rejected.
 */
public final class SchemaCompatibility {

    private SchemaCompatibility() {
    }

    public static List<String> breakingChanges(TypeDefinition current, TypeDefinition proposed) {
        List<String> changes = new ArrayList<>(propertyChanges(current.properties(), proposed.properties()));
        if (current instanceof EntityTypeDefinition c && proposed instanceof EntityTypeDefinition p) {
            if (c.identityStrategy() != p.identityStrategy() || !c.naturalKey().equals(p.naturalKey())) {
                changes.add("identity changed from " + c.identityStrategy() + c.naturalKey() + " to "
                        + p.identityStrategy() + p.naturalKey() + ": existing ids would no longer match");
            }
        }
        if (current instanceof RelationshipTypeDefinition c && proposed instanceof RelationshipTypeDefinition p) {
            c.sourceTypes().stream().filter(t -> !p.allowsSource(t))
                    .forEach(t -> changes.add("source type '" + t + "' removed"));
            c.targetTypes().stream().filter(t -> !p.allowsTarget(t))
                    .forEach(t -> changes.add("target type '" + t + "' removed"));
            if (!c.discriminator().equals(p.discriminator())) {
                changes.add("identity discriminator changed: existing relationship ids would no longer match");
            }
        }
        return changes;
    }

    private static List<String> propertyChanges(Map<String, PropertyDefinition> current,
                                                Map<String, PropertyDefinition> proposed) {
        List<String> changes = new ArrayList<>();
        current.forEach((name, old) -> {
            PropertyDefinition neu = proposed.get(name);
            if (neu == null) {
                changes.add("property '" + name + "' removed (deprecate it instead)");
                return;
            }
            if (old.type() != neu.type() && !(old.type().isTextual() && neu.type().isTextual())) {
                changes.add("property '" + name + "' type changed from " + old.type().json() + " to " + neu.type().json());
            }
            if (!old.required() && neu.required()) {
                changes.add("property '" + name + "' became required");
            }
            if (!neu.enumValues().isEmpty() && (old.enumValues().isEmpty() || !neu.enumValues().containsAll(old.enumValues()))) {
                changes.add("property '" + name + "' allows fewer values than before");
            }
            if (neu.pattern() != null && !neu.pattern().equals(old.pattern())) {
                changes.add("property '" + name + "' has a new pattern that existing values may not match");
            }
        });
        proposed.forEach((name, neu) -> {
            if (!current.containsKey(name) && neu.required()) {
                changes.add("new property '" + name + "' is required (add it as optional, or migrate)");
            }
        });
        return changes;
    }

    /** Compares two semantic versions {@code MAJOR.MINOR.PATCH}. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
