package com.omnicore.pke.engine.graph;

import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opens driver sessions on the configured knowledge database. Package-private use only. */
@Component
class GraphSessions {

    private final Driver driver;
    private final String database;

    GraphSessions(Driver driver, @Value("${spring.data.neo4j.database:neo4j}") String database) {
        this.driver = driver;
        this.database = database;
    }

    Session read() {
        return driver.session(SessionConfig.builder().withDatabase(database).withDefaultAccessMode(AccessMode.READ).build());
    }

    Session write() {
        return driver.session(SessionConfig.builder().withDatabase(database).withDefaultAccessMode(AccessMode.WRITE).build());
    }
}
