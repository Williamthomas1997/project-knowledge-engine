package com.omnicore.pke.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.HistoryQueries;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemState;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.graph.WriteStage;
import com.omnicore.pke.engine.ingest.IngestionRunService;
import com.omnicore.pke.engine.ingest.IngestionRunService.Kind;
import com.omnicore.pke.engine.ingest.IngestionRunService.Mode;
import com.omnicore.pke.engine.ingest.IngestionRunService.Request;
import com.omnicore.pke.engine.ingest.RepositoryIngestionService;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.pipeline.PipelineExecutor;
import com.omnicore.pke.engine.source.DocumentStore;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.support.Demo;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.DemoCorpus.DemoRepo;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M5 history: reconstruction as of a seq, snapshots and their diff after a real code change (through
 * {@code code-on-commit}), commit → knowledge, a failed migration restored from its checkpoint, and the JSONL export.
 */
class HistoryIT extends KnowledgeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE = "com.demo.transfer.TransferService";

    @Autowired
    GraphStore store;

    @Autowired
    HistoryQueries history;

    @Autowired
    HistoryExporter exporter;

    @Autowired
    DocumentStore documents;

    @Autowired
    RepositoryIngestionService repositories;

    @Autowired
    IngestionRunService runs;

    @Autowired
    PipelineExecutor executor;

    private HttpResponse<String> http(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = authorized(URI.create("http://localhost:" + port + path))
                .header("X-PKE-Actor", "history-it");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String method, String path, String body) throws Exception {
        HttpResponse<String> response = http(method, path, body);
        assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isBetween(200, 299);
        return JSON.readTree(response.body());
    }

    private static Map<String, Object> props(List<ItemState> state, String id) {
        return state.stream().filter(s -> s.id().equals(id)).map(ItemState::properties).findFirst().orElse(null);
    }

    @Test
    void stateAsOfASeqIsReconstructedFromVersions() throws Exception {
        String a = PkeIds.entityId("JavaMethod", List.of(Demo.REPO, SERVICE, "a()"));
        String b = PkeIds.entityId("JavaMethod", List.of(Demo.REPO, SERVICE, "b()"));
        long s1 = store.apply(ChangeSet.of(Demo.method(SERVICE, "a()", "returnType", "String")), "t").lastSeq();
        long s2 = store.apply(ChangeSet.of(Demo.method(SERVICE, "a()", "returnType", "Long"),
                Demo.method(SERVICE, "b()")), "t").lastSeq();
        ChangeResult call = store.apply(new ChangeSet(null, List.of(),
                List.of(Demo.calls(SERVICE, "a()", SERVICE, "b()", "c3")), List.of()), "t");
        long s3 = call.lastSeq();
        String relationship = call.items().getFirst().id();
        long s4 = store.invalidate(new RelationshipInvalidation(relationship, "removed in c4", null, List.of()), "t").lastSeq();

        List<ItemState> at1 = history.stateAt(s1, null, 100);
        assertThat(props(at1, a)).containsEntry("returnType", "String");
        assertThat(props(at1, b)).isNull();

        List<ItemState> at2 = history.stateAt(s2, null, 100);
        assertThat(props(at2, a)).containsEntry("returnType", "Long");
        assertThat(props(at2, b)).isNotNull();
        assertThat(props(at2, relationship)).isNull();

        assertThat(history.stateAt(s3, "CALLS", 100)).extracting(ItemState::id).containsExactly(relationship);
        assertThat(history.stateAt(s4, "CALLS", 100)).isEmpty();

        JsonNode rest = ok("GET", "/api/history/state?seq=" + s1 + "&type=JavaMethod", null);
        assertThat(rest.size()).isEqualTo(1);
        assertThat(rest.get(0).get("properties").get("returnType").asString()).isEqualTo("String");

        JsonNode diff = ok("GET", "/api/history/diff?fromSeq=" + s1 + "&toSeq=" + s4, null);
        Map<String, String> changes = new java.util.TreeMap<>();
        diff.forEach(c -> changes.put(c.get("id").asString(), c.get("change").asString()));
        assertThat(changes).containsEntry(a, "CHANGED").containsEntry(b, "ADDED").doesNotContainKey(relationship);
        assertThat(http("GET", "/api/history/diff?fromSeq=5&toSeq=1", null).statusCode()).isEqualTo(400);
    }

    @Test
    void codeOnCommitSnapshotsUatAndTheDiffShowsTheRemovedAndAddedCall() throws Exception {
        DemoRepo repo = DemoCorpus.repository("transfer-service");
        String c1 = repo.head();
        repositories.register("transfer-service", repo.dir().toString(), "transfer-service", "t");
        assertThat(runs.start(new Request(Kind.REPOSITORY, "transfer-service", Mode.FULL), "t", true).status().name())
                .isEqualTo("COMPLETED");
        JsonNode before = ok("POST", "/api/snapshots", "{\"label\": \"dev c1\"}");
        assertThat(before.get("contents").get("sources").get("repo:transfer-service").asString()).isEqualTo(c1);
        assertThat(before.get("contents").get("schema").get("RelationshipType:CALLS").asString()).isNotBlank();
        assertThat(before.get("contents").get("queryTemplates").get("log-trail-of-api").asString()).isEqualTo("1.1.0");
        assertThat(before.get("contents").get("appRelease").asString()).isNotBlank();

        String c2 = repo.applyAndCommit("transfer-service-changes/c2", "c2: currency policy");
        ExecutionView execution = executor.start("code-on-commit",
                Map.of("repository", "transfer-service", "tag", "uat"), "t", false, true);
        assertThat(execution.status()).as("%s %s", execution.error(), execution.steps()).isEqualTo("COMPLETED");
        JsonNode snapshotStep = JSON.readTree(execution.steps().stream().filter(s -> s.stepId().equals("snapshot"))
                .findFirst().orElseThrow().outputJson());
        assertThat(snapshotStep.get("created").asBoolean()).isTrue();
        String after = snapshotStep.get("snapshotId").asString();
        assertThat(ok("GET", "/api/snapshots/" + after, null).get("label").asString()).isEqualTo("uat");

        JsonNode diff = ok("GET", "/api/snapshots/diff?from=" + before.get("id").asString() + "&to=" + after, null);
        String validateCurrency = SERVICE + "#validateCurrency(String)";
        assertThat(edges(diff.get("relationships").get("INVALIDATED").get("CALLS")))
                .contains(validateCurrency + " -> com.demo.transfer.CurrencyConverter#isSupported(String)");
        assertThat(edges(diff.get("relationships").get("ADDED").get("CALLS")))
                .contains(validateCurrency + " -> com.demo.transfer.CurrencyPolicy#isAllowed(String)");
        JsonNode removed = first(diff.get("relationships").get("INVALIDATED").get("CALLS"),
                "com.demo.transfer.CurrencyConverter#isSupported(String)");
        assertThat(removed.get("reason").asString()).contains("no longer found");
        JsonNode added = first(diff.get("relationships").get("ADDED").get("CALLS"), "com.demo.transfer.CurrencyPolicy#isAllowed(String)");
        assertThat(added.get("evidence").get(0).get("sourceVersion").asString()).isEqualTo(c2);
        assertThat(diff.get("configuration").get("sources").get("repo:transfer-service").get("from").asString()).isEqualTo(c1);
        assertThat(diff.get("configuration").get("sources").get("repo:transfer-service").get("to").asString()).isEqualTo(c2);

        // git -> knowledge: the run that processed c2 wrote these events
        JsonNode commit = ok("GET", "/api/commits/" + c2.substring(0, 10) + "/knowledge-changes", null);
        assertThat(commit.get("counts").get("RELATIONSHIP_INVALIDATED").asLong()).isPositive();
        assertThat(commit.get("counts").get("RELATIONSHIP_CREATED").asLong()).isPositive();
        assertThat(ok("GET", "/api/history/events?commit=" + c1, null).size()).isPositive();
        assertThat(http("GET", "/api/commits/not-a-sha/knowledge-changes", null).statusCode()).isEqualTo(400);

        // an untagged commit makes no snapshot
        repo.applyAndCommit("transfer-service-changes/c3", "c3: rename");
        ExecutionView dev = executor.start("code-on-commit", Map.of("repository", "transfer-service", "tag", "dev"), "t",
                false, true);
        assertThat(dev.status()).isEqualTo("COMPLETED");
        assertThat(count("MATCH (s:Snapshot) RETURN count(s)")).isEqualTo(2);
    }

    /** "subject -> object" as fqcn#signature of the two methods, for every change in the list. */
    private List<String> edges(JsonNode changes) {
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode change : changes) {
            JsonNode state = change.get("after").isNull() ? change.get("before") : change.get("after");
            result.add(method(state.get("subjectId").asString()) + " -> " + method(state.get("objectId").asString()));
        }
        return result;
    }

    private JsonNode first(JsonNode changes, String objectMethod) {
        for (JsonNode change : changes) {
            JsonNode state = change.get("after").isNull() ? change.get("before") : change.get("after");
            if (method(state.get("objectId").asString()).equals(objectMethod)) {
                return change;
            }
        }
        throw new AssertionError("no change to " + objectMethod + " in " + changes);
    }

    private static String method(String id) {
        return rows("MATCH (m:JavaMethod {pke_id: $id}) RETURN m.fqcn + '#' + m.signature AS s", Map.of("id", id))
                .getFirst().get("s").toString();
    }

    @Test
    void aFailedMigrationRestoresTheCheckpoint() throws Exception {
        store.apply(ChangeSet.of(Demo.method(SERVICE, "a()"), Demo.method(SERVICE, "b()"), Demo.method(SERVICE, "c()")), "t");
        String migration = """
                {"id": "%s", "entityType": "JavaMethod", "batchSize": 1,
                 "operations": [{"op": "setDefault", "property": "summary", "value": "Not summarised yet"}]}""";

        faults.failOnceAfter(WriteStage.AFTER_VERSION, 1); // the first batch commits, the second fails
        JsonNode failed = ok("POST", "/api/schema/migrations", migration.formatted("m1"));
        assertThat(failed.get("status").asString()).isEqualTo("RESTORED");
        assertThat(failed.get("batches").asInt()).isEqualTo(1);
        assertThat(failed.get("error").asString()).contains("Injected failure");
        long checkpoint = failed.get("checkpointSeq").asLong();
        assertThat(failed.get("restoreFirstSeq").asLong()).isGreaterThan(checkpoint);

        Map<String, Map<String, Object>> atCheckpoint = history.stateAt(checkpoint, "JavaMethod", 100).stream()
                .collect(Collectors.toMap(ItemState::id, ItemState::properties));
        Map<String, Map<String, Object>> now = history.currentEntities("JavaMethod").stream()
                .collect(Collectors.toMap(ItemState::id, ItemState::properties));
        assertThat(now).isEqualTo(atCheckpoint);
        assertThat(now.values()).allSatisfy(p -> assertThat(p).doesNotContainKey("summary"));
        // nothing was deleted: the committed batch and its reversal are both history
        assertThat(count("MATCH (e:ChangeEvent {operationId: 'migration:m1'}) RETURN count(e)")).isEqualTo(1);
        assertThat(count("MATCH (e:ChangeEvent) WHERE e.operationId STARTS WITH 'restore:' RETURN count(e)")).isEqualTo(1);
        assertThat(count("MATCH (s:Snapshot {label: 'checkpoint:migration:m1'}) RETURN count(s)")).isEqualTo(1);

        JsonNode done = ok("POST", "/api/schema/migrations", migration.formatted("m2"));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("migrated").asInt()).isEqualTo(3);
        assertThat(history.currentEntities("JavaMethod")).allSatisfy(e ->
                assertThat(e.properties()).containsEntry("summary", "Not summarised yet"));
        assertThat(http("POST", "/api/schema/migrations",
                "{\"id\": \"m3\", \"entityType\": \"JavaMethod\", \"operations\": [{\"op\": \"renameProperty\", "
                        + "\"from\": \"signature\", \"to\": \"summary\"}]}").statusCode()).isEqualTo(400);
    }

    @Test
    void changeEventsAreExportedAsJsonlExactlyOnce() throws Exception {
        store.apply(ChangeSet.of(Demo.method(SERVICE, "a()"), Demo.method(SERVICE, "b()")), "t");
        HistoryExporter.Export first = exporter.export().orElseThrow();
        assertThat(first.fromSeq()).isZero();
        assertThat(first.events()).isEqualTo(first.toSeq());
        List<String> lines = read(first.ref());
        assertThat(lines).hasSize((int) first.events());
        for (int i = 0; i < lines.size(); i++) {
            assertThat(JSON.readTree(lines.get(i)).get("seq").asLong()).isEqualTo(i + 1);
        }
        assertThat(exporter.export()).isEmpty();

        store.apply(ChangeSet.of(Demo.method(SERVICE, "c()")), "t");
        JsonNode second = ok("POST", "/api/history/exports", null).get("export");
        assertThat(second.get("fromSeq").asLong()).isEqualTo(first.toSeq());
        List<String> more = read(second.get("ref").asString());
        assertThat(JSON.readTree(more.getFirst()).get("seq").asLong()).isEqualTo(first.toSeq() + 1);
        assertThat(JSON.readTree(more.getFirst()).get("type").asString()).isEqualTo("ENTITY_CREATED");
        assertThat(ok("GET", "/api/history/exports", null).size()).isEqualTo(2);
    }

    private List<String> read(String ref) throws IOException {
        try (InputStream in = documents.open(ref)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
