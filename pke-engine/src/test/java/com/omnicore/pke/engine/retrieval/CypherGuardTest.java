package com.omnicore.pke.engine.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.kernel.Violation;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CypherGuardTest {

    private final CypherGuard guard = new CypherGuard(Set.of("Entity", "JavaMethod", "KafkaTopic"),
            Set.of("CALLS", "PUBLISHES_TO"), Set.of("signature", "fqcn", "name", "id", "label"), 50);

    private static java.util.List<String> messages(CypherGuard.Result result) {
        return result.violations().stream().map(Violation::message).toList();
    }

    @Test
    void aReadOnlyQueryOverRegisteredTypesIsAllowedAndGetsALimit() {
        CypherGuard.Result result = guard.check("""
                MATCH (m:JavaMethod)-[:PUBLISHES_TO]->(t:KafkaTopic) WHERE m.pke_id IN $seedIds
                RETURN t.pke_id AS id, t.name AS label""");
        assertThat(result.allowed()).as(result.violations().toString()).isTrue();
        assertThat(result.cypher()).endsWith("LIMIT 50");
    }

    @Test
    void writeClausesAreRejectedAlsoInLowerCaseButNotInsideStrings() {
        for (String write : new String[] {"MATCH (m:JavaMethod) SET m.name = 'x' RETURN m",
                "match (m:JavaMethod) detach delete m", "CREATE (:JavaMethod {name: 'x'})",
                "MATCH (m:JavaMethod) MERGE (m)-[:CALLS]->(m) RETURN m", "MATCH (m:JavaMethod) REMOVE m.name RETURN m",
                "LOAD CSV FROM 'file:///x' AS row RETURN row", "MATCH (m:JavaMethod) FOREACH (x IN [1] | SET m.name = 'y')",
                "DROP INDEX pke_card_text"}) {
            assertThat(messages(guard.check(write))).as(write).anyMatch(m -> m.contains("is not allowed"));
        }
        assertThat(guard.check("MATCH (m:JavaMethod) WHERE m.name = 'CREATE or DELETE' RETURN m.name AS label").allowed()).isTrue();
    }

    @Test
    void proceduresAndNamespacedFunctionsAreRejectedExceptTheIndexQueries() {
        assertThat(messages(guard.check("CALL dbms.components() YIELD name RETURN name")))
                .anyMatch(m -> m.contains("procedure 'dbms.components' is not allowed"));
        assertThat(messages(guard.check("MATCH (m:JavaMethod) RETURN apoc.text.join([m.name], ',') AS label")))
                .anyMatch(m -> m.contains("namespaced function 'apoc.text.join'"));
        assertThat(messages(guard.check("MATCH (m:JavaMethod) CALL (m) { RETURN 1 AS x } IN TRANSACTIONS RETURN m.name AS label")))
                .anyMatch(m -> m.contains("IN TRANSACTIONS"));
        assertThat(guard.check("CALL db.index.fulltext.queryNodes('pke_entity_text', $q) YIELD node RETURN node.name AS label")
                .allowed()).isTrue();
    }

    @Test
    void unknownLabelsRelationshipTypesAndPropertiesAreRejected() {
        assertThat(messages(guard.check("MATCH (u:User) RETURN u.name AS label")))
                .anyMatch(m -> m.contains("label 'User' is not a registered type"));
        assertThat(messages(guard.check("MATCH (m:JavaMethod)-[:OWNS]->(x) RETURN x.name AS label")))
                .anyMatch(m -> m.contains("relationship type 'OWNS' is not registered"));
        assertThat(messages(guard.check("MATCH (m:JavaMethod) RETURN m.password AS label")))
                .anyMatch(m -> m.contains("property 'password' is not registered"));
        assertThat(messages(guard.check("MATCH (m:`Java Method`) RETURN m.name AS label")))
                .anyMatch(m -> m.contains("backticks"));
        assertThat(messages(guard.check("MATCH (m:JavaMethod) RETURN m; MATCH (n) DETACH DELETE n")))
                .anyMatch(m -> m.contains("only one statement"));
    }

    @Test
    void aLimitAboveTheMaximumIsCappedAndAParameterLimitIsKept() {
        assertThat(guard.check("MATCH (m:JavaMethod) RETURN m.name AS label LIMIT 100000").cypher()).endsWith("LIMIT 50");
        assertThat(guard.check("MATCH (m:JavaMethod) RETURN m.name AS label LIMIT 10").cypher()).endsWith("LIMIT 10");
        assertThat(guard.check("MATCH (m:JavaMethod) RETURN m.name AS label LIMIT $limit").cypher()).endsWith("LIMIT $limit");
    }
}
