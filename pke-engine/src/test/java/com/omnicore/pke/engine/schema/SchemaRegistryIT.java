package com.omnicore.pke.engine.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.graph.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.graph.KnowledgeWriter;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaRegistryIT extends KnowledgeIT {

    static final String REGULATORY_CONTROL = """
            kind: EntityType
            name: RegulatoryControl
            version: 1.0.0
            description: A control required by a regulator
            category: business
            properties:
              controlId: { type: string, required: true }
              title: { type: string, required: true }
            identity: { strategy: naturalKey, naturalKey: [controlId] }
            """;

    static final String REGULATORY_DEPENDENCY = """
            kind: RelationshipType
            name: REGULATORY_DEPENDENCY
            version: 1.0.0
            description: Indicates a dependency on a regulatory control
            category: business
            sourceTypes: [BusinessFeature]
            targetTypes: [RegulatoryControl]
            properties:
              rationale: { type: text }
              verificationStatus: { type: string }
            evidence: { required: true }
            """;

    @Autowired
    KnowledgeWriter writer;

    @Autowired
    SchemaRegistry registry;

    @Test
    void bundledDefinitionsAreRegisteredOnceWithOneEventEach() {
        assertThat(schemaService.list(DefinitionKind.ENTITY_TYPE)).extracting(SchemaService.DefinitionView::name)
                .contains("BusinessRequirement", "JavaMethod", "ApiOperation");
        long definitions = count("MATCH (d:SchemaDefinition) RETURN count(d)");
        assertThat(definitions).isGreaterThanOrEqualTo(15);
        assertThat(count("MATCH (e:ChangeEvent {type: 'SCHEMA_REGISTERED'})-[:ABOUT]->(:SchemaDefinition) RETURN count(e)"))
                .isEqualTo(definitions);
    }

    @Test
    void aNewMetadataOnlyRelationshipTypeWorksWithoutCodeChanges() {
        assertThat(schemaService.register(REGULATORY_CONTROL, Format.YAML, "t").outcome())
                .isEqualTo(SchemaService.Outcome.REGISTERED);
        assertThat(schemaService.register(REGULATORY_DEPENDENCY, Format.YAML, "t").outcome())
                .isEqualTo(SchemaService.Outcome.REGISTERED);

        var result = writer.apply(new ChangeSet(null,
                List.of(new EntityWrite("BusinessFeature", null, Map.of("featureKey", "intl-transfer", "name",
                                "International transfer"), EpistemicStatus.OBSERVED, null, null),
                        new EntityWrite("RegulatoryControl", null, Map.of("controlId", "QCB-AML-7", "title",
                                "Screen beneficiaries"), EpistemicStatus.OBSERVED, null, null)),
                List.of(new RelationshipWrite("REGULATORY_DEPENDENCY",
                        EntityRef.key("BusinessFeature", Map.of("featureKey", "intl-transfer")),
                        EntityRef.key("RegulatoryControl", Map.of("controlId", "QCB-AML-7")),
                        Map.of("rationale", "Transfers abroad must be screened"), EpistemicStatus.HUMAN_VERIFIED, null,
                        List.of(new EvidenceInput("doc:policy", "2026-1", "§4", null, "manual", null, null, null, null,
                                null, "VERIFIED", null, null)))),
                List.of()), "t");

        assertThat(result.items()).extracting(i -> i.outcome()).containsOnly(Outcome.CREATED);
        assertThat(count("MATCH (:Entity:BusinessFeature)-[r:REGULATORY_DEPENDENCY]->(:Entity:RegulatoryControl) RETURN count(r)"))
                .isEqualTo(1);
    }

    @Test
    void versionsAreImmutableAndOlderVersionsAreRefused() {
        schemaService.register(REGULATORY_CONTROL, Format.YAML, "t");
        assertThat(schemaService.register(REGULATORY_CONTROL, Format.YAML, "t").outcome())
                .isEqualTo(SchemaService.Outcome.UNCHANGED);
        assertThatThrownBy(() -> schemaService.register(REGULATORY_CONTROL.replace("A control", "Another control"),
                Format.YAML, "t")).isInstanceOf(ConflictException.class).hasMessageContaining("immutable");

        schemaService.register(REGULATORY_CONTROL.replace("1.0.0", "1.1.0")
                .replace("title: { type: string, required: true }",
                        "title: { type: string, required: true }\n  regulator: { type: string }"), Format.YAML, "t");
        assertThatThrownBy(() -> schemaService.register(REGULATORY_CONTROL.replace("1.0.0", "1.0.1"), Format.YAML, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("older");

        assertThat(schemaService.history(DefinitionKind.ENTITY_TYPE, "RegulatoryControl"))
                .extracting(SchemaService.VersionView::version, SchemaService.VersionView::current)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1.0.0", false),
                        org.assertj.core.groups.Tuple.tuple("1.1.0", true));
        assertThat(registry.entityType("RegulatoryControl").orElseThrow().properties()).containsKey("regulator");
    }

    @Test
    void breakingChangesAreRejectedWithTheReason() {
        schemaService.register(REGULATORY_CONTROL, Format.YAML, "t");
        String removesTitle = REGULATORY_CONTROL.replace("1.0.0", "2.0.0")
                .replace("  title: { type: string, required: true }\n", "");
        assertThatThrownBy(() -> schemaService.register(removesTitle, Format.YAML, "t"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("'title' removed");
        var report = schemaService.validate(removesTitle, Format.YAML);
        assertThat(report.valid()).isFalse();
        assertThat(report.breakingChanges()).isNotEmpty();
    }

    @Test
    void relationshipEndpointsMustBeRegisteredTypes() {
        assertThatThrownBy(() -> schemaService.register(REGULATORY_DEPENDENCY, Format.YAML, "t"))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("'RegulatoryControl' is not a registered entity type");
    }

    @Test
    void registryIsRebuiltFromNeo4jOnReload() {
        schemaService.register(REGULATORY_CONTROL, Format.YAML, "t");
        schemaService.reload();
        assertThat(registry.entityType("RegulatoryControl")).isPresent();
        assertThat(registry.relationshipType("CALLS")).isPresent();
    }
}
