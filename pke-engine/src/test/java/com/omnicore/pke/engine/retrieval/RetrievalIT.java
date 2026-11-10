package com.omnicore.pke.engine.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.RetrievalQueries;
import com.omnicore.pke.engine.support.DemoKnowledge;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Hybrid search, the eight query templates, task context and neighbourhoods on the whole demo corpus. */
class RetrievalIT extends KnowledgeIT {

    @Autowired
    DemoKnowledge demo;

    @Autowired
    RetrievalService retrieval;

    @Autowired
    QueryTemplates templates;

    @Autowired
    ContextService context;

    @Autowired
    RetrievalQueries queries;

    @BeforeEach
    void loadDemo() {
        demo.load();
    }

    private static String id(String cypher) {
        List<Map<String, Object>> rows = rows(cypher, Map.of());
        assertThat(rows).as(cypher).hasSize(1);
        return (String) rows.getFirst().get("id");
    }

    private static String method(String signature) {
        return id("MATCH (m:JavaMethod {signature: '" + signature + "'}) WHERE NOT m.fqcn ENDS WITH 'Test' RETURN m.pke_id AS id");
    }

    private static final String VALIDATION_GATEWAY = "MATCH (a:ApiOperation {layer: 'gateway'}) "
            + "WHERE a.pathTemplate = '/api/v2/international-transfers/validation' RETURN a.pke_id AS id";

    private List<String> labels(String template, String seedId) {
        return templates.run(template, seedId, 50).rows().stream().map(r -> String.valueOf(r.get("label"))).toList();
    }

    private List<String> types(String template, String seedId) {
        return templates.run(template, seedId, 50).rows().stream().map(r -> String.valueOf(r.get("type"))).toList();
    }

    @Test
    void everyQueryTemplateExpandsFromItsSeed() {
        assertThat(templates.all()).hasSize(8);
        String gateway = id(VALIDATION_GATEWAY);
        String validate = method("validate(TransferRequest)");
        String validateCurrency = method("validateCurrency(String)");
        String journey = id("MATCH (j:UserJourney {journeyKey: 'international-transfer'}) RETURN j.pke_id AS id");
        String fr1 = id("MATCH (c:KnowledgeCard {requirementId: 'FR-1'}) RETURN c.pke_id AS id");

        assertThat(labels("journey-of-api", gateway)).contains("international-transfer")
                .anyMatch(l -> l.contains("step 2 calls POST /api/v2/international-transfers/validation"));
        assertThat(labels("apis-in-journey", journey)).anyMatch(l -> l.startsWith("step 1 calls"))
                .anyMatch(l -> l.contains("handled by validate(TransferRequest,String)"));
        assertThat(labels("impact-of-method", validateCurrency)).anyMatch(l -> l.endsWith("TransferService#validate(TransferRequest)"))
                .anyMatch(l -> l.contains("/validation")).contains("international-transfer")
                .anyMatch(l -> l.contains("rejectsUnsupportedBeneficiaryCurrency"));
        assertThat(labels("implementation-of-requirement", fr1)).contains("implemented by validateCurrency(String)")
                .anyMatch(l -> l.contains("/validation"));
        assertThat(types("requirements-of-api", gateway)).contains("KnowledgeCard", "IMPLEMENTED_BY");
        assertThat(labels("requirements-of-api", gateway)).anyMatch(l -> l.contains("beneficiary currency"));
        assertThat(templates.run("shared-logic-of-api", gateway, 50).rows()).as("no other API shares the demo's logic").isEmpty();
        assertThat(labels("log-trail-of-api", gateway)).first().asString().startsWith("INFO Validating transfer");
        assertThat(labels("log-trail-of-api", gateway)).anyMatch(l -> l.contains("not supported"))
                .anyMatch(l -> l.contains("daily limit"));
        assertThat(labels("evidence-of", validate)).anyMatch(l -> l.startsWith("repo:transfer-service@"));
        assertThat(labels("evidence-of", fr1)).anyMatch(l -> l.startsWith(DemoKnowledge.BRD + "@") && l.endsWith("#FR-1 Beneficiary currency validation"));
    }

