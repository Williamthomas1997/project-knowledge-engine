package com.omnicore.pke.engine.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.GraphIntegrityService;
import com.omnicore.pke.engine.graph.IngestionRunStore.Run;
import com.omnicore.pke.engine.graph.IngestionRunStore.Status;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.ingest.IngestionRunService.Kind;
import com.omnicore.pke.engine.ingest.IngestionRunService.Mode;
import com.omnicore.pke.engine.ingest.IngestionRunService.Request;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.DemoCorpus.DemoRepo;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Java code ingestion from a real Git repository: specific edges, idempotency, invalidation and renames. */
class RepositoryIngestionIT extends KnowledgeIT {

    private static final String REPO = "transfer-service";
    private static final String PKG = "com.demo.transfer.";

    @Autowired
    RepositoryIngestionService repositories;

    @Autowired
    IngestionRunService runs;

    @Autowired
    KnowledgeQueryService queries;

    @Autowired
    SourceQueryService sources;

    @Autowired
    GraphIntegrityService integrity;

    private DemoRepo repo;

    @BeforeEach
    void register() {
        repo = DemoCorpus.repository(REPO);
        repositories.register(REPO, repo.dir().toString(), "transfer-service", "t");
    }

    private Run run(Mode mode) {
        Run run = runs.start(new Request(Kind.REPOSITORY, REPO, mode), "t", true);
        assertThat(run.status()).as("run %s: %s %s", run.id(), run.error(), run.statsJson()).isEqualTo(Status.COMPLETED);
        return run;
    }

    private static String method(String type, String signature) {
        return PkeIds.entityId("JavaMethod", List.of(REPO, PKG + type, signature));
    }

    private static long edges(String type, String from, String to, String status) {
        return count("""
                MATCH (a:Assertion {pke_type: $type, pke_subjectId: $from, pke_objectId: $to, pke_status: $status})
                RETURN count(a)""", Map.of("type", type, "from", from, "to", to, "status", status));
    }

    @Test
    void fullRunExtractsTheExpectedCodeKnowledge() {
        Run run = run(Mode.FULL);
        assertThat(run.toVersion()).isEqualTo(repo.head());

        String validate = method("TransferService", "validate(TransferRequest)");
        String validateCurrency = method("TransferService", "validateCurrency(String)");
        assertThat(edges("CALLS", validate, validateCurrency, "CURRENT")).isEqualTo(1);
        assertThat(edges("CALLS", validateCurrency, method("CurrencyConverter", "isSupported(String)"), "CURRENT")).isEqualTo(1);
        assertThat(edges("CALLS", method("CurrencyConverter", "convert(BigDecimal,String,String)"),
                method("FxRateClient", "rate(String,String)"), "CURRENT")).isEqualTo(1);
        assertThat(count("""
                MATCH (:JavaClass {fqcn: 'com.demo.transfer.TransferService'})-[r:INJECTS {via: 'constructor'}]->(:JavaClass {fqcn: 'com.demo.transfer.CurrencyConverter'})
                RETURN count(r)""")).isEqualTo(1);
        assertThat(count("""
                MATCH (:JavaMethod {signature: 'validateCurrency(String)'})-[:TESTED_BY]->(t:TestCase {methodName: 'rejectsUnsupportedBeneficiaryCurrency'})
                RETURN count(t)""")).isEqualTo(1);
        assertThat(count("""
                MATCH (:JavaMethod {signature: 'validate(TransferRequest)'})-[:LOGS {order: 1}]->(l:LogStatement {level: 'INFO', template: 'Validating transfer to {}'})
                RETURN count(l)""")).isEqualTo(1);
        assertThat(count("""
                MATCH (:JavaMethod {signature: 'validateCurrency(String)'})-[:THROWS_ERROR]->(e:ErrorCode {code: '003040'})
                MATCH (e)-[:SUPPORTED_BY]->(ev:Evidence {extractionMethod: 'extract.sql-error-config'})
                RETURN count(DISTINCT e)""")).isEqualTo(1);
        assertThat(count("""
                MATCH (api:ApiOperation {layer: 'service', httpMethod: 'POST', pathTemplate: '/api/v2/international-transfers/validation'})
                      -[:HANDLED_BY]->(:JavaMethod {signature: 'validate(TransferRequest,String)'})
                MATCH (:Service {serviceName: 'transfer-service'})-[:EXPOSES_API]->(api)
                RETURN count(api)""")).isEqualTo(1);
        assertThat(count("MATCH (:JavaMethod {signature: 'validate(TransferRequest)'})-[:READS_FROM]->(:DatabaseTable {name: 'DKN_BENEFICIARY'}) RETURN count(*)"))
                .isEqualTo(1);
        assertThat(count("MATCH (:JavaMethod {signature: 'execute(ExecuteRequest)'})-[:WRITES_TO]->(:DatabaseTable {name: 'DKN_BENEFICIARY'}) RETURN count(*)"))
                .isEqualTo(1);
        assertThat(count("MATCH (:JavaMethod)-[:PUBLISHES_TO]->(:KafkaTopic {name: 'transfer.executed'}) RETURN count(*)")).isEqualTo(1);
        assertThat(count("MATCH (:JavaMethod)-[:CONSUMES_FROM]->(:KafkaTopic {name: 'fx.rates.updated'}) RETURN count(*)")).isEqualTo(1);
        // evidence links to the commit it came from
        assertThat(count("MATCH (:Evidence {extractionMethod: 'extract.java'})-[:FROM]->(v:SourceVersion {kind: 'commit'}) RETURN count(DISTINCT v)"))
                .isEqualTo(1);
        assertThat(integrity.check().ok()).isTrue();
    }

