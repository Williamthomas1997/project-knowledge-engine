package com.omnicore.pke.engine.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.stereotype.Component;

/**
 * Read-only access to Neo4j for every package other than {@code graph}. Queries run in READ-mode transactions,
 * so the database itself rejects any write attempted through this class. Results are plain Java values
 * (maps, lists, strings, numbers, {@code java.time} types).
 */
@Component
public class GraphReader {

    private final GraphSessions sessions;

    GraphReader(GraphSessions sessions) {
        this.sessions = sessions;
    }

    public List<Map<String, Object>> query(String cypher, Map<String, Object> parameters) {
        try (Session session = sessions.read()) {
            return session.executeRead(tx -> tx.run(cypher, parameters).list(r -> r.asMap()));
        }
    }

    /**
     * Runs a read query with a server-side timeout: the transaction is terminated when it runs longer
     * (used for guarded, LLM-written Cypher and query templates).
     */
    public List<Map<String, Object>> query(String cypher, Map<String, Object> parameters, Duration timeout) {
        try (Session session = sessions.read()) {
            return session.executeRead(tx -> tx.run(cypher, parameters).list(r -> r.asMap()),
                    TransactionConfig.builder().withTimeout(timeout).build());
        }
    }

    public Optional<Map<String, Object>> single(String cypher, Map<String, Object> parameters) {
        List<Map<String, Object>> rows = query(cypher, parameters);
        if (rows.size() > 1) {
            throw new IllegalStateException("Expected at most one row but got " + rows.size());
        }
        return rows.stream().findFirst();
    }
}
