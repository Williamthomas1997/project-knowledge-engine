package com.omnicore.pke.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EntityView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.VersionView;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.StepView;
import com.omnicore.pke.engine.ingest.DocumentIngestionService;
import com.omnicore.pke.engine.ingest.IngestionRunService;
import com.omnicore.pke.engine.ingest.RepositoryIngestionService;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BRD → knowledge cards with the replay model and a real Neo4j (M3 prompt §5): expected cards, idempotent re-runs
 * without model calls, a changed limit (UPDATE + INVALIDATE with history), a contradiction (question, then the
 * answer is applied), requirement → implementation links, a schema-breaking answer, resume after a crash, and the
 * cost ledger.
 */
class BrdIngestPipelineIT extends KnowledgeIT {

    static final String DOC = "doc:international-transfer-brd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DocumentIngestionService documents;

    @Autowired
    PipelineExecutor executor;

    @Autowired
    KnowledgeQueryService queries;

    @Autowired
    RepositoryIngestionService repositories;

    @Autowired
    IngestionRunService runs;

    // ------------------------------------------------------------------------------------------ helpers

    private void upload(String demoFile) {
        try {
            documents.ingest(Files.readAllBytes(DemoCorpus.file(demoFile)), "international-transfer-brd.md", null, null, "t");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ExecutionView brdIngest(String documentKey, boolean force) {
        return executor.start("brd-ingest", Map.of("documentKey", documentKey), "t", force, true);
    }

    private ExecutionView completed(ExecutionView execution) {
        assertThat(execution.status()).as("execution %s: %s %s", execution.id(), execution.error(),
                execution.steps().stream().map(s -> s.stepId() + "=" + s.status() + " " + s.error()).toList())
                .isEqualTo("COMPLETED");
        return execution;
    }

    private static StepView step(ExecutionView execution, String id) {
        return execution.steps().stream().filter(s -> s.stepId().equals(id)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(ExecutionView execution, String stepId) {
        return JSON.readValue(step(execution, stepId).outputJson(), Map.class);
    }

    /** Current cards as {text, kind, requirementId, id, epistemic}. */
    private static List<Map<String, Object>> currentCards() {
        return rows("""
                MATCH (c:KnowledgeCard) WHERE coalesce(c.pke_status, 'CURRENT') = 'CURRENT'
                RETURN c.pke_id AS id, c.text AS text, c.kind AS kind, c.requirementId AS requirementId,
                       c.pke_epistemic AS epistemic ORDER BY c.text""", Map.of());
    }

    private static Map<String, Object> cardContaining(String text) {
        List<Map<String, Object>> matches = currentCards().stream()
                .filter(c -> String.valueOf(c.get("text")).contains(text)).toList();
        assertThat(matches).as("current cards containing '%s' in %s", text, currentCards()).hasSize(1);
        return matches.getFirst();
    }

    private static long modelCalls() {
        return count("MATCH (c:ModelCall) RETURN count(c)");
    }

    private HttpResponse<String> http(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-PKE-Actor", "william");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    void theDemoBrdBecomesTheExpectedCardsWithEvidenceAndALedger() throws Exception {
        upload("brd/international-transfer-brd.md");
        ExecutionView execution = completed(brdIngest(DOC, false));

        List<Map<String, Object>> cards = currentCards();
        assertThat(cards).extracting(c -> c.get("requirementId")).contains("FR-1", "FR-2", "FR-3");
        assertThat(cardContaining("not supported for the destination country"))
                .containsEntry("kind", "rule").containsEntry("requirementId", "FR-1");
        assertThat(cardContaining("QAR 50,000")).containsEntry("kind", "constraint").containsEntry("requirementId", "FR-2");
        assertThat(cardContaining("same as the daily limit for local transfers")).containsEntry("requirementId", "FR-2");
        assertThat(cardContaining("transfer-executed event")).containsEntry("kind", "requirement")
                .containsEntry("requirementId", "FR-3");
        assertThat(cardContaining("Error 003040")).containsEntry("kind", "definition");
        assertThat(cardContaining("Error 003041")).containsEntry("kind", "definition");
        assertThat(cards).allSatisfy(c -> assertThat(c.get("epistemic")).isEqualTo("INFERRED_AI"));
        assertThat(cards.stream().map(c -> String.valueOf(c.get("text")))).noneMatch(t -> t.contains("Version 1.0"));

        // every card is supported by evidence that cites its source section and records the skill run
        assertThat(count("""
                MATCH (c:KnowledgeCard) WHERE NOT EXISTS {
                  MATCH (c)-[:SUPPORTED_BY]->(e:Evidence)-[:CITES]->(:Section {artifactKey: $doc})
                  WHERE e.extractionMethod = 'skill:brd-to-knowledge-cards' AND e.modelId IS NOT NULL
                    AND e.promptHash IS NOT NULL AND e.skillVersion = 'brd-to-knowledge-cards@1.0.0'
                    AND e.epistemic = 'INFERRED_AI' AND e.pipelineVersion = 'brd-ingest@1.0.0'
                } RETURN count(c)""", Map.of("doc", DOC))).isZero();

        // cost ledger
        List<Map<String, Object>> calls = rows("MATCH (c:ModelCall {executionId: $id}) RETURN properties(c) AS c",
                Map.of("id", execution.id()));
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(row -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) row.get("c");
            assertThat(c).containsKeys("provider", "model", "skill", "skillVersion", "tokensIn", "tokensOut",
                    "promptHash", "inputHash", "stepId");
            assertThat(c.get("outcome")).isEqualTo("OK");
        });
        JsonNode ledger = JSON.readTree(http("GET", "/api/ai/calls?executionId=" + execution.id(), null).body());
        assertThat(ledger.size()).isEqualTo(calls.size());
    }

    @Test
    void reRunningTheSameVersionMakesNoModelCallsAndNoEvents() {
        upload("brd/international-transfer-brd.md");
        ExecutionView first = completed(brdIngest(DOC, false));
        long calls = modelCalls();
        Map<String, Long> before = footprint();

        ExecutionView same = brdIngest(DOC, false);
        assertThat(same.id()).as("idempotency key: same pipeline version and document version").isEqualTo(first.id());

        ExecutionView forced = completed(brdIngest(DOC, true));
        assertThat(forced.id()).isNotEqualTo(first.id());
        assertThat(output(forced, "sections").get("cardsInput")).asList().isEmpty();
        assertThat(modelCalls()).as("fingerprints stop every model call").isEqualTo(calls);
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void aChangedLimitUpdatesOneCardAndInvalidatesAnotherWithHistory() {
        upload("brd/international-transfer-brd.md");
        completed(brdIngest(DOC, false));
        String limitCard = (String) cardContaining("QAR 50,000").get("id");
        String sameAsLocal = (String) cardContaining("same as the daily limit for local transfers").get("id");

        upload("brd/changes/international-transfer-brd-v2.md");
        ExecutionView v2 = completed(brdIngest(DOC, false));

        @SuppressWarnings("unchecked")
        Map<String, Object> decisions = (Map<String, Object>) output(v2, "apply").get("decisions");
        assertThat(decisions).containsEntry("UPDATE", 1).containsEntry("INVALIDATE", 1);
        assertThat(output(v2, "sections").get("unchanged")).as("only FR-2, FR-4 and the header changed").isEqualTo(5);

        EntityView limit = queries.entity(limitCard);
        assertThat(limit.status()).isEqualTo("CURRENT");
        assertThat((String) limit.properties().get("text")).contains("QAR 30,000");
        List<VersionView> history = queries.entityVersions(limitCard);
        assertThat(history).hasSize(2);
        assertThat((String) history.get(0).properties().get("text")).contains("QAR 50,000");
        assertThat(history.get(0).validToSeq()).isNotNull();

        EntityView invalid = queries.entity(sameAsLocal);
        assertThat(invalid.status()).isEqualTo("INVALID");
        assertThat(invalid.invalidAt()).isNotNull();
        assertThat(invalid.invalidationReason()).contains("superseded by " + DOC);
        assertThat(queries.entityVersions(sameAsLocal)).extracting(VersionView::status).containsExactly("CURRENT", "INVALID");
        assertThat(cardContaining("purpose code")).containsEntry("requirementId", "FR-4");
    }

    @Test
    void aContradictionBecomesAQuestionAndNothingChangesUntilItIsAnswered() throws Exception {
        upload("brd/international-transfer-brd.md");
        completed(brdIngest(DOC, false));
        upload("brd/changes/international-transfer-brd-v2.md");
        completed(brdIngest(DOC, false));
        String limitCard = (String) cardContaining("QAR 30,000").get("id");
        upload("brd/changes/international-transfer-brd-v3.md");
        Map<String, Long> before = footprint();

        ExecutionView v3 = completed(brdIngest(DOC, false));
        assertThat(output(v3, "apply").get("questions")).asList().hasSize(1);
        assertThat(footprint()).as("a conflict writes no knowledge").isEqualTo(before);
        assertThat(queries.entity(limitCard).version()).isEqualTo(2);

        JsonNode open = JSON.readTree(http("GET", "/api/questions?status=open", null).body());
        assertThat(open.size()).isEqualTo(1);
        JsonNode question = open.get(0);
        assertThat(question.get("kind").asString()).isEqualTo("CARD_CONFLICT");
        assertThat(question.get("question").asString()).contains("30,000").contains("50,000");
        assertThat(question.get("concerns").get(0).asString()).isEqualTo(limitCard);
        String id = question.get("id").asString();
        JsonNode detail = JSON.readTree(http("GET", "/api/questions/" + id, null).body());
        assertThat(detail.get("citedSections").size()).as("the candidate's source section").isEqualTo(1);
        assertThat(detail.get("citedEvidence").size()).as("the existing card's evidence").isPositive();

        HttpResponse<String> answer = http("POST", "/api/questions/" + id + "/answer",
                "{\"option\": \"use-new\", \"comment\": \"The limits summary is the approved value\"}");
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        EntityView card = queries.entity(limitCard);
        assertThat((String) card.properties().get("text")).contains("QAR 50,000");
        assertThat(card.epistemic()).isEqualTo("HUMAN_VERIFIED");
        assertThat(card.version()).isEqualTo(3);
        assertThat(card.evidence()).anySatisfy(e -> {
            assertThat(e.get("epistemic")).isEqualTo("HUMAN_VERIFIED");
            assertThat(e.get("extractionMethod")).isEqualTo("human-answer");
        });
        assertThat(count("MATCH (e:ChangeEvent {subjectId: $id, actor: 'api:william'}) RETURN count(e)",
                Map.of("id", limitCard))).isEqualTo(1);
        assertThat(JSON.readTree(http("GET", "/api/questions?status=open", null).body()).size()).isZero();
        assertThat(http("POST", "/api/questions/" + id + "/answer", "{\"option\": \"keep-existing\"}").statusCode())
                .isEqualTo(409);
    }

    @Test
    void requirementsAreLinkedToTheirImplementationAsInferredWithEvidence() {
        var repo = DemoCorpus.repository("transfer-service");
        repositories.register("transfer-service", repo.dir().toString(), "transfer-service", "t");
        upload("brd/international-transfer-brd.md");
        completed(executor.start("code-ingest", Map.of("repository", "transfer-service", "mode", "FULL"), "t", false, true));

        ExecutionView brd = completed(brdIngest(DOC, false));
        assertThat((Integer) output(brd, "link").get("links")).isPositive();

        List<Map<String, Object>> links = rows("""
                MATCH (c:KnowledgeCard)-[r:IMPLEMENTED_BY]->(m:JavaMethod)
                MATCH (a:Assertion {pke_id: r.pke_relId})-[:SUPPORTED_BY]->(e:Evidence)
                RETURN c.requirementId AS requirement, m.signature AS method, a.pke_epistemic AS epistemic,
                       e.modelId AS model, e.promptHash AS promptHash, e.confidenceReported AS confidence,
                       e.excerpt AS rationale, e.skillVersion AS skill""", Map.of());
        assertThat(links).anySatisfy(l -> {
            assertThat(l.get("requirement")).isEqualTo("FR-1");
            assertThat(l.get("method")).isEqualTo("validateCurrency(String)");
        });
        assertThat(links).anySatisfy(l -> {
            assertThat(l.get("requirement")).isEqualTo("FR-2");
            assertThat(l.get("method")).isEqualTo("validate(TransferRequest)");
        });
        assertThat(links).allSatisfy(l -> {
            assertThat(l.get("epistemic")).isEqualTo("INFERRED_AI");
            assertThat(l.get("model")).isNotNull();
            assertThat(l.get("promptHash")).isNotNull();
            assertThat(l.get("rationale")).isNotNull();
            assertThat(l.get("skill")).isEqualTo("link-requirement-to-implementation@1.0.0");
            assertThat(((Number) l.get("confidence")).doubleValue()).isBetween(0.5, 1.0);
        });
        assertThat(links).noneSatisfy(l -> assertThat(String.valueOf(l.get("method"))).contains("convert"));

        // re-linking after a code run with no new commits: every card's inputs are unchanged
        long calls = modelCalls();
        ExecutionView relink = completed(executor.start("code-ingest", Map.of("repository", "transfer-service",
                "mode", "INCREMENTAL"), "t", true, true));
        assertThat(output(relink, "link").get("links")).isEqualTo(0);
        assertThat(modelCalls()).isEqualTo(calls);
    }

    @Test
    void anAnswerOutsideTheOutputSchemaFailsPermanentAndWritesNothing() throws Exception {
        documents.ingest(Files.readAllBytes(Path.of("src/test/resources/docs/bad-skill-output.md")), "bad-skill-output.md",
                null, null, "t");
        Map<String, Long> before = footprint();
        ExecutionView failed = brdIngest("doc:bad-skill-output", false);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorClass()).isEqualTo("PERMANENT");
        StepView cards = step(failed, "cards");
        assertThat(cards.status()).isEqualTo("FAILED");
        assertThat(cards.attempts()).as("a PERMANENT failure is not retried").isEqualTo(1);
        assertThat(cards.error()).contains("output schema");
        assertThat(step(failed, "apply").status()).isEqualTo("PENDING");
        assertThat(footprint()).isEqualTo(before);
        assertThat(count("MATCH (c:ModelCall {outcome: 'INVALID_OUTPUT'}) RETURN count(c)")).isEqualTo(1);
    }

    @Test
    void anInterruptedPipelineResumesFromTheFailedStepWithoutCallingTheModelAgain() {
        upload("brd/international-transfer-brd.md");
        pipelineFaults.crashAt("brd-ingest", "apply");
        ExecutionView failed = brdIngest(DOC, false);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(step(failed, "cards").status()).isEqualTo("COMPLETED");
        assertThat(step(failed, "reconcile").status()).isEqualTo("COMPLETED");
        assertThat(step(failed, "apply").status()).isEqualTo("FAILED");
        assertThat(count("MATCH (c:KnowledgeCard) RETURN count(c)")).isZero();
        long calls = modelCalls();

        pipelineFaults.reset();
        ExecutionView resumed = completed(executor.resume(failed.id(), "t", true));
        assertThat(modelCalls()).as("completed steps are not run again").isEqualTo(calls);
        assertThat(step(resumed, "cards").attempts()).isEqualTo(1);
        assertThat(step(resumed, "apply").attempts()).isEqualTo(2);
        assertThat(currentCards()).isNotEmpty();
    }
}