    @Test
    void reRunningWithoutChangesWritesNothing() {
        run(Mode.FULL);
        var before = footprint();

        Run incremental = run(Mode.INCREMENTAL);
        Run full = run(Mode.FULL);

        assertThat(footprint()).isEqualTo(before);
        assertThat(incremental.statsJson()).contains("\"filesExtracted\":0");
        assertThat(full.statsJson()).doesNotContain("CREATED").doesNotContain("UPDATED");
    }

    @Test
    void aRemovedCallIsInvalidatedWithHistoryAndANewCallIsCreated() {
        run(Mode.FULL);
        String validateCurrency = method("TransferService", "validateCurrency(String)");
        String isSupported = method("CurrencyConverter", "isSupported(String)");
        String oldCall = PkeIds.relationshipId("CALLS", validateCurrency, isSupported, List.of());

        String c2 = repo.applyAndCommit("transfer-service-changes/c2", "c2: currency policy");
        Run run = run(Mode.INCREMENTAL);

        assertThat(run.toVersion()).isEqualTo(c2);
        assertThat(edges("CALLS", validateCurrency, isSupported, "INVALID")).isEqualTo(1);
        assertThat(queries.relationshipVersions(oldCall)).extracting(KnowledgeQueryService.VersionView::status)
                .containsExactly("CURRENT", "INVALID");
        assertThat(edges("CALLS", validateCurrency, method("CurrencyPolicy", "isAllowed(String)"), "CURRENT")).isEqualTo(1);
        assertThat(count("MATCH ()-[r:CALLS {pke_relId: $id}]->() RETURN count(r)", Map.of("id", oldCall))).isZero();
        assertThat(sources.versions(RepositoryIngestionService.key(REPO))).hasSize(2);
        assertThat(integrity.check().ok()).isTrue();
    }

    @Test
    void renamesKeepTheirIdentity() {
        run(Mode.FULL);
        repo.applyAndCommit("transfer-service-changes/c2", "c2");
        run(Mode.INCREMENTAL);
        String converterClass = PkeIds.entityId("JavaClass", List.of(REPO, PKG + "CurrencyConverter"));
        String convert = method("CurrencyConverter", "convert(BigDecimal,String,String)");
        String validateCurrency = method("TransferService", "validateCurrency(String)");
        String validate = method("TransferService", "validate(TransferRequest)");
        String callToCheck = PkeIds.relationshipId("CALLS", validate, validateCurrency, List.of());

        repo.applyAndCommit("transfer-service-changes/c3", "c3: rename converter and validateCurrency");
        run(Mode.INCREMENTAL);

        // the class and its methods keep their pke_id; the new names are aliases
        var renamedClass = queries.entity(converterClass);
        assertThat(renamedClass.properties()).containsEntry("fqcn", PKG + "FxCurrencyConverter");
        assertThat(queries.entity(convert).properties()).containsEntry("fqcn", PKG + "FxCurrencyConverter");
        assertThat(queries.entity(validateCurrency).properties()).containsEntry("signature", "checkCurrency(String)");
        assertThat(count("MATCH (:Entity {pke_id: $id})-[:HAS_ALIAS]->(a:Alias) RETURN count(a)", Map.of("id", converterClass))).isEqualTo(1);
        assertThat(count("MATCH (e:ChangeEvent {type: 'ENTITY_RENAMED'}) RETURN count(e)")).isGreaterThanOrEqualTo(3);
        assertThat(count("MATCH (n:Entity:JavaClass {fqcn: $fqcn}) RETURN count(n)", Map.of("fqcn", PKG + "FxCurrencyConverter"))).isEqualTo(1);
        // the relationship to the renamed method keeps its id and stays current
        assertThat(queries.relationship(callToCheck).status()).isEqualTo("CURRENT");
        assertThat(edges("CALLS", method("CurrencyConverter", "convert(BigDecimal,String,String)"),
                method("FxRateClient", "rate(String,String)"), "CURRENT")).isEqualTo(1);
        assertThat(integrity.check().ok()).isTrue();
    }
}
