package com.omnicore.pke.archfixture;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/** Deliberate violation used by ArchitectureRuleFixturesTest: a non-graph class writing to Neo4j. Never run. */
public class RogueWriter {

    public void write(Driver driver) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> tx.run("CREATE (:Entity {pke_id: 'rogue'})").consume());
        }
    }
}
