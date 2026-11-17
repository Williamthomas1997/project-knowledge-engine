package com.omnicore.pke.engine.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.history.SnapshotService;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.reconcile.QuestionService;
import com.omnicore.pke.engine.security.Role;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.support.DemoKnowledge;
import com.omnicore.pke.engine.support.KnowledgeIT;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M6: every MCP tool through the official MCP Java SDK client (streamable HTTP, API key in the Authorization header)
 * against the demo corpus; REST and MCP equivalence; roles, execution policy and audit; the journey interview
 * (replay model); {@code answer_question} applying the decision.
 */
class McpIT extends KnowledgeIT {

    static final List<String> TOOLS = List.of("search_knowledge", "get_task_context", "get_entity",
            "traverse_dependencies", "find_affected_components", "get_journey", "list_journeys", "get_requirements",
            "find_brd_sections", "get_evidence", "compare_snapshots", "get_schema", "list_skills",
            "list_open_questions", "answer_question", "trigger_ingestion", "explain_log_trail",
            "submit_evolution_request", "start_journey_interview", "answer_journey_interview");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VALIDATION = "/api/v2/international-transfers/validation";

    @Autowired
    DemoKnowledge demo;

    @Autowired
    QuestionService questions;

    @Autowired
    SnapshotService snapshots;

    private final List<McpSyncClient> clients = new ArrayList<>();
    private McpSyncClient contributor;

    @BeforeEach
    void loadDemo() {
        demo.load();
        contributor = client(apiKeys.create("claude-code", List.of(Role.CONTRIBUTOR), "test").key());
    }

    @AfterEach
    void closeClients() {
        clients.forEach(McpSyncClient::closeGracefully);
        clients.clear();
    }

    @Test
    void everyToolAnswersAgainstTheDemoCorpus() {
        assertThat(contributor.listTools().tools()).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(TOOLS);
        assertThat(contributor.listTools().tools()).allSatisfy(t -> {
            assertThat(t.description()).as(t.name()).hasSizeGreaterThan(40);
            assertThat(t.inputSchema()).as(t.name()).isNotNull();
        });

        String api = serviceApi(VALIDATION);
        String method = id("MATCH (m:JavaMethod) WHERE m.signature STARTS WITH 'validate(' RETURN m.pke_id AS id");
        String from = snapshots.create("before", "test").id();
        String to = snapshots.create("after", "test").id();

        assertThat(call(contributor, "search_knowledge", Map.of("query", "transfer validation", "k", 5)).get("hits").size())
                .isPositive();
        assertThat(call(contributor, "get_task_context", Map.of("task", "Add a currency check to transfer validation"))
                .get("markdown").asString()).isNotBlank();
        assertThat(call(contributor, "get_entity", Map.of("id", api)).get("type").asString()).isEqualTo("ApiOperation");
        assertThat(call(contributor, "traverse_dependencies", Map.of("id", api, "depth", 2)).toString()).contains(method);
        assertThat(call(contributor, "find_affected_components", Map.of("methodId", method)).get("rows").toString())
                .contains(VALIDATION);
        assertThat(call(contributor, "list_journeys", Map.of()).toString()).contains("international-transfer");
        assertThat(call(contributor, "get_journey", Map.of("idOrKey", "international-transfer")).get("steps").size())
                .isEqualTo(3);
        assertThat(call(contributor, "get_requirements", Map.of("apiId", api)).has("rows")).isTrue();
        assertThat(call(contributor, "find_brd_sections", Map.of("query", "limit")).size()).isPositive();
        assertThat(call(contributor, "get_evidence", Map.of("id", api)).get("rows").size()).isPositive();
        assertThat(call(contributor, "compare_snapshots", Map.of("fromSnapshotId", from, "toSnapshotId", to)).isObject())
                .isTrue();
        assertThat(call(contributor, "get_schema", Map.of()).get("entityTypes").toString()).contains("ApiOperation");
        assertThat(call(contributor, "get_schema", Map.of("kind", "ENTITY_TYPE", "name", "UserJourney")).toString())
                .contains("journeyKey");
        assertThat(call(contributor, "list_skills", Map.of()).toString()).contains("journey-interview");
        assertThat(call(contributor, "list_open_questions", Map.of()).isArray()).isTrue();
        assertThat(call(contributor, "explain_log_trail",
                Map.of("logLine", "2026-11-17 10:15:02 INFO  TransferService - Validating transfer to B-1001"))
                .get("match").isObject()).isTrue();
        // the mutating tools are covered by the tests below
    }

