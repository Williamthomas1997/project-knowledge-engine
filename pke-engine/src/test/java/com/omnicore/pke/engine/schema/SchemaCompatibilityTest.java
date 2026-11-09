package com.omnicore.pke.engine.schema;

import static com.omnicore.pke.engine.schema.SchemaTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SchemaCompatibilityTest {

    private static final String V1 = """
            kind: EntityType
            name: Thing
            version: 1.0.0
            description: x
            properties:
              key: { type: string, required: true }
              colour: { type: string, enum: [red, green] }
              size: { type: integer }
            identity: { strategy: naturalKey, naturalKey: [key] }
            """;

    private static TypeDefinition def(String yaml) {
        return parse(yaml).definition();
    }

    @Test
    void additiveChangesAreCompatible() {
        String v11 = V1.replace("1.0.0", "1.1.0")
                .replace("[red, green]", "[red, green, blue]")
                .replace("size: { type: integer }", "size: { type: integer }\n  note: { type: text }");
        assertThat(SchemaCompatibility.breakingChanges(def(V1), def(v11))).isEmpty();
    }

    @Test
    void destructiveChangesAreReported() {
        String v2 = """
                kind: EntityType
                name: Thing
                version: 2.0.0
                description: x
                properties:
                  key: { type: string, required: true }
                  colour: { type: string, enum: [red] }
                  extra: { type: string, required: true }
                identity: { strategy: generated }
                """;
        assertThat(SchemaCompatibility.breakingChanges(def(V1), def(v2)))
                .anyMatch(c -> c.contains("'size' removed"))
                .anyMatch(c -> c.contains("'colour' allows fewer values"))
                .anyMatch(c -> c.contains("'extra' is required"))
                .anyMatch(c -> c.contains("identity changed"));
    }

    @Test
    void narrowingRelationshipEndpointsIsBreaking() {
        String r1 = """
                kind: RelationshipType
                name: LINKS
                version: 1.0.0
                description: x
                category: technical
                sourceTypes: [Aa, Bb]
                targetTypes: ["*"]
                """;
        String r2 = r1.replace("1.0.0", "1.1.0").replace("[Aa, Bb]", "[Aa]").replace("[\"*\"]", "[Cc]");
        assertThat(SchemaCompatibility.breakingChanges(def(r1), def(r2)))
                .anyMatch(c -> c.contains("source type 'Bb' removed"))
                .anyMatch(c -> c.contains("target type '*' removed"));
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(SchemaCompatibility.compareVersions("1.10.0", "1.9.9")).isPositive();
        assertThat(SchemaCompatibility.compareVersions("2.0.0", "2.0.0")).isZero();
        assertThat(SchemaCompatibility.compareVersions("0.9.0", "1.0.0")).isNegative();
    }
}
