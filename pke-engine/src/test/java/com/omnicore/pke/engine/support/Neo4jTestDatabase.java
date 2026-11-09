package com.omnicore.pke.engine.support;

import java.util.Map;
import java.util.Set;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Picks the Neo4j used by integration tests, once per JVM:
 * <ol>
 *   <li>Testcontainers, if a Docker daemon is reachable (image from {@code pke.test.neo4j.image}).</li>
 *   <li>An existing server (e.g. Neo4j Desktop) from {@code NEO4J_TEST_URI}, {@code NEO4J_TEST_USERNAME},
 *       {@code NEO4J_TEST_PASSWORD} and {@code NEO4J_TEST_DATABASE}. The database must be a dedicated test
 *       database: it is wiped before each test class.</li>
 *   <li>Otherwise none: tests annotated with {@link RequiresNeo4j} are reported as skipped, with the reason.</li>
 * </ol>
 * {@code PKE_TEST_NEO4J_MODE} (or {@code -Dpke.test.neo4j.mode}) overrides the choice:
 * {@code auto} (default), {@code testcontainers}, {@code external} or {@code none}.
 */
public final class Neo4jTestDatabase {

    private static final String DEFAULT_IMAGE = "neo4j:2026.10.1-community";
    private static final Set<String> PROTECTED_DATABASES = Set.of("neo4j", "system");

    public enum Mode { TESTCONTAINERS, EXTERNAL, UNAVAILABLE }

    public record Connection(Mode mode, String uri, String username, String password, String database,
                             String description) {
    }

    private static Connection connection;
    private static Driver driver;

    private Neo4jTestDatabase() {
    }

    public static synchronized Connection connection() {
        if (connection == null) {
            connection = resolve();
        }
        return connection;
    }

    public static boolean available() {
        return connection().mode() != Mode.UNAVAILABLE;
    }

    public static synchronized Driver driver() {
        if (!available()) {
            throw new IllegalStateException(connection().description());
        }
        if (driver == null) {
            Connection c = connection();
            driver = GraphDatabase.driver(c.uri(), AuthTokens.basic(c.username(), c.password()));
            driver.verifyConnectivity();
            Runtime.getRuntime().addShutdownHook(new Thread(driver::close));
        }
        return driver;
    }

    public static SessionConfig sessionConfig() {
        return SessionConfig.forDatabase(connection().database());
    }

    /**
     * Deletes all data in the test database. On an external server it never runs against {@code neo4j}
     * or {@code system}; a throwaway container's default database is fine to wipe.
     */
    public static void wipe() {
        String database = connection().database();
        if (connection().mode() == Mode.EXTERNAL && PROTECTED_DATABASES.contains(database)) {
            throw new IllegalStateException("Refusing to wipe database '" + database + "'");
        }
        try (var session = driver().session(sessionConfig())) {
            session.run("MATCH (n) CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 1000 ROWS").consume();
        }
    }

    public static void registerSpringProperties(DynamicPropertyRegistry registry) {
        Connection c = connection();
        registry.add("spring.neo4j.uri", c::uri);
        registry.add("spring.neo4j.authentication.username", c::username);
        registry.add("spring.neo4j.authentication.password", c::password);
        registry.add("spring.data.neo4j.database", c::database);
    }

    private static Connection resolve() {
        String mode = setting("pke.test.neo4j.mode", "PKE_TEST_NEO4J_MODE", "auto");
        return switch (mode) {
            case "auto" -> dockerAvailable() ? startContainer() : external();
            case "testcontainers" -> startContainer();
            case "external" -> external();
            case "none" -> new Connection(Mode.UNAVAILABLE, null, null, null, null,
                    "Neo4j integration tests disabled (PKE_TEST_NEO4J_MODE=none).");
            default -> throw new IllegalStateException(
                    "PKE_TEST_NEO4J_MODE must be auto, testcontainers, external or none, not '" + mode + "'");
        };
    }

    private static Connection startContainer() {
        String image = System.getProperty("pke.test.neo4j.image", DEFAULT_IMAGE);
        Neo4jContainer container = new Neo4jContainer(DockerImageName.parse(image))
                .withAdminPassword("pke-test-password");
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return new Connection(Mode.TESTCONTAINERS, container.getBoltUrl(), "neo4j", container.getAdminPassword(),
                "neo4j", "Testcontainers " + image);
    }

    private static Connection external() {
        Map<String, String> env = System.getenv();
        String uri = env.get("NEO4J_TEST_URI");
        if (uri == null || uri.isBlank()) {
            return new Connection(Mode.UNAVAILABLE, null, null, null, null,
                    "No Neo4j for integration tests: Docker is not reachable and NEO4J_TEST_URI is not set. "
                            + "Start Docker, or point NEO4J_TEST_URI/USERNAME/PASSWORD/DATABASE at a dedicated test database.");
        }
        String database = env.getOrDefault("NEO4J_TEST_DATABASE", "pketest");
        if (PROTECTED_DATABASES.contains(database)) {
            throw new IllegalStateException("NEO4J_TEST_DATABASE must be a dedicated test database, not '" + database
                    + "': integration tests delete all of its data.");
        }
        Connection external = new Connection(Mode.EXTERNAL, uri, env.getOrDefault("NEO4J_TEST_USERNAME", "neo4j"),
                env.getOrDefault("NEO4J_TEST_PASSWORD", ""), database, "External Neo4j " + uri + " database " + database);
        ensureDatabaseExists(external);
        return external;
    }

    private static void ensureDatabaseExists(Connection c) {
        try (Driver d = GraphDatabase.driver(c.uri(), AuthTokens.basic(c.username(), c.password()));
             var session = d.session(SessionConfig.forDatabase("system"))) {
            session.run("CREATE DATABASE $name IF NOT EXISTS WAIT", Map.of("name", c.database())).consume();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not create or find test database '" + c.database() + "' on " + c.uri()
                    + ". Create it in Neo4j Desktop (Enterprise) or use Docker. Cause: " + e.getMessage(), e);
        }
    }

    private static String setting(String property, String envVar, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim().toLowerCase();
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
