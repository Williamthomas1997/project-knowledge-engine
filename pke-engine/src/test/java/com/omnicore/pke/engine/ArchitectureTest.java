package com.omnicore.pke.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/**
 * Package boundaries from IMPLEMENTATION_PLAN.md §1 and the single-writer rule (ADR-009), enforced rather than
 * left to convention. {@code ArchitectureRuleFixturesTest} proves the write rules fail on deliberate violations.
 */
@AnalyzeClasses(packages = "com.omnicore.pke.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ENGINE = "com.omnicore.pke.engine";
    private static final String GRAPH = ENGINE + ".graph";

    /** Driver entry points that open a write-capable transaction or run auto-commit queries. */
    private static final Set<String> WRITE_ENTRY_POINTS = Set.of(
            "executeWrite", "executeWriteWithoutResult", "run", "beginTransaction", "executableQuery");
    private static final Set<String> DRIVER_SESSION_TYPES = Set.of(
            "org.neo4j.driver.Session", "org.neo4j.driver.QueryRunner", "org.neo4j.driver.Driver",
            "org.neo4j.driver.async.AsyncSession", "org.neo4j.driver.reactive.ReactiveSession",
            "org.neo4j.driver.reactivestreams.ReactiveSession");

    static final DescribedPredicate<JavaMethodCall> DRIVER_WRITE_ENTRY_POINT = DescribedPredicate.describe(
            "a Neo4j driver write entry point " + WRITE_ENTRY_POINTS,
            call -> WRITE_ENTRY_POINTS.contains(call.getName())
                    && DRIVER_SESSION_TYPES.contains(call.getTargetOwner().getName()));

    @ArchTest
    static final ArchRule onlyGraphUsesTheDriver = noClasses()
            .that().resideOutsideOfPackage(GRAPH + "..")
            .should().dependOnClassesThat().resideInAnyPackage("org.neo4j.driver..", "org.springframework.data.neo4j..")
            .because("only the graph package talks to Neo4j; other packages read through GraphReader (read-only)");

    @ArchTest
    static final ArchRule onlyGraphTransactionsOpensWriteTransactions = noClasses()
            .that().doNotHaveFullyQualifiedName(GRAPH + ".GraphTransactions")
            .and().doNotHaveFullyQualifiedName(GRAPH + ".GraphSchemaInitializer")
            .should().callMethodWhere(DRIVER_WRITE_ENTRY_POINT)
            .because("every knowledge write goes through GraphTransactions, which records history in the same "
                    + "transaction; only GraphSchemaInitializer may run auto-commit schema commands");

    @ArchTest
    static final ArchRule coreDoesNotDependOnApiAdapters = noClasses()
            .that().resideOutsideOfPackage(ENGINE + ".api..")
            .and().doNotHaveFullyQualifiedName(ENGINE + ".PkeApplication")
            .should().dependOnClassesThat().resideInAPackage(ENGINE + ".api..")
            .because("REST and MCP are thin adapters; the core must not know about them");

    @ArchTest
    static final ArchRule schemaDoesNotDependOnGraph = noClasses()
            .that().resideInAPackage(ENGINE + ".schema..")
            .should().dependOnClassesThat().resideInAPackage(GRAPH + "..")
            .because("schema persistence goes through the SchemaDefinitionStore port that graph implements");

    @ArchTest
    static final ArchRule kernelDependsOnNoOtherEnginePackage = noClasses()
            .that().resideInAPackage(ENGINE + ".kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ENGINE + ".schema..", GRAPH + "..", ENGINE + ".history..", ENGINE + ".source..",
                    ENGINE + ".ingest..", ENGINE + ".extract..", ENGINE + ".reconcile..", ENGINE + ".retrieval..",
                    ENGINE + ".ai..", ENGINE + ".pipeline..", ENGINE + ".evolution..", ENGINE + ".api..",
                    ENGINE + ".ops..")
            .because("the kernel holds shared value types and must stay at the bottom of the dependency graph");

    @ArchTest
    static final ArchRule onlyAdaptersUseSpringAi = noClasses()
            .that().resideOutsideOfPackages(ENGINE + ".ai..", ENGINE + ".vectorstore..", GRAPH + "..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..")
            .because("skills, pipelines and retrieval use the provider-neutral ports (ADR-013, ADR-014)");

    @ArchTest
    static final ArchRule noNeo4jTypeOutsideTheGraphAdapter = noClasses()
            .that().resideOutsideOfPackage(GRAPH + "..")
            .should().dependOnClassesThat().resideInAnyPackage("org.neo4j..")
            .because("Neo4j is one adapter of the GraphStore and VectorStorePort ports (ADR-014)");

    @ArchTest
    static final ArchRule writesGoThroughTheGraphStorePort = noClasses()
            .that().resideOutsideOfPackage(GRAPH + "..")
            .should().dependOnClassesThat().haveFullyQualifiedName(GRAPH + ".KnowledgeWriter")
            .because("knowledge is written through the GraphStore port; KnowledgeWriter is the Neo4j adapter's internals");

    @ArchTest
    static final ArchRule portsDoNotDependOnAdapters = noClasses()
            .that().resideInAnyPackage(ENGINE + ".store..", ENGINE + ".vectorstore..")
            .should().dependOnClassesThat().resideInAnyPackage(GRAPH + "..", ENGINE + ".index..", ENGINE + ".retrieval..")
            .because("ports and their non-Neo4j adapters must not depend on the Neo4j adapter or on their users");

    @ArchTest
    static final ArchRule kernelIsFrameworkFree = noClasses()
            .that().resideInAPackage(ENGINE + ".kernel..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.neo4j..", "tools.jackson..")
            .because("kernel types are shared with plugins and tests and must not drag in frameworks");
}