    @Test
    void restAndMcpReturnTheSameResults() throws Exception {
        String api = serviceApi(VALIDATION);
        assertEquivalent(call(contributor, "get_entity", Map.of("id", api)), rest("GET", "/api/entities/" + api, null));
        assertEquivalent(call(contributor, "list_journeys", Map.of()), rest("GET", "/api/journeys", null));
        assertEquivalent(call(contributor, "get_journey", Map.of("idOrKey", "international-transfer")),
                rest("GET", "/api/journeys/international-transfer", null));
        assertEquivalent(call(contributor, "list_skills", Map.of()), rest("GET", "/api/skills", null));
        assertEquivalent(call(contributor, "find_brd_sections", Map.of("query", "limit")),
                rest("GET", "/api/sources/sections/search?q=limit", null));
        JsonNode mcp = call(contributor, "search_knowledge", Map.of("query", "daily transfer limit", "k", 5));
        JsonNode rest = rest("POST", "/api/retrieval/search", "{\"query\": \"daily transfer limit\", \"k\": 5}");
        assertThat(ids(mcp.get("hits"))).isNotEmpty().isEqualTo(ids(rest.get("hits")));
    }

    @Test
    void readersCannotChangeAnythingAndAnonymousCallersAreRejected() {
        McpSyncClient reader = client(apiKeys.create("reader", List.of(Role.READER), "test").key());
        assertThat(call(reader, "list_journeys", Map.of()).size()).isPositive();
        String question = linkReview();
        for (var call : List.of(
                new CallToolRequest("answer_question", Map.of("questionId", question, "option", "accept")),
                new CallToolRequest("trigger_ingestion", Map.of("pipeline", "code-ingest")),
                new CallToolRequest("start_journey_interview", Map.of("journeyKey", "x-journey")))) {
            CallToolResult result = reader.callTool(call);
            assertThat(result.isError()).as(call.name()).isTrue();
            assertThat(text(result)).as(call.name()).contains("CONTRIBUTOR");
        }
        assertThat(questions.get(question).status()).isEqualTo("OPEN");
        assertThat(count("""
                MATCH (a:AuditEvent {channel: 'MCP', actor: 'key:reader', outcome: 'DENIED'}) RETURN count(a)""")).isEqualTo(3);

        assertThatThrownBy(() -> client(null)).as("no key: the MCP endpoint answers 401").isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client("pke_" + "x".repeat(43))).as("unknown key").isInstanceOf(RuntimeException.class);
    }

    @Test
    void triggerIngestionAndEvolutionRequestsFollowTheExecutionPolicy() {
        // the test profile allows code ingestion but not reindex
        CallToolResult reindex = contributor.callTool(new CallToolRequest("trigger_ingestion", Map.of("pipeline", "reindex")));
        assertThat(reindex.isError()).isTrue();
        assertThat(text(reindex)).contains("execution policy");
        JsonNode execution = call(contributor, "trigger_ingestion",
                Map.of("pipeline", "code-on-commit", "input", Map.of("repository", "transfer-service"), "force", true));
        assertThat(execution.get("pipelineId").asString()).isEqualTo("code-on-commit");
        assertThat(execution.get("actor").asString()).isEqualTo("key:claude-code");

        CallToolResult shell = contributor.callTool(new CallToolRequest("submit_evolution_request",
                Map.of("kind", "RUN_SHELL", "subject", "rm", "reason", "cleanup")));
        assertThat(shell.isError()).isTrue();
        assertThat(text(shell)).contains("execution policy");
        JsonNode request = call(contributor, "submit_evolution_request", Map.of("kind", "NEW_ENTITY_TYPE",
                "subject", "FeatureFlag", "reason", "Journeys depend on feature flags", "details",
                Map.of("properties", List.of("flagKey"))));
        assertThat(request.get("status").asString()).isEqualTo("OPEN");
        assertThat(count("MATCH (r:EvolutionRequest {subject: 'FeatureFlag', requestedBy: 'key:claude-code'}) RETURN count(r)"))
                .isEqualTo(1);
        assertThat(count("""
                MATCH (a:AuditEvent {channel: 'MCP', actor: 'key:claude-code'})
                RETURN count(a)""")).isEqualTo(4);
    }

    @Test
    void answerQuestionAppliesTheDecisionAsHumanVerified() {
        String question = linkReview();
        JsonNode answer = call(contributor, "answer_question",
                Map.of("questionId", question, "option", "accept", "comment", "Confirmed in the code review"));
        assertThat(answer.get("question").get("status").asString()).isEqualTo("ANSWERED");
        assertThat(answer.get("applied").get("items").size()).isPositive();
        assertThat(count("""
                MATCH (a:Assertion {pke_type: 'HANDLED_BY', pke_epistemic: 'HUMAN_VERIFIED'})-[:SUPPORTED_BY]->(e:Evidence)
                WHERE e.excerpt CONTAINS 'Confirmed in the code review' RETURN count(DISTINCT a)""")).isEqualTo(1);
        assertThat(count("MATCH (e:ChangeEvent {actor: 'key:claude-code'}) RETURN count(e)")).isPositive();
    }

    @Test
    void aJourneyInterviewCompletesAJourneyLinkedToKnownApis() {
        JsonNode turn = call(contributor, "start_journey_interview",
                Map.of("journeyKey", "transfer-interview", "name", "Transfer by interview"));
        assertThat(turn.get("status").asString()).isEqualTo("ASKING");
        assertThat(turn.get("question").asString()).containsIgnoringCase("first");
        String session = turn.get("sessionId").asString();

        // the model completes the draft; each step's API matches the gateway and the service API: the engine asks
        turn = answer(session, "They validate the transfer first, then they execute it. Nothing else.");
        assertThat(turn.get("status").asString()).isEqualTo("ASKING");
        assertThat(turn.get("question").asString()).contains("Step 1", "matches 2 APIs", "[gateway gateway]");
        turn = answer(session, "maybe the first?");
        assertThat(turn.get("question").asString()).startsWith("Please answer with a number");
        turn = answer(session, "1");
        assertThat(turn.get("question").asString()).contains("Step 2", "/api/v3/international-transfers/execute");
        turn = answer(session, "1");

        assertThat(turn.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(turn.get("eventsWritten").asInt()).isPositive();
        JsonNode journey = call(contributor, "get_journey", Map.of("idOrKey", "transfer-interview"));
        assertThat(journey.get("steps").size()).isEqualTo(2);
        assertThat(journey.get("steps").get(0).get("apis").get(0).get("pathTemplate").asString()).isEqualTo(VALIDATION);
        assertThat(count("""
                MATCH (:UserJourney {journeyKey: 'transfer-interview'})-[:HAS_STEP]->(:JourneyStep)-[:CALLS_API]->(a:ApiOperation)
                RETURN count(DISTINCT a) + 10 * sum(CASE a.layer WHEN 'gateway' THEN 1 ELSE 0 END)""")).as("two gateway APIs")
                .isEqualTo(22);
        assertThat(journey.get("steps").get(1).get("apis").get(0).get("pathTemplate").asString())
                .isEqualTo("/api/v3/international-transfers/execute");
        assertThat(journey.get("sources").toString()).contains("journey.interview");
        assertThat(count("""
                MATCH (:UserJourney {journeyKey: 'transfer-interview'})-[:HAS_STEP]->(s:JourneyStep)
                MATCH (a:Assertion {pke_type: 'CALLS_API', pke_subjectId: s.pke_id, pke_epistemic: 'HUMAN_VERIFIED'})
                RETURN count(a)""")).isEqualTo(2);
        assertThat(count("MATCH (s:InterviewSession {status: 'COMPLETED'}) RETURN count(s)")).isEqualTo(1);
        CallToolResult again = contributor.callTool(new CallToolRequest("answer_journey_interview",
                Map.of("sessionId", session, "answer", "1")));
        assertThat(again.isError()).isTrue();
    }

    private JsonNode answer(String session, String answer) {
        return call(contributor, "answer_journey_interview", Map.of("sessionId", session, "answer", answer));
    }

    /** A link-review question: the validation service API is handled by TransferService#execute (deliberately odd). */
    private String linkReview() {
        String api = serviceApi(VALIDATION);
        String execute = id("MATCH (m:JavaMethod) WHERE m.signature STARTS WITH 'execute(' RETURN m.pke_id AS id");
        RelationshipWrite write = new RelationshipWrite("HANDLED_BY", EntityRef.id(api), EntityRef.id(execute), Map.of(),
                EpistemicStatus.INFERRED_AI, null,
                List.of(EvidenceInput.deterministic("test", null, "McpIT", "test")));
        return questions.linkReview(write, api, execute, null, "Is the validation API handled by execute?", null, "test");
    }

    private McpSyncClient client(String key) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp").requestBuilder(request).build())
                .requestTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        clients.add(client);
        return client;
    }

    private static JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(new CallToolRequest(tool, new LinkedHashMap<>(arguments)));
        assertThat(result.isError()).as("%s: %s", tool, text(result)).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text(result));
    }

    private static String text(CallToolResult result) {
        return result.content().stream().filter(TextContent.class::isInstance).map(c -> ((TextContent) c).text())
                .findFirst().orElse("");
    }

    private JsonNode rest(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = authorized(URI.create("http://localhost:" + port + path));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void assertEquivalent(JsonNode mcp, JsonNode rest) {
        assertThat(withoutNulls(mcp)).isEqualTo(withoutNulls(rest));
    }

    /** REST omits nothing and MCP may drop nulls (or the reverse): compare the values that are present. */
    private static JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            var copy = JSON.createObjectNode();
            node.properties().forEach(e -> {
                if (!e.getValue().isNull()) {
                    copy.set(e.getKey(), withoutNulls(e.getValue()));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            var copy = JSON.createArrayNode();
            node.forEach(n -> copy.add(withoutNulls(n)));
            return copy;
        }
        return node;
    }

    private static List<String> ids(JsonNode hits) {
        List<String> ids = new ArrayList<>();
        hits.forEach(h -> ids.add(h.get("id").asString()));
        return ids;
    }

    private static String serviceApi(String path) {
        return id("MATCH (a:ApiOperation {layer: 'service', pathTemplate: '" + path + "'}) RETURN a.pke_id AS id");
    }

    private static String id(String cypher) {
        List<Map<String, Object>> rows = rows(cypher, Map.of());
        assertThat(rows).as(cypher).isNotEmpty();
        return (String) rows.getFirst().get("id");
    }
}
