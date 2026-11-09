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
 * Community are used (ADR-008): node uniqueness constraints and range indexes. Required properties are enforced
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
            "CREATE INDEX pke_entity_type IF NOT EXISTS FOR (n:Entity) ON (n.pke_type)",
            "CREATE INDEX pke_assertion_subject IF NOT EXISTS FOR (n:Assertion) ON (n.pke_subjectId)",
            "CREATE INDEX pke_assertion_object IF NOT EXISTS FOR (n:Assertion) ON (n.pke_objectId)",
            "CREATE INDEX pke_assertion_status IF NOT EXISTS FOR (n:Assertion) ON (n.pke_status)",
            "CREATE INDEX pke_change_event_subject IF NOT EXISTS FOR (n:ChangeEvent) ON (n.subjectId)",
            "CREATE INDEX pke_schema_definition_name IF NOT EXISTS FOR (n:SchemaDefinition) ON (n.kind, n.name)");

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
