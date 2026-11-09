package com.omnicore.pke.engine.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * Deliberate violation used by ArchitectureRuleFixturesTest: a class inside {@code graph} that bypasses
 * GraphTransactions (so its writes would have no history). Never run.
 */
public class RogueGraphWriter {

    public void autoCommit(Driver driver) {
        try (Session session = driver.session()) {
            session.run("CREATE (:Entity {pke_id: 'rogue'})").consume();
        }
    }
}
