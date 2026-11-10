package com.omnicore.pke.engine.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every execution guard of ADR-007 §7 step 4 against a real database (the static guards are in CypherGuardTest). */
class GuardedCypherIT extends KnowledgeIT {

    @Autowired
    GuardedCypher cypher;

    @Test
    void aWriteIsRejectedByTheGuardAndAlsoByTheReadOnlyTransaction() {
        assertThatThrownBy(() -> cypher.run("CREATE (:JavaMethod {signature: 'x()'})", Map.of(), "test"))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("rejected by the guard");
        // even if the static guard were bypassed, the read-only transaction refuses the write
        assertThatThrownBy(() -> cypher.execute("CREATE (:JavaMethod {signature: 'x()'})", Map.of(), "test", Duration.ofSeconds(5)))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(count("MATCH (n:JavaMethod) RETURN count(n)")).isZero();
    }

    @Test
    void anUnknownLabelIsRejectedBeforeExecution() {
        assertThatThrownBy(() -> cypher.run("MATCH (u:User) RETURN u.signature AS label", Map.of(), "test"))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations().toString())
                        .contains("label 'User' is not a registered type"));
    }

    @Test
    void explainMustSucceed() {
        assertThatThrownBy(() -> cypher.run("MATCH (m:JavaMethod) RETURN m.signature AS label ORDER BY", Map.of(), "test"))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations().toString())
                        .contains("EXPLAIN failed"));
    }

    @Test
    void theLimitIsInjectedAndApplied() {
        GuardedCypher.Execution execution = cypher.run("UNWIND range(1, 1000) AS x RETURN x AS id", Map.of(), "test");
        assertThat(execution.cypher()).endsWith("LIMIT 200");
        assertThat(execution.rows()).hasSize(200);
    }

    @Test
    void theTimeoutIsEnforced() {
        assertThatThrownBy(() -> cypher.run("UNWIND range(1, 500000000) AS x WITH x WHERE x < 0 RETURN x AS id",
                Map.of(), "test", Duration.ofMillis(500)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations().toString())
                        .contains("timed out after PT0.5S"));
    }
}
