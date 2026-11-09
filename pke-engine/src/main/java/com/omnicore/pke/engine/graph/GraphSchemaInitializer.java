package com.omnicore.pke.engine.graph;

import java.util.List;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the engine's constraints and indexes at startup (idempotent). Only features available in Neo4j
 * Community are used (ADR-008): node uniqueness constraints, range indexes and a fulltext index. Required properties are enforced
 * by the schema validator, not by existence or type constraints.
 */
@Component
@Order(10)
public class GraphSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphSchemaInitializer.class);

    static final List<String> STATEMENTS = List.of(
            "CREATE CONSTRAINT pke_entity_id IF NOT EXISTS FOR (n:Entity) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_assertion_id IF NOT EXISTS FOR (n:Assertion) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_evidence_id IF NOT EXISTS FOR (n:Evidence) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_entity_version_id IF NOT EXISTS FOR (n:EntityVersion) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_assertion_version_id IF NOT EXISTS FOR (n:AssertionVersion) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_change_event_seq IF NOT EXISTS FOR (n:ChangeEvent) REQUIRE n.seq IS UNIQUE",
            "CREATE CONSTRAINT pke_schema_definition_id IF NOT EXISTS FOR (n:SchemaDefinition) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_sequence_name IF NOT EXISTS FOR (n:Sequence) REQUIRE n.name IS UNIQUE",
            "CREATE CONSTRAINT pke_alias_id IF NOT EXISTS FOR (n:Alias) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_source_artifact_id IF NOT EXISTS FOR (n:SourceArtifact) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_source_version_id IF NOT EXISTS FOR (n:SourceVersion) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_section_id IF NOT EXISTS FOR (n:Section) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_ingestion_run_id IF NOT EXISTS FOR (n:IngestionRun) REQUIRE n.pke_id IS UNIQUE",
            "CREATE INDEX pke_entity_type IF NOT EXISTS FOR (n:Entity) ON (n.pke_type)",
            "CREATE INDEX pke_assertion_subject IF NOT EXISTS FOR (n:Assertion) ON (n.pke_subjectId)",
            "CREATE INDEX pke_assertion_object IF NOT EXISTS FOR (n:Assertion) ON (n.pke_objectId)",
            "CREATE INDEX pke_assertion_status IF NOT EXISTS FOR (n:Assertion) ON (n.pke_status)",
            "CREATE INDEX pke_change_event_subject IF NOT EXISTS FOR (n:ChangeEvent) ON (n.subjectId)",
            "CREATE INDEX pke_schema_definition_name IF NOT EXISTS FOR (n:SchemaDefinition) ON (n.kind, n.name)",
            "CREATE INDEX pke_evidence_scope IF NOT EXISTS FOR (n:Evidence) ON (n.sourceArtifactId, n.scope)",
            "CREATE INDEX pke_source_artifact_key IF NOT EXISTS FOR (n:SourceArtifact) ON (n.key)",
            "CREATE INDEX pke_ingestion_run_created IF NOT EXISTS FOR (n:IngestionRun) ON (n.createdAt)",
            "CREATE FULLTEXT INDEX pke_section_text IF NOT EXISTS FOR (n:Section) ON EACH [n.heading, n.text]",
            // M3: AI layer, skills, pipelines, knowledge cards
            "CREATE CONSTRAINT pke_model_call_id IF NOT EXISTS FOR (n:ModelCall) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_skill_definition_id IF NOT EXISTS FOR (n:SkillDefinition) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_pipeline_definition_id IF NOT EXISTS FOR (n:PipelineDefinition) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_pipeline_execution_id IF NOT EXISTS FOR (n:PipelineExecution) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_step_execution_id IF NOT EXISTS FOR (n:StepExecution) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_step_memo_id IF NOT EXISTS FOR (n:StepMemo) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_open_question_id IF NOT EXISTS FOR (n:OpenQuestion) REQUIRE n.pke_id IS UNIQUE",
            "CREATE CONSTRAINT pke_fingerprint_id IF NOT EXISTS FOR (n:Fingerprint) REQUIRE n.pke_id IS UNIQUE",
            "CREATE INDEX pke_pipeline_execution_key IF NOT EXISTS FOR (n:PipelineExecution) ON (n.idempotencyKey)",
            "CREATE INDEX pke_model_call_execution IF NOT EXISTS FOR (n:ModelCall) ON (n.executionId)",
            "CREATE INDEX pke_open_question_status IF NOT EXISTS FOR (n:OpenQuestion) ON (n.status)",
            "CREATE FULLTEXT INDEX pke_card_text IF NOT EXISTS FOR (n:KnowledgeCard) ON EACH [n.text]");

    private final GraphSessions sessions;

    GraphSchemaInitializer(GraphSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Session session = sessions.write()) {
            for (String statement : STATEMENTS) {
                session.run(statement).consume();
            }
            session.run("CALL db.awaitIndexes(120)").consume();
        }
        log.info("Ensured {} Neo4j constraints and indexes", STATEMENTS.size());
    }
}
