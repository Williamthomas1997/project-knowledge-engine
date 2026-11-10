package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.schema.DefinitionDocument;
import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.EntityTypeDefinition;
import com.omnicore.pke.engine.schema.RelationshipTypeDefinition;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What Cypher may name: registered entity and relationship types and their properties, plus the system graph model
 * (entities, assertions, evidence, versions, sources, sections, questions) and the column aliases templates use.
 */
@Component
public class GraphVocabulary {

    static final Set<String> SYSTEM_LABELS = Set.of("Entity", "Assertion", "Evidence", "EntityVersion", "AssertionVersion",
            "ChangeEvent", "Section", "SourceVersion", "SourceArtifact", "OpenQuestion", "Alias");
    static final Set<String> SYSTEM_RELATIONSHIPS = Set.of("SUPPORTED_BY", "HAS_VERSION", "FROM", "CITES", "ABOUT",
            "HAS_SECTION", "CURRENT_VERSION", "HAS_ALIAS", "CONCERNS");
    static final Set<String> SYSTEM_PROPERTIES = Set.of(
            // evidence
            "sourceArtifactId", "sourceVersion", "sourceVersionId", "location", "contentHash", "extractionMethod",
            "pipelineVersion", "skillVersion", "modelProvider", "modelId", "excerpt", "verification", "confidenceReported",
            "confidenceCalibrated", "epistemic", "scope", "promptHash", "recordedSeq", "recordedAt", "subjectId",
            // sources, sections, versions, events, questions
            "key", "kind", "name", "status", "version", "heading", "headingPath", "order", "text", "artifactKey", "page",
            "level", "hash", "validFromSeq", "validToSeq", "propsJson", "propsHash", "validAt", "invalidAt", "seq", "type",
            "at", "actor", "operationId", "subjectKind", "subjectType", "details", "question", "entityId", "assertionId",
            // column aliases used by templates
            "id", "label", "detail", "depth");

    private final SchemaRegistry schema;

    public GraphVocabulary(SchemaRegistry schema) {
        this.schema = schema;
    }

    public CypherGuard guard(int maxLimit) {
        Set<String> labels = new HashSet<>(SYSTEM_LABELS);
        Set<String> relationships = new HashSet<>(SYSTEM_RELATIONSHIPS);
        Set<String> properties = new HashSet<>(SYSTEM_PROPERTIES);
        for (DefinitionDocument doc : schema.all(DefinitionKind.ENTITY_TYPE)) {
            EntityTypeDefinition type = (EntityTypeDefinition) doc.definition();
            labels.add(type.name());
            properties.addAll(type.properties().keySet());
        }
        for (DefinitionDocument doc : schema.all(DefinitionKind.RELATIONSHIP_TYPE)) {
            RelationshipTypeDefinition type = (RelationshipTypeDefinition) doc.definition();
            relationships.add(type.name());
            properties.addAll(type.properties().keySet());
        }
        return new CypherGuard(labels, relationships, properties, maxLimit);
    }
}