    @Test
    void hybridSearchFindsCardsCodeAndDocumentSections() {
        RetrievalService.SearchResult result = retrieval.search(RetrievalService.SearchRequest.of("beneficiary currency validation", 15));
        assertThat(result.collections()).contains("KnowledgeCard_text");
        assertThat(result.hits()).anySatisfy(h -> {
            assertThat(h.type()).isEqualTo("KnowledgeCard");
            assertThat(h.label()).contains("beneficiary currency is not supported");
            assertThat(h.sources()).anyMatch(s -> s.startsWith("vector:"));
        });
        assertThat(result.hits()).anySatisfy(h -> assertThat(h.label()).isEqualTo("TransferService#validateCurrency(String)"));
        assertThat(result.hits()).anySatisfy(h -> {
            assertThat(h.kind()).isEqualTo("SECTION");
            assertThat(h.label()).isEqualTo(DemoKnowledge.BRD + " — FR-1 Beneficiary currency validation");
        });
        assertThat(result.hits()).extracting(RetrievalService.Hit::id).doesNotHaveDuplicates();
        assertThat(retrieval.search(new RetrievalService.SearchRequest("beneficiary currency validation", 10, List.of("JavaMethod"),
                null, null, null, true, null)).hits()).allSatisfy(h -> assertThat(h.type()).isEqualTo("JavaMethod"));
    }

    @Test
    void theTransferFlowQuestionReturnsGraphAndDocumentEvidenceWithCorrectCitations() {
        ContextService.ContextResult result = context.context(new ContextService.ContextRequest(
                "Which business requirements and APIs are affected if beneficiary currency validation changes?", 3000, 2, 5));
        List<ContextService.Item> all = new java.util.ArrayList<>();
        List.of(result.verified(), result.deterministic(), result.inferred(), result.conflicts()).forEach(all::addAll);

        // graph: the requirement's AI-inferred implementation link and the API handled by the affected code
        assertThat(result.inferred()).anySatisfy(i -> {
            assertThat(i.type()).isEqualTo("IMPLEMENTED_BY");
            assertThat(i.citations()).anyMatch(c -> c.startsWith(DemoKnowledge.BRD + "@#card:"));
        });
        assertThat(result.inferred()).anySatisfy(i -> assertThat(i.label()).contains("beneficiary currency is not supported"));
        assertThat(all).anySatisfy(i -> assertThat(i.label()).contains("/api/v2/international-transfers/validation"));
        assertThat(result.deterministic()).anySatisfy(i -> {
            assertThat(i.type()).isEqualTo("JavaMethod");
            assertThat(i.label()).contains("validateCurrency");
            assertThat(i.citations()).anyMatch(c -> c.startsWith("repo:transfer-service@") && c.contains("TransferService.java"));
        });
        // document evidence: the BRD section, cited as source@version#heading
        assertThat(result.deterministic()).anySatisfy(i -> {
            assertThat(i.type()).isEqualTo("Section");
            assertThat(i.citations()).singleElement().asString().matches(
                    "doc:international-transfer-brd@[0-9a-f]{64}#FR-1 Beneficiary currency validation");
        });
        // every citation resolves to an existing source version
        assertThat(result.citations()).isNotEmpty().allSatisfy(c -> {
            int at = c.indexOf('@');
            String version = c.substring(at + 1, c.indexOf('#', at));
            assertThat(queries.sourceVersionExists(c.substring(0, at), version.isEmpty() ? null : version)).as(c).isTrue();
        });
        assertThat(result.templates()).contains("implementation-of-requirement");
        assertThat(result.markdown()).contains("## Verified facts", "## Deterministic facts", "## AI inferences",
                "## Conflicts and open questions");
    }

    @Test
    void theContextStaysWithinItsBudget() {
        ContextService.ContextResult small = context.context(new ContextService.ContextRequest(
                "Modify beneficiary currency validation in the transfer flow.", 150, 2, 5));
        assertThat(small.usedTokens()).isLessThanOrEqualTo(150);
        assertThat(small.truncated()).isPositive();
        ContextService.ContextResult large = context.context(new ContextService.ContextRequest(
                "Modify beneficiary currency validation in the transfer flow.", 4000, 2, 5));
        assertThat(large.usedTokens()).isLessThanOrEqualTo(4000).isGreaterThan(small.usedTokens());
        int items = large.verified().size() + large.deterministic().size() + large.inferred().size() + large.conflicts().size();
        assertThat(items).isGreaterThan(small.verified().size() + small.deterministic().size() + small.inferred().size()
                + small.conflicts().size());
    }

    @Test
    void restEndpointsServeSearchContextAndNeighbourhoods() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        JsonMapper json = JsonMapper.builder().build();
        HttpResponse<String> search = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/retrieval/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"daily limit\", \"k\": 3}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(json.readTree(search.body()).get("hits").size()).isBetween(1, 3);
        HttpResponse<String> ctx = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/context"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"task\": \"What is the daily limit?\", \"budgetTokens\": 500}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ctx.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ctx.body()).get("usedTokens").asInt()).isLessThanOrEqualTo(500);
        String validate = method("validate(TransferRequest)");
        JsonNode neighbourhood = json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/entities/" + validate + "/neighbourhood?depth=1")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(neighbourhood.get("relationships").size()).isPositive();
        assertThat(neighbourhood.get("nodes").size()).isGreaterThan(1);
    }
}
