package com.omnicore.pke.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.kernel.CypherIdentifiers;
import com.omnicore.pke.engine.kernel.InvalidIdentifierException;
import com.omnicore.pke.engine.support.Neo4jTestDatabase;
import com.omnicore.pke.engine.support.RequiresNeo4j;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

/**
 * Proves the Neo4j capability ADR-001 depends on: labels and relationship types chosen at runtime
 * (from the schema registry) are passed as query parameters, never concatenated into Cypher.
 */
@RequiresNeo4j
class Neo4jDynamicLabelIT {

    @Test
    void dynamicLabelAndRelationshipTypeArePassedAsParameters() {
        String label = CypherIdentifiers.label("BusinessRequirement");
        String targetLabel = CypherIdentifiers.label("JavaMethod");
        String relType = CypherIdentifiers.relationshipType("IMPLEMENTS_REQUIREMENT");

        try (var session = Neo4jTestDatabase.driver().session(Neo4jTestDatabase.sessionConfig())) {
            session.executeWrite(tx -> tx.run("""
                    CREATE (a:Entity {pke_id: 'req-1'}) SET a:$($label)
                    CREATE (b:Entity {pke_id: 'method-1'}) SET b:$($targetLabel)
                    WITH a, b
                    MERGE (b)-[r:$($relType)]->(a)
                    SET r.relId = 'rel-1'
                    """, Map.of("label", label, "targetLabel", targetLabel, "relType", relType)).consume());

            Record row = session.executeRead(tx -> tx.run("""
                    MATCH (b:Entity {pke_id: 'method-1'})-[r]->(a:Entity {pke_id: 'req-1'})
                    RETURN labels(a) AS sourceLabels, labels(b) AS targetLabels, type(r) AS relType, r.relId AS relId
                    """).single());

            assertThat(row.get("sourceLabels").asList(Value::asString)).containsExactlyInAnyOrder("Entity", label);
            assertThat(row.get("targetLabels").asList(Value::asString)).containsExactlyInAnyOrder("Entity", targetLabel);
            assertThat(row.get("relType").asString()).isEqualTo(relType);
            assertThat(row.get("relId").asString()).isEqualTo("rel-1");

            // MERGE with a dynamic type is idempotent: running it again must not create a second edge.
            session.executeWrite(tx -> tx.run("""
                    MATCH (a:Entity {pke_id: 'req-1'}), (b:Entity {pke_id: 'method-1'})
                    MERGE (b)-[:$($relType)]->(a)
                    """, Map.of("relType", relType)).consume());
            long edges = session.executeRead(tx -> tx.run(
                    "MATCH (:Entity {pke_id: 'method-1'})-[r:$($relType)]->() RETURN count(r) AS c",
                    Map.of("relType", relType)).single().get("c").asLong());
            assertThat(edges).isEqualTo(1);

            // Labels can be matched dynamically too, which is how typed queries will work.
            long matched = session.executeRead(tx -> tx.run(
                    "MATCH (n:$($label)) RETURN count(n) AS c", Map.of("label", label)).single().get("c").asLong());
            assertThat(matched).isEqualTo(1);
        }
    }

    @Test
    void injectionAttemptNeverReachesTheDatabase() {
        assertThatThrownBy(() -> CypherIdentifiers.label("Foo) DETACH DELETE n //"))
                .isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> CypherIdentifiers.relationshipType("CALLS]->() DELETE r //"))
                .isInstanceOf(InvalidIdentifierException.class);
    }
}
