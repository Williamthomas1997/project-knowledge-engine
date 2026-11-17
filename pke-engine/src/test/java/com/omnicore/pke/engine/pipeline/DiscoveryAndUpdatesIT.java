package com.omnicore.pke.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.ingest.DocumentIngestionService;
import com.omnicore.pke.engine.ingest.IngestionRunService;
import com.omnicore.pke.engine.ingest.IngestionRunService.Kind;
import com.omnicore.pke.engine.ingest.IngestionRunService.Mode;
import com.omnicore.pke.engine.ingest.IngestionRunService.Request;
import com.omnicore.pke.engine.ingest.RepositoryIngestionService;
import com.omnicore.pke.engine.retrieval.LogTrailService;
import com.omnicore.pke.engine.retrieval.LogTrailService.Explanation;
import com.omnicore.pke.engine.retrieval.LogTrailService.LogEntry;
import com.omnicore.pke.engine.retrieval.QueryTemplates;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.DemoCorpus.DemoRepo;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * M5 update and discovery pipelines: brd-update reaches the model only for changed sections, SHARES_LOGIC_WITH on
 * the demo service (utilities excluded), a proposed relationship type registered without Java changes and
 * populated, and the log trail.
 */
class DiscoveryAndUpdatesIT extends KnowledgeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DOC = "doc:international-transfer-brd";
    private static final String PKG = "com.demo.transfer.";

    @Autowired
    DocumentIngestionService documents;

    @Autowired
    PipelineExecutor executor;

    @Autowired
    RepositoryIngestionService repositories;

    @Autowired
    IngestionRunService runs;

    @Autowired
    QueryTemplates templates;

    @Autowired
    LogTrailService logTrail;

    @Autowired
    SchemaRegistry registry;

    private void upload(String demoFile) {
        try {
            documents.ingest(Files.readAllBytes(DemoCorpus.file(demoFile)), "international-transfer-brd.md", null, null, "t");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ExecutionView completed(ExecutionView execution) {
        assertThat(execution.status()).as("execution %s: %s %s", execution.id(), execution.error(),
                execution.steps().stream().map(s -> s.stepId() + "=" + s.status() + " " + s.error()).toList())
                .isEqualTo("COMPLETED");
        return execution;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(ExecutionView execution, String stepId) {
        return JSON.readValue(execution.steps().stream().filter(s -> s.stepId().equals(stepId)).findFirst().orElseThrow()
                .outputJson(), Map.class);
    }

    private DemoRepo ingestTransferService() {
        DemoRepo repo = DemoCorpus.repository("transfer-service");
        repositories.register("transfer-service", repo.dir().toString(), "transfer-service", "t");
        assertThat(runs.start(new Request(Kind.REPOSITORY, "transfer-service", Mode.FULL), "t", true).status().name())
                .isEqualTo("COMPLETED");
        return repo;
    }

    /** The service-layer API handled by {@code TransferController#<handler>}. */
    private static String api(String handler) {
        return rows("""
                MATCH (a:ApiOperation {layer: 'service'})-[:HANDLED_BY]->(m:JavaMethod {fqcn: $fqcn, signature: $sig})
                RETURN a.pke_id AS id""", Map.of("fqcn", PKG + "TransferController", "sig", handler))
                .getFirst().get("id").toString();
    }

    @Test
    void brdUpdateSendsOnlyChangedSectionsToTheModel() {
        upload("brd/international-transfer-brd.md");
        completed(executor.start("brd-update", Map.of("documentKey", DOC), "t", false, true));

        upload("brd/changes/international-transfer-brd-v2.md");
        ExecutionView v2 = completed(executor.start("brd-update", Map.of("documentKey", DOC), "t", false, true));
        Map<String, Object> diff = output(v2, "diff");
        assertThat(diff.get("previousVersion")).isNotNull();
        assertThat(diff.get("unchanged")).as("diff %s", diff).isEqualTo(6);
        assertThat(diff.get("added").toString()).contains("FR-4");
        assertThat(diff.get("changed").toString()).contains("FR-2");

        List<?> sent = (List<?>) output(v2, "sections").get("sections");
        long cardCalls = count("MATCH (c:ModelCall {executionId: $id, skill: 'brd-to-knowledge-cards'}) RETURN count(c)",
                Map.of("id", v2.id()));
        assertThat(cardCalls).as("one card call per changed or added section").isEqualTo(sent.size());
        assertThat(sent.size()).isLessThanOrEqualTo(((List<?>) diff.get("added")).size() + ((List<?>) diff.get("changed")).size());
        assertThat(sent).allSatisfy(s -> assertThat(diff.get("added").toString() + diff.get("changed"))
                .contains(((Map<?, ?>) s).get("headingPath").toString()));
    }

    @Test
    void sharedLogicLinksApisThroughBusinessMethodsButNotUtilities() {
        DemoRepo repo = ingestTransferService();
        repo.applyAndCommit("transfer-service-changes/c4", "c4: execute re-checks the currency");
        ExecutionView run = completed(executor.start("code-on-commit", Map.of("repository", "transfer-service"), "t",
                false, true));
        assertThat(output(run, "shared-logic").get("pairs")).isNotEqualTo(0);

        String validate = api("validate(TransferRequest,String)");
        String execute = api("execute(ExecuteRequest)");
        List<Map<String, Object>> links = rows("""
                MATCH (a:Assertion {pke_type: 'SHARES_LOGIC_WITH', pke_status: 'CURRENT'})
                RETURN a.pke_subjectId AS s, a.pke_objectId AS o, a.sharedMethods AS shared, a.pke_category AS category""",
                Map.of());
        Map<String, Object> pair = links.stream().filter(l -> List.of(l.get("s"), l.get("o")).containsAll(List.of(validate, execute)))
                .findFirst().orElseThrow(() -> new AssertionError("no validate/execute link in " + links));
        assertThat(pair.get("shared")).isEqualTo(List.of(PKG + "TransferService#validateCurrency(String)"));
        assertThat(pair.get("category")).isEqualTo("technical");
        assertThat(links).allSatisfy(l -> assertThat(l.get("shared").toString()).doesNotContain("TransferTextUtils"));
        assertThat(count("MATCH (:ApiOperation)-[r:SHARES_LOGIC_WITH]->(:ApiOperation) RETURN count(r)")).isEqualTo(links.size());

        // nothing changed: re-deriving writes nothing
        long events = count("MATCH (e:ChangeEvent) RETURN count(e)");
        completed(executor.start("code-on-commit", Map.of("repository", "transfer-service"), "t", true, true));
        assertThat(count("MATCH (e:ChangeEvent) RETURN count(e)")).isEqualTo(events);
    }

    @Autowired
    com.omnicore.pke.engine.store.GraphStore store;

    /** M6.1: gateway-layer pairs written by M6 are invalidated (history kept); only service APIs are paired. */
    @Test
    void sharedLogicMigratesToServiceApisOnlyAndKeepsHistory() {
        DemoRepo repo = ingestTransferService();
        repo.applyAndCommit("transfer-service-changes/c4", "c4: execute re-checks the currency");
        repositories.register("qa-automation", DemoCorpus.repository("qa-automation").dir().toString(), null, "t");
        assertThat(runs.start(new Request(Kind.REPOSITORY, "qa-automation", Mode.FULL), "t", true).status().name())
                .isEqualTo("COMPLETED");
        completed(executor.start("code-on-commit", Map.of("repository", "transfer-service"), "t", true, true));
        String validate = api("validate(TransferRequest,String)");
        String execute = api("execute(ExecuteRequest)");
        List<Map<String, Object>> gateway = rows("""
                MATCH (g:ApiOperation {layer: 'gateway'})-[:HANDLED_BY]->(m:JavaMethod)
                WHERE m.signature IN ['validate(TransferRequest,String)', 'execute(ExecuteRequest)']
                RETURN g.pke_id AS id ORDER BY m.signature""", Map.of());
        assertThat(gateway).as("gateway APIs resolved to the same handlers").hasSize(2);

        // an M6-style edge between the two gateway APIs, in the same evidence scope as the derivation
        String a = (String) gateway.get(0).get("id");
        String b = (String) gateway.get(1).get("id");
        store.apply(new com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet("m6-edge", List.of(), List.of(
                new com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite("SHARES_LOGIC_WITH",
                        com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef.id(a.compareTo(b) < 0 ? a : b),
                        com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef.id(a.compareTo(b) < 0 ? b : a),
                        Map.of("sharedMethods", List.of(PKG + "TransferService#validateCurrency(String)"), "sharedCount", 1),
                        com.omnicore.pke.engine.kernel.EpistemicStatus.EXTRACTED_DETERMINISTIC, null,
                        List.of(com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput.scoped("derived:shared-logic",
                                "m6", "derived:shared-logic", "call-graph", "discover.shared-logic", "m6")))), List.of()), "t");

        ExecutionView migration = completed(executor.start("shared-logic", Map.of("reason", "M6.1 migration"), "t", false, true));
        assertThat(output(migration, "shared-logic").get("outcomes").toString()).contains("INVALIDATED=1");
        assertThat(rows("""
                MATCH (a:Assertion {pke_type: 'SHARES_LOGIC_WITH'})
                MATCH (s:ApiOperation {pke_id: a.pke_subjectId}), (o:ApiOperation {pke_id: a.pke_objectId})
                RETURN a.pke_status AS status, s.layer + '-' + o.layer AS layers ORDER BY status""", Map.of()))
                .containsExactly(Map.of("status", "CURRENT", "layers", "service-service"),
                        Map.of("status", "INVALID", "layers", "gateway-gateway"));
        assertThat(count("""
                MATCH (a:Assertion {pke_type: 'SHARES_LOGIC_WITH', pke_status: 'INVALID'})-[:HAS_VERSION]->(v:AssertionVersion)
                RETURN count(v)""")).as("the invalidated edge keeps its versions").isEqualTo(2);
        assertThat(count("""
                MATCH (a:Assertion {pke_type: 'SHARES_LOGIC_WITH', pke_status: 'CURRENT'})
                WHERE [a.pke_subjectId, a.pke_objectId] = [$x, $y] OR [a.pke_subjectId, a.pke_objectId] = [$y, $x]
                RETURN count(a)""", Map.of("x", validate, "y", execute))).isEqualTo(1);
    }

    /** M6.1: in reactive code, handlers reach their logs through method references and single implementations. */
    @Test
    void reactiveHandlersReachTheirLogsThroughTheCallGraph() {
        DemoRepo repo = DemoCorpus.repository("reactive-service");
        repositories.register("reactive-service", repo.dir().toString(), "reactive-service", "t");
        assertThat(runs.start(new Request(Kind.REPOSITORY, "reactive-service", Mode.FULL), "t", true).status().name())
                .isEqualTo("COMPLETED");
        long logs = count("MATCH (l:LogStatement {repository: 'reactive-service'}) RETURN count(l)");
        assertThat(logs).isEqualTo(7);
        assertThat(count("""
                MATCH (:ApiOperation {service: 'reactive-service'})-[:HANDLED_BY]->(:JavaMethod)-[:CALLS*0..4]->(:JavaMethod)-[:LOGS]->(l)
                RETURN count(DISTINCT l)""")).as("every log statement is reachable from a handler").isEqualTo(logs);
        assertThat(count("""
                MATCH (a:Assertion {pke_type: 'CALLS'}) WHERE a.resolution IN ['method-reference', 'single-implementation']
                RETURN count(a)""")).isGreaterThanOrEqualTo(5);
        assertThat(count("""
                MATCH (m:JavaMethod {repository: 'reactive-service', signature: 'execute(ReactiveRequest)'})
                WHERE any(u IN m.unresolvedCalls WHERE u STARTS WITH 'multiple-implementations: ') RETURN count(m)"""))
                .isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProposedRelationshipTypeIsRegisteredWithoutJavaChangesAndPopulated() throws Exception {
        ingestTransferService();
        assertThat(registry.relationshipType("CAN_RETURN_ERROR")).isEmpty();
        ExecutionView run = completed(executor.start("discover-relationships", Map.of(), "t", false, true));

        Map<String, String> outcomes = new java.util.TreeMap<>();
        ((List<Map<String, Object>>) output(run, "propose").get("proposals"))
                .forEach(p -> outcomes.put((String) p.get("name"), (String) p.get("outcome")));
        assertThat(outcomes).containsEntry("CAN_RETURN_ERROR", "REGISTERED")
                .containsEntry("SHARES_BUSINESS_RULE_WITH", "EVOLUTION_REQUESTED")
                .containsEntry("RUNS_IN_ENVIRONMENT", "EVOLUTION_REQUESTED");

        assertThat(registry.relationshipType("CAN_RETURN_ERROR")).isPresent();
        assertThat(count("""
                MATCH (e:ChangeEvent {type: 'SCHEMA_REGISTERED', subjectType: 'RelationshipType'})-[:ABOUT]->
                      (:SchemaDefinition {name: 'CAN_RETURN_ERROR', current: true}) RETURN count(e)"""))
                .as("registered through the schema registry, with its event").isEqualTo(1);
        List<Map<String, Object>> codes = rows("""
                MATCH (a:ApiOperation)-[:CAN_RETURN_ERROR]->(e:ErrorCode)
                MATCH (a)-[:HANDLED_BY]->(:JavaMethod {signature: 'validate(TransferRequest,String)'})
                RETURN collect(DISTINCT e.code) AS codes""", Map.of());
        assertThat((List<String>) codes.getFirst().get("codes")).contains("003040", "003041");

        List<Map<String, Object>> requests = rows("""
                MATCH (r:EvolutionRequest {status: 'OPEN'}) RETURN r.subject AS subject, r.reason AS reason ORDER BY subject""",
                Map.of());
        assertThat(requests).extracting(r -> r.get("subject")).containsExactly("RUNS_IN_ENVIRONMENT", "SHARES_BUSINESS_RULE_WITH");
        assertThat(requests.get(1).get("reason").toString()).contains("technical relationships alone");

        // code-on-commit re-populates the derived type; unchanged code writes nothing
        long before = count("MATCH (e:ChangeEvent) WHERE e.subjectType = 'CAN_RETURN_ERROR' RETURN count(e)");
        completed(executor.start("discover-relationships", Map.of(), "t", true, true));
        assertThat(count("MATCH (e:ChangeEvent) WHERE e.subjectType = 'CAN_RETURN_ERROR' RETURN count(e)")).isEqualTo(before);
    }

    @Test
    void theLogTrailIsInCallOrderAndALogLineIsExplained() {
        ingestTransferService();
        String validate = api("validate(TransferRequest,String)");
        List<String> trail = templates.run("log-trail-of-api", validate, 50).rows().stream()
                .map(r -> (String) r.get("label")).toList();
        assertThat(trail).hasSize(3);
        assertThat(trail.get(0)).startsWith("INFO Validating transfer");
        assertThat(trail.get(1)).startsWith("WARN Transfer above the daily limit");
        assertThat(trail.get(2)).startsWith("ERROR Currency");

        Explanation explained = logTrail.explain(
                "2026-11-17 10:15:02 INFO  TransferService - Validating transfer to B-1001", null);
        assertThat(explained.match().label()).startsWith("INFO Validating transfer");
        assertThat(explained.api()).isEqualTo(validate);
        assertThat(explained.nextExpected()).extracting(LogEntry::label)
                .containsExactly(trail.get(1), trail.get(2));
        assertThat(explained.likelyFailingMethod()).isEqualTo(PKG + "TransferService#validate(TransferRequest)");
        assertThat(explained.errorCodes()).contains("003040", "003041");
        assertThat(explained.explanationStatus()).isEqualTo("OK");
        assertThat(explained.explanation().get("explanation").toString()).containsIgnoringCase("validat");
    }
}
