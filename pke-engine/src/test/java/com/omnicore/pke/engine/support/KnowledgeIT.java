package com.omnicore.pke.engine.support;

import com.omnicore.pke.engine.pipeline.PipelineBootstrap;
import com.omnicore.pke.engine.schema.SchemaService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests against a real Neo4j. All subclasses share one Spring context (same configuration),
 * and every test starts from an empty database with the bundled schema definitions, skills and pipelines
 * registered. AI answers come from recorded fixtures ({@code replay} profile, {@code src/test/resources/ai-fixtures}).
 */
@RequiresNeo4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("replay")
@Import({TestFaultInjector.class, TestPipelineSupport.class})
public abstract class KnowledgeIT {

    @DynamicPropertySource
    static void neo4j(DynamicPropertyRegistry registry) {
        Neo4jTestDatabase.registerSpringProperties(registry);
        registry.add("pke.ingestion.route-export-files", () -> DemoCorpus.file("routes/ocs_t_gateway_api_route.sql").toString());
        registry.add("pke.data-dir", () -> System.getProperty("java.io.tmpdir") + "/pke-it-data");
        registry.add("pke.pipelines.locations",
                () -> "classpath*:definitions/pipelines/*.yaml,classpath*:test-pipelines/*.yaml");
    }

    @Autowired
    protected SchemaService schemaService;

    @Autowired
    protected TestFaultInjector faults;

    @Autowired
    protected TestPipelineSupport.PipelineFaults pipelineFaults;

    @Autowired
    private PipelineBootstrap pipelineBootstrap;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void resetKnowledge() {
        faults.reset();
        pipelineFaults.reset();
        Neo4jTestDatabase.wipe();
        schemaService.bootstrap();
        pipelineBootstrap.load();
    }

    /** Runs a count query directly against the test database (bypassing the engine). */
    protected static long count(String cypher) {
        return count(cypher, Map.of());
    }

    protected static long count(String cypher, Map<String, Object> params) {
        try (var session = Neo4jTestDatabase.driver().session(Neo4jTestDatabase.sessionConfig())) {
            return session.run(cypher, params).single().get(0).asLong();
        }
    }

    /** Runs a read query directly against the test database and returns the rows as plain Java values. */
    protected static java.util.List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        try (var session = Neo4jTestDatabase.driver().session(Neo4jTestDatabase.sessionConfig())) {
            return session.run(cypher, params).list(r -> r.asMap());
        }
    }

    /** Runs a statement directly against the test database, e.g. to tamper with data in integrity tests. */
    protected static void runRaw(String cypher, Map<String, Object> params) {
        try (var session = Neo4jTestDatabase.driver().session(Neo4jTestDatabase.sessionConfig())) {
            session.run(cypher, params).consume();
        }
    }

    /** Counts of every node kind the writer creates, for before/after comparisons. */
    protected static Map<String, Long> footprint() {
        return Map.of(
                "Entity", count("MATCH (n:Entity) RETURN count(n)"),
                "Assertion", count("MATCH (n:Assertion) RETURN count(n)"),
                "Evidence", count("MATCH (n:Evidence) RETURN count(n)"),
                "EntityVersion", count("MATCH (n:EntityVersion) RETURN count(n)"),
                "AssertionVersion", count("MATCH (n:AssertionVersion) RETURN count(n)"),
                "ChangeEvent", count("MATCH (n:ChangeEvent) RETURN count(n)"),
                "knowledgeEdges", count("MATCH ()-[r]->() WHERE r.pke_relId IS NOT NULL RETURN count(r)"),
                "seq", count("OPTIONAL MATCH (s:Sequence {name: 'knowledge'}) RETURN coalesce(s.value, 0)"));
    }
}
