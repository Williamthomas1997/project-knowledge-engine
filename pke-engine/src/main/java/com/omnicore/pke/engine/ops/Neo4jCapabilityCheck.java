package com.omnicore.pke.engine.ops;

import com.omnicore.pke.engine.graph.GraphReader;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Logs the Neo4j version and edition at startup, and stops the application if the server is
 * older than the minimum version the graph representation (ADR-001) relies on.
 */
@Component
@Order(0)
public class Neo4jCapabilityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jCapabilityCheck.class);

    private final GraphReader graphReader;

    public Neo4jCapabilityCheck(GraphReader graphReader) {
        this.graphReader = graphReader;
    }

    @Override
    public void run(ApplicationArguments args) {
        Neo4jServerInfo info = detect();
        log.info("Connected to Neo4j {} ({} edition)", info.version(), info.edition());
        if (!info.isSupported()) {
            throw new IllegalStateException("Neo4j " + info.version() + " is not supported: the engine needs Neo4j "
                    + Neo4jServerInfo.MIN_MAJOR + "." + Neo4jServerInfo.MIN_MINOR
                    + " or newer for dynamic labels and relationship types. Upgrade the server or point NEO4J_URI at a newer one.");
        }
    }

    public Neo4jServerInfo detect() {
        return graphReader.single("""
                        CALL dbms.components() YIELD name, versions, edition
                        WHERE name = 'Neo4j Kernel'
                        RETURN versions[0] AS version, edition""", Map.of())
                .map(row -> new Neo4jServerInfo((String) row.get("version"), (String) row.get("edition")))
                .orElseThrow(() -> new IllegalStateException("dbms.components() returned no 'Neo4j Kernel' entry"));
    }
}
