package com.omnicore.pke.engine.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.KnowledgeIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR-015 over HTTP: proposal writes are invisible to default reads; approve promotes with history; reject keeps it. */
class ProposalIT extends KnowledgeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = authorized(URI.create("http://localhost:" + port + path))
                .header("X-PKE-Actor", "proposal-it");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private JsonNode ok(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return JSON.readTree(r.body());
    }

    private static String feature(String key, String name) {
        return "{\"type\": \"BusinessFeature\", \"properties\": {\"featureKey\": \"" + key + "\", \"name\": \"" + name
                + "\"}, \"epistemic\": \"OBSERVED\"}";
    }

    private static String dependency(String from, String to) {
        return "{\"type\": \"DEPENDS_ON_FEATURE\", \"subject\": {\"type\": \"BusinessFeature\", \"key\": {\"featureKey\": \""
                + from + "\"}}, \"object\": {\"type\": \"BusinessFeature\", \"key\": {\"featureKey\": \"" + to + "\"}},"
                + " \"properties\": {\"rationale\": \"needs it\"}, \"epistemic\": \"OBSERVED\", \"evidence\": [{"
                + "\"sourceArtifactId\": \"kgp:run-1\", \"sourceVersion\": \"1\", \"location\": \"P-1\","
                + " \"extractionMethod\": \"kgp.points\"}]}";
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(i -> names.add(i.get("properties").get("name").asString()));
        return names;
    }

    private String mainFeature() throws Exception {
        JsonNode r = ok(send("POST", "/api/entities", feature("transfer", "Transfer")), 201);
        return r.get("items").get(0).get("id").asString();
    }

    private void createProposal(String id) throws Exception {
        JsonNode p = ok(send("POST", "/api/proposals",
                "{\"id\": \"" + id + "\", \"title\": \"Run " + id + "\", \"sourceRun\": \"kgp-" + id + "\"}"), 201);
        assertThat(p.get("scope").asString()).isEqualTo("proposal:" + id);
        assertThat(p.get("status").asString()).isEqualTo("OPEN");
    }

    private JsonNode writeInProposal(String id) throws Exception {
        return writeInProposal(id, 201);
    }

    private JsonNode writeInProposal(String id, int status) throws Exception {
        return ok(send("POST", "/api/knowledge/changes?scope=proposal:" + id, "{\"entities\": ["
                + feature("transfer", "Transfer v2") + ", " + feature("limits", "Limits") + "],"
                + " \"relationships\": [" + dependency("transfer", "limits") + "]}"), status);
    }

    @Test
    void aProposalWriteIsInvisibleToDefaultReads() throws Exception {
        String transferId = mainFeature();
        createProposal("p1");
        JsonNode written = writeInProposal("p1");
        assertThat(written.get("items")).hasSize(3);
        String limitsId = written.get("items").get(1).get("id").asString();
        long eventsBefore = get("/api/history/events?limit=200").size();

        // default reads: main only
        JsonNode main = get("/api/entities?type=BusinessFeature");
        assertThat(main.get("total").asLong()).isEqualTo(1);
        assertThat(names(main)).containsExactly("Transfer");
        assertThat(main.get("items").get(0).get("scope").asString()).isEqualTo("main");
        assertThat(send("GET", "/api/entities/" + limitsId, null).statusCode()).isEqualTo(404);
        assertThat(get("/api/entities/" + transferId + "/relationships")).isEmpty();
        assertThat(get("/api/integrity").get("violations").isEmpty()).isTrue();

        // widened reads: the proposal overlays main
        JsonNode scoped = get("/api/entities?type=BusinessFeature&scope=proposal:p1");
        assertThat(scoped.get("total").asLong()).isEqualTo(2);
        assertThat(names(scoped)).containsExactly("Transfer v2", "Limits");
        assertThat(get("/api/entities/" + limitsId + "?scope=proposal:p1").get("status").asString()).isEqualTo("PROPOSED");
        assertThat(names(get("/api/entities?type=BusinessFeature&includeProposals=true"))).contains("Limits");
        JsonNode rels = get("/api/entities/" + transferId + "/relationships?scope=proposal:p1");
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).get("scope").asString()).isEqualTo("proposal:p1");

        // the diff
        JsonNode diff = get("/api/proposals/p1");
        assertThat(diff.get("entities").get("added")).hasSize(1);
        assertThat(diff.get("entities").get("changed")).hasSize(1);
        assertThat(diff.get("entities").get("changed").get(0).get("mainProperties").get("name").asString()).isEqualTo("Transfer");
        assertThat(diff.get("relationships").get("added")).hasSize(1);

        // writing the same content again is a no-op
        JsonNode again = writeInProposal("p1", 200);
        again.get("items").forEach(i -> assertThat(i.get("outcome").asString()).isEqualTo("UNCHANGED"));
        assertThat(get("/api/history/events?limit=200").size()).isEqualTo(eventsBefore);
    }

    @Test
    void approveMakesItVisibleWithHistory() throws Exception {
        String transferId = mainFeature();
        createProposal("p2");
        writeInProposal("p2");

        JsonNode decision = ok(send("POST", "/api/proposals/p2/approve", "{\"comment\": \"looks right\"}"), 200);
        assertThat(decision.get("proposal").get("status").asString()).isEqualTo("APPROVED");
        assertThat(decision.get("proposal").get("decidedBy").asString()).isEqualTo("key:test-admin/proposal-it");
        assertThat(decision.get("applied").get("items")).hasSize(3);

        JsonNode main = get("/api/entities?type=BusinessFeature");
        assertThat(names(main)).containsExactlyInAnyOrder("Transfer v2", "Limits");
        assertThat(get("/api/entities/" + transferId + "/versions")).hasSize(2);
        assertThat(get("/api/entities/" + transferId + "/relationships")).hasSize(1);
        assertThat(get("/api/integrity").get("violations").isEmpty()).isTrue();

        // every event of the approval carries the proposal id and the approver
        List<JsonNode> approval = new ArrayList<>();
        get("/api/history/events?limit=200").forEach(e -> {
            if ("p2".equals(e.path("details").path("proposalId").asString(null))
                    && !e.get("type").asString().startsWith("PROPOSAL_ITEM")) {
                approval.add(e);
            }
        });
        assertThat(approval).extracting(e -> e.get("type").asString())
                .contains("ENTITY_UPDATED", "ENTITY_CREATED", "RELATIONSHIP_CREATED", "PROPOSAL_APPROVED");
        approval.forEach(e -> assertThat(e.get("details").get("approvedBy").asString()).isEqualTo("key:test-admin/proposal-it"));
        assertThat(approval).extracting(e -> e.get("operationId").asString()).containsOnly("proposal:p2:approve");

        // a decided proposal accepts nothing more
        assertThat(send("POST", "/api/entities?scope=proposal:p2", feature("x", "X")).statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/proposals/p2/approve", null).statusCode()).isEqualTo(409);
    }

    @Test
    void rejectKeepsTheHistory() throws Exception {
        mainFeature();
        createProposal("p3");
        writeInProposal("p3");

        JsonNode rejected = ok(send("POST", "/api/proposals/p3/reject", "{\"reason\": \"wrong limit\"}"), 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("reason").asString()).isEqualTo("wrong limit");
        assertThat(rejected.get("itemCount").asLong()).isEqualTo(3);

        assertThat(names(get("/api/entities?type=BusinessFeature"))).containsExactly("Transfer");
        assertThat(names(get("/api/entities?type=BusinessFeature&scope=proposal:p3"))).containsExactly("Transfer");
        JsonNode diff = get("/api/proposals/p3");
        assertThat(diff.get("entities").get("added").get(0).get("itemStatus").asString()).isEqualTo("REJECTED");

        List<String> types = new ArrayList<>();
        get("/api/history/events?limit=200").forEach(e -> types.add(e.get("type").asString()));
        assertThat(types).contains("PROPOSAL_CREATED", "PROPOSAL_ITEM_RECORDED", "PROPOSAL_REJECTED");
        assertThat(send("POST", "/api/entities?scope=proposal:p3", feature("x", "X")).statusCode()).isEqualTo(409);
    }

    @Test
    void removalsAndUpdatesAreTracked() throws Exception {
        mainFeature();
        ok(send("POST", "/api/entities", feature("limits", "Limits")), 201);
        JsonNode rel = ok(send("POST", "/api/relationships", dependency("transfer", "limits")), 201);
        String relationshipId = rel.get("items").get(0).get("id").asString();
        createProposal("p4");

        ok(send("POST", "/api/relationships/" + relationshipId + "/invalidate?scope=proposal:p4",
                "{\"reason\": \"no longer needed\"}"), 200);
        JsonNode first = ok(send("POST", "/api/entities?scope=proposal:p4", feature("fees", "Fees")), 201);
        JsonNode updated = ok(send("POST", "/api/entities?scope=proposal:p4", feature("fees", "Fees and charges")), 200);
        assertThat(updated.get("items").get(0).get("outcome").asString()).isEqualTo("UPDATED");
        assertThat(updated.get("items").get(0).get("version").asLong()).isEqualTo(2);
        assertThat(updated.get("items").get(0).get("id").asString()).isEqualTo(first.get("items").get(0).get("id").asString());

        JsonNode diff = get("/api/proposals/p4");
        assertThat(diff.get("relationships").get("removed")).hasSize(1);
        assertThat(get("/api/relationships/" + relationshipId).get("status").asString()).isEqualTo("CURRENT");
        assertThat(get("/api/relationships/" + relationshipId + "?scope=proposal:p4").get("status").asString())
                .isEqualTo("PROPOSED_INVALIDATION");

        ok(send("POST", "/api/proposals/p4/approve", null), 200);
        assertThat(get("/api/relationships/" + relationshipId).get("status").asString()).isEqualTo("INVALID");
        assertThat(names(get("/api/entities?type=BusinessFeature"))).contains("Fees and charges");
    }

    @Test
    void badScopesAndDanglingEndpointsAreRejected() throws Exception {
        mainFeature();
        createProposal("p5");
        assertThat(send("GET", "/api/entities?scope=draft", null).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/entities?scope=proposal:nope", feature("a", "A")).statusCode()).isEqualTo(404);
        HttpResponse<String> dangling = send("POST", "/api/relationships?scope=proposal:p5", dependency("transfer", "ghost"));
        assertThat(dangling.statusCode()).as(dangling.body()).isEqualTo(400);
        assertThat(dangling.body()).contains("neither in main nor in proposal p5");
        assertThat(send("POST", "/api/proposals", "{\"id\": \"p5\", \"title\": \"dup\"}").statusCode()).isEqualTo(409);
    }
}
