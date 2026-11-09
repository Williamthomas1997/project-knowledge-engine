package com.omnicore.pke.engine.schema;

import static com.omnicore.pke.engine.schema.SchemaTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemaDefinitionParserTest {

    private static final String REQUIREMENT = """
            kind: EntityType
            name: Requirement
            version: 1.0.0
            description: A requirement
            properties:
              docId: { type: string, required: true }
              reqId: { type: string, required: true }
              text: { type: text }
            identity: { strategy: naturalKey, naturalKey: [docId, reqId] }
            embedding: { indexFields: [text] }
            """;

    @Test
    void everyBundledDefinitionIsValid() {
        List<DefinitionSource.SourceFile> files = new DefinitionSource("").schemaFiles();
        assertThat(files).hasSizeGreaterThanOrEqualTo(15);
        files.forEach(f -> assertThat(SchemaTestSupport.PARSER.parse(f.text(), f.format()).definition()).isNotNull());
    }

    @Test
    void parsesAnEntityTypeWithIdentityAndEmbedding() {
        DefinitionDocument doc = parse(REQUIREMENT);
        EntityTypeDefinition def = (EntityTypeDefinition) doc.definition();
        assertThat(def.name()).isEqualTo("Requirement");
        assertThat(def.properties()).containsOnlyKeys("docId", "reqId", "text");
        assertThat(def.properties().keySet()).containsExactly("docId", "reqId", "text");
        assertThat(def.identityStrategy()).isEqualTo(EntityTypeDefinition.IdentityStrategy.NATURAL_KEY);
        assertThat(def.naturalKey()).containsExactly("docId", "reqId");
        assertThat(def.embedding().indexFields()).containsExactly("text");
        assertThat(doc.key()).isEqualTo("EntityType/Requirement/1.0.0");
    }

    @Test
    void yamlAndJsonOfTheSameDefinitionHaveTheSameHash() {
        String json = """
                {"kind":"EntityType","name":"Requirement","version":"1.0.0","description":"A requirement",
                 "properties":{"docId":{"type":"string","required":true},"reqId":{"type":"string","required":true},
                 "text":{"type":"text"}},
                 "embedding":{"indexFields":["text"]},
                 "identity":{"naturalKey":["docId","reqId"],"strategy":"naturalKey"}}""";
        assertThat(SchemaTestSupport.PARSER.parse(json, Format.JSON).hash()).isEqualTo(parse(REQUIREMENT).hash());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "lower-case name           | name: Requirement           | name: requirement           | /name",
            "missing description       | 'description: A requirement' | 'displayName: x'          | $",
            "bad version               | version: 1.0.0              | version: v1                 | /version",
            "unknown property type     | 'text: { type: text }'      | 'text: { type: blob }'      | /properties/text/type",
            "system property name      | 'text: { type: text }'      | 'pke_text: { type: text }'  | /properties",
            "unknown top-level key     | 'embedding: { indexFields: [text] }' | 'colour: red'      | $"
    })
    void metaSchemaRejectsMalformedDefinitions(String label, String from, String to, String path) {
        String yaml = REQUIREMENT.replace(from.strip(), to.strip());
        assertThat(yaml).isNotEqualTo(REQUIREMENT);
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).violations())
                        .extracting(Violation::path).anyMatch(p -> p.startsWith(path)));
    }

    @Test
    void semanticRulesBeyondTheMetaSchemaAreChecked() {
        String yaml = """
                kind: EntityType
                name: Entity
                version: 1.0.0
                description: Uses a reserved label and inconsistent keys
                properties:
                  key: { type: string }
                  count: { type: integer, enum: [one] }
                  code: { type: string, pattern: "([" }
                identity: { strategy: naturalKey, naturalKey: [key, missing] }
                embedding: { indexFields: [count] }
                retrieval: { displayProperty: nothing }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).violations()).extracting(Violation::message)
                        .anyMatch(m -> m.contains("reserved"))
                        .anyMatch(m -> m.contains("'key' must be a required property"))
                        .anyMatch(m -> m.contains("'missing' is not a declared property"))
                        .anyMatch(m -> m.contains("enum is only allowed"))
                        .anyMatch(m -> m.contains("not a valid regular expression"))
                        .anyMatch(m -> m.contains("'count' must be a declared string or text property"))
                        .anyMatch(m -> m.contains("'nothing' is not a declared property")));
    }

    @Test
    void relationshipTypesNeedACategoryAndValidEndpoints() {
        String yaml = """
                kind: RelationshipType
                name: SECURED_BY
                version: 1.0.0
                description: x
                sourceTypes: [ApiOperation]
                targetTypes: [bad-type]
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).violations()).extracting(Violation::path)
                        .anyMatch(p -> p.equals("$") || p.startsWith("/category"))
                        .anyMatch(p -> p.startsWith("/targetTypes")));
    }

    @Test
    void reservedRelationshipTypesAreRejected() {
        String yaml = """
                kind: RelationshipType
                name: SUPPORTED_BY
                version: 1.0.0
                description: x
                category: technical
                sourceTypes: ["*"]
                targetTypes: ["*"]
                """;
        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(ValidationFailedException.class).hasMessageContaining("reserved");
    }

    @Test
    void unparseableInputIsAValidationFailure() {
        assertThatThrownBy(() -> SchemaTestSupport.PARSER.parse("{not json", Format.JSON))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> parse("kind: Unknown\nname: X"))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("/kind");
    }
}
