package com.omnicore.pke.engine.graph;

import static com.omnicore.pke.engine.support.Demo.calls;
import static com.omnicore.pke.engine.support.Demo.method;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.graph.GraphIntegrityService.IntegrityViolation;
import com.omnicore.pke.engine.graph.GraphIntegrityService.Kind;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** "Every current assertion has exactly one native edge and vice versa", checked against tampered data. */
class GraphIntegrityIT extends KnowledgeIT {

    @Autowired
    KnowledgeWriter writer;

    @Autowired
    GraphIntegrityService integrity;

    @Autowired
    GraphReader reader;

    private String writeCall() {
        return writer.apply(new ChangeSet(null, List.of(method("com.demo.A", "a()"), method("com.demo.B", "b()")),
                List.of(calls("com.demo.A", "a()", "com.demo.B", "b()", "c1")), List.of()), "t").items().get(2).id();
    }

    @Test
    void consistentGraphPasses() {
        writeCall();
        var report = integrity.check();
        assertThat(report.ok()).isTrue();
        assertThat(report.currentAssertions()).isEqualTo(1);
        assertThat(report.knowledgeEdges()).isEqualTo(1);
    }

    @Test
    void detectsAMissingEdge() {
        String id = writeCall();
        runRaw("MATCH ()-[r {pke_relId: $id}]->() DELETE r", Map.of("id", id));
        assertThat(integrity.check().violations()).extracting(IntegrityViolation::kind, IntegrityViolation::relationshipId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Kind.ASSERTION_WITHOUT_EDGE, id));
    }

    @Test
    void detectsDuplicateStrayAndStaleEdges() {
        String id = writeCall();
        runRaw("""
                MATCH (s:Entity)-[r:CALLS {pke_relId: $id}]->(o:Entity)
                CREATE (s)-[:CALLS {pke_relId: $id}]->(o)
                CREATE (o)-[:CALLS {pke_relId: 'stray'}]->(s)""", Map.of("id", id));
        assertThat(integrity.check().violations()).extracting(IntegrityViolation::kind)
                .contains(Kind.DUPLICATE_EDGE, Kind.EDGE_WITHOUT_ASSERTION);

        runRaw("MATCH (a:Assertion {pke_id: $id}) SET a.pke_status = 'INVALID'", Map.of("id", id));
        assertThat(integrity.check().violations()).extracting(IntegrityViolation::kind)
                .contains(Kind.EDGE_FOR_INVALID_ASSERTION);
    }

    @Test
    void graphReaderCannotWrite() {
        assertThatThrownBy(() -> reader.query("CREATE (:Entity {pke_id: 'sneaky'})", Map.of()))
                .isInstanceOf(org.neo4j.driver.exceptions.ClientException.class);
        assertThat(count("MATCH (n:Entity {pke_id: 'sneaky'}) RETURN count(n)")).isZero();
    }
}
