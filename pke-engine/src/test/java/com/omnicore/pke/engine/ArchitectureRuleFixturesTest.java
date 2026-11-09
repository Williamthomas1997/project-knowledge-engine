package com.omnicore.pke.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.archfixture.RogueWriter;
import com.omnicore.pke.engine.graph.GraphReader;
import com.omnicore.pke.engine.graph.GraphTransactions;
import com.omnicore.pke.engine.graph.RogueGraphWriter;
import com.omnicore.pke.engine.ops.Neo4jCapabilityCheck;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** Proves the single-writer rules are precise: they fail on deliberate violations and pass on the real classes. */
class ArchitectureRuleFixturesTest {

    @Test
    void driverUseOutsideGraphIsDetected() {
        JavaClasses rogue = new ClassFileImporter().importClasses(RogueWriter.class);
        assertThat(ArchitectureTest.onlyGraphUsesTheDriver.evaluate(rogue).hasViolation()).isTrue();
        assertThat(ArchitectureTest.onlyGraphTransactionsOpensWriteTransactions.evaluate(rogue).hasViolation()).isTrue();
    }

    @Test
    void writeInsideGraphThatBypassesGraphTransactionsIsDetected() {
        JavaClasses rogue = new ClassFileImporter().importClasses(RogueGraphWriter.class);
        assertThat(ArchitectureTest.onlyGraphTransactionsOpensWriteTransactions.evaluate(rogue).hasViolation()).isTrue();
    }

    @Test
    void readersAndTheWriterThemselvesPass() {
        JavaClasses real = new ClassFileImporter().importClasses(GraphReader.class, GraphTransactions.class,
                Neo4jCapabilityCheck.class);
        assertThat(ArchitectureTest.onlyGraphTransactionsOpensWriteTransactions.evaluate(real).hasViolation()).isFalse();
        assertThat(ArchitectureTest.onlyGraphUsesTheDriver.evaluate(real).hasViolation()).isFalse();
    }
}
