package com.omnicore.pke.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Package boundaries from IMPLEMENTATION_PLAN.md §1, enforced rather than left to convention. */
@AnalyzeClasses(packages = "com.omnicore.pke.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ENGINE = "com.omnicore.pke.engine";

    @ArchTest
    static final ArchRule coreDoesNotDependOnApiAdapters = noClasses()
            .that().resideOutsideOfPackage(ENGINE + ".api..")
            .and().doNotHaveFullyQualifiedName(ENGINE + ".PkeApplication")
            .should().dependOnClassesThat().resideInAPackage(ENGINE + ".api..")
            .because("REST and MCP are thin adapters; the core must not know about them");

    @ArchTest
    static final ArchRule kernelDependsOnNoOtherEnginePackage = noClasses()
            .that().resideInAPackage(ENGINE + ".kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ENGINE + ".schema..", ENGINE + ".graph..", ENGINE + ".history..", ENGINE + ".source..",
                    ENGINE + ".ingest..", ENGINE + ".extract..", ENGINE + ".reconcile..", ENGINE + ".retrieval..",
                    ENGINE + ".ai..", ENGINE + ".pipeline..", ENGINE + ".evolution..", ENGINE + ".api..",
                    ENGINE + ".ops..")
            .because("the kernel holds shared value types and must stay at the bottom of the dependency graph");

    @ArchTest
    static final ArchRule kernelIsFrameworkFree = noClasses()
            .that().resideInAPackage(ENGINE + ".kernel..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.neo4j..")
            .because("kernel types are shared with plugins and tests and must not drag in frameworks");

    @ArchTest
    static final ArchRule apiAdaptersDoNotTalkToNeo4jDirectly = noClasses()
            .that().resideInAPackage(ENGINE + ".api..")
            .should().dependOnClassesThat().resideInAnyPackage("org.neo4j..", "org.springframework.data.neo4j..")
            .because("adapters must go through application services, never the database");
}
