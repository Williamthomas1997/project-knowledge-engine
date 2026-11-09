package com.omnicore.pke.engine.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.graph.CodeQueryService.JourneyStepView;
import com.omnicore.pke.engine.graph.GraphIntegrityService;
import com.omnicore.pke.engine.graph.IngestionRunStore.Run;
import com.omnicore.pke.engine.graph.IngestionRunStore.Status;
import com.omnicore.pke.engine.ingest.IngestionRunService.Kind;
import com.omnicore.pke.engine.ingest.IngestionRunService.Mode;
import com.omnicore.pke.engine.ingest.IngestionRunService.Request;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The transfer journey from four sources, its data flow, suite coverage and route resolution to handlers. */
class JourneyIngestionIT extends KnowledgeIT {

    @Autowired
    RepositoryIngestionService repositories;

    @Autowired
    IngestionRunService runs;

    @Autowired
    CodeQueryService journeys;

    @Autowired
    GraphIntegrityService integrity;

    @BeforeEach
    void ingest() {
        repositories.register("transfer-service", DemoCorpus.repository("transfer-service").dir().toString(), "transfer-service", "t");
        repositories.register("qa-automation", DemoCorpus.repository("qa-automation").dir().toString(), null, "t");
        for (String repo : List.of("transfer-service", "qa-automation")) {
            Run run = runs.start(new Request(Kind.REPOSITORY, repo, Mode.FULL), "t", true);
            assertThat(run.status()).as("%s: %s %s", repo, run.error(), run.statsJson()).isEqualTo(Status.COMPLETED);
        }
    }

    @Test
    void allFourSourcesDescribeTheSameJourney() {
        var journey = journeys.journey("international-transfer");
        assertThat(journey.name()).isEqualTo("International Transfer");
        assertThat(journey.steps()).hasSize(3);
        assertThat(journey.sources()).anyMatch(s -> s.startsWith("journey.postman"))
                .anyMatch(s -> s.startsWith("journey.java-journey")).anyMatch(s -> s.startsWith("journey.gatling"))
                .anyMatch(s -> s.startsWith("journey.jmeter"));
        assertThat(journey.steps()).extracting(s -> s.apis().size()).containsOnly(1);
        assertThat(journey.steps()).extracting(s -> s.apis().getFirst().get("pathTemplate")).containsExactly(
                "/api/v1/get-ft-accounts", "/api/v2/international-transfers/validation", "/api/v3/international-transfers/execute");
        // every source asserted every step's API: four evidence items per CALLS_API
        assertThat(count("""
                MATCH (:UserJourney {journeyKey: 'international-transfer'})-[:HAS_STEP]->(s)
                MATCH (a:Assertion {pke_type: 'CALLS_API', pke_subjectId: s.pke_id})-[:SUPPORTED_BY]->(e:Evidence)
                RETURN count(DISTINCT e.extractionMethod)""")).isEqualTo(4);
        assertThat(journey.steps().get(1).labels()).anyMatch(l -> l.startsWith("validate"))
                .anyMatch(l -> l.startsWith("InitiateTransfer"));
        // the gateway API carries its full definition, never values
        assertThat(count("""
                MATCH (a:ApiOperation {layer: 'gateway', pathTemplate: '/api/v2/international-transfers/validation'})
                WHERE a.gatewayKey = 'TRANSFER|INTERNATIONAL|INTL_TRANSFER|INTL_01' AND 'Authorization' IN a.otherHeaders
                  AND a.requestFields CONTAINS '"purpose"' AND NOT a.requestFields CONTAINS 'USD'
                RETURN count(a)""")).isEqualTo(1);
    }

    @Test
    void dataFlowBetweenSteps() {
        assertThat(journeys.journey("international-transfer").feeds()).extracting(f -> f.get("fromStep") + "->" + f.get("toStep") + " " + f.get("field"))
                .containsExactly("1->2 auid", "2->3 uuid");
    }

    @Test
    void routesLinkEveryJourneyApiToItsHandler() {
        List<JourneyStepView> steps = journeys.journey("international-transfer").steps();
        assertThat(steps).extracting(s -> s.handlers().size()).containsOnly(1);
        assertThat(steps).extracting(s -> s.handlers().getFirst().get("signature")).containsExactly(
                "ftAccounts(AccountsRequest)", "validate(TransferRequest,String)", "execute(ExecuteRequest)");
        assertThat(count("""
                MATCH (:ApiOperation {layer: 'gateway'})-[r:HANDLED_BY]->(:JavaMethod)
                MATCH (a:Assertion {pke_id: r.pke_relId})-[:SUPPORTED_BY]->(:Evidence {extractionMethod: 'route.gateway-export'})
                RETURN count(DISTINCT a)""")).isEqualTo(3);
        assertThat(integrity.check().ok()).isTrue();
    }

    @Test
    void suitesCoverTheJourneysTheirClassesDrive() {
        assertThat(journeys.journey("international-transfer").coveredBy()).containsExactly("transfer");
    }

    @Test
    void reIngestingTheJourneysWritesNothing() {
        var before = footprint();
        Run run = runs.start(new Request(Kind.REPOSITORY, "qa-automation", Mode.FULL), "t", true);
        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void journeyApiOverRest() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/journeys/international-transfer")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("validate(TransferRequest,String)").contains("\"fromStep\":1");
        HttpResponse<String> list = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/journeys")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(list.body()).contains("\"journeyKey\":\"international-transfer\"").contains("\"steps\":3");
        assertThat(count("MATCH (r:IngestionRun {status: 'COMPLETED'}) RETURN count(r)")).isEqualTo(2);
        assertThat(Map.of()).isEmpty();
    }
}
