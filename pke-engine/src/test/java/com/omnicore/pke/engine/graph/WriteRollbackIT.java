package com.omnicore.pke.engine.graph;

import static com.omnicore.pke.engine.support.Demo.calls;
import static com.omnicore.pke.engine.support.Demo.method;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.support.KnowledgeIT;
import com.omnicore.pke.engine.support.TestFaultInjector.InjectedFailure;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A failure at any point of a write transaction rolls back everything it wrote: entities, the native edge, the
 * assertion, evidence, versions, change events and the sequence. Uses a real Neo4j, not mocks.
 */
class WriteRollbackIT extends KnowledgeIT {

    @Autowired
    KnowledgeWriter writer;

    @Autowired
    GraphIntegrityService integrity;

    private static ChangeSet slice() {
        return new ChangeSet(null,
                List.of(method("com.demo.A", "a()"), method("com.demo.B", "b()")),
                List.of(calls("com.demo.A", "a()", "com.demo.B", "b()", "c1")),
                List.of());
    }

    @ParameterizedTest
    @EnumSource(WriteStage.class)
    void aFailureAtAnyStageLeavesNoTrace(WriteStage stage) {
        var before = footprint();
        faults.failAt(stage);

        assertThatThrownBy(() -> writer.apply(slice(), "t")).isInstanceOf(InjectedFailure.class)
                .hasMessageContaining(stage.name());

        assertThat(footprint()).isEqualTo(before);
        assertThat(integrity.check().ok()).isTrue();

        // nothing half-written blocks a clean retry
        faults.reset();
        var result = writer.apply(slice(), "t");
        assertThat(result.eventCount()).isEqualTo(3);
        assertThat(result.firstSeq()).isEqualTo(before.get("seq") + 1);
    }
}
