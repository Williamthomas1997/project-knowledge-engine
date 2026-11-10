package com.omnicore.pke.engine.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.ingest.DocumentIngestionService;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.ops.SyncStatusService;
import com.omnicore.pke.engine.pipeline.PipelineExecutor;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.KnowledgeIT;
import com.omnicore.pke.engine.vectorstore.VectorStorePort;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The vector outbox, the worker, generations and reconciliation (ADR-007 §3, M4 prompt §2 and §5). */
class IndexSyncIT extends KnowledgeIT {

    @Autowired
    GraphStore graph;

    @Autowired
    IndexWorker worker;

    @Autowired
    VectorStorePort store;

    @Autowired
    Collections collections;

    @Autowired
    EmbeddingGenerations generations;

    @Autowired
    IndexMaintenance maintenance;

    @Autowired
    PipelineExecutor pipelines;

    @Autowired
    DocumentIngestionService documents;

    @Autowired
    SyncStatusService sync;

    private static final EvidenceInput EVIDENCE = EvidenceInput.deterministic("doc:policy", "v1", "s1", "manual");

    private ChangeResult card(String id, String text) {
        return graph.apply(ChangeSet.of(new EntityWrite("KnowledgeCard", id,
                Map.of("text", text, "kind", "rule", "documentKey", "doc:policy"), EpistemicStatus.OBSERVED, null,
                List.of(EVIDENCE))), "t");
    }

    private VectorStorePort.CollectionRef cards(String generation) {
        return collections.ref("KnowledgeCard_text", generations.get(generation));
    }

    private static long openTasks() {
        return count("MATCH (t:IndexTask) RETURN count(t)");
    }

    @Test
    void aKnowledgeWriteCreatesItsIndexTaskInTheSameTransactionAndTheWorkerAppliesIt() {
        ChangeResult created = card("card-1", "Transfers above QAR 1,000 need an OTP.");
        assertThat(count("MATCH (t:IndexTask {entityId: 'card-1', field: 'text', op: 'UPSERT', operationId: $op}) RETURN count(t)",
                Map.of("op", created.operationId()))).isEqualTo(1);
        // a write that does not change the indexed field creates no task
        graph.apply(ChangeSet.of(new EntityWrite("KnowledgeCard", "card-1",
                Map.of("text", "Transfers above QAR 1,000 need an OTP.", "kind", "constraint", "documentKey", "doc:policy"),
                EpistemicStatus.OBSERVED, null, List.of(EVIDENCE))), "t");
        assertThat(openTasks()).isEqualTo(1);

        worker.drain(created.operationId(), Duration.ofSeconds(10));
        assertThat(openTasks()).isZero();
        String recordId = Collections.recordId("card-1", "text", "g1");
        assertThat(store.existingIds(cards("g1"), List.of(recordId))).containsExactly(recordId);
        assertThat(store.search(cards("g1"), "OTP for transfers", 3, 0.0, Map.of("status", "CURRENT")))
                .first().satisfies(h -> {
                    assertThat(h.pkeId()).isEqualTo("card-1");
                    assertThat(h.metadata()).containsEntry("type", "KnowledgeCard").containsEntry("collection", "KnowledgeCard_text")
                            .containsKeys("project", "epistemic", "status", "sourceVersionId");
                });
    }

    @Test
    void invalidatingAnEntityDeletesItsVectors() {
        ChangeResult created = card("card-2", "A beneficiary can be added from the mobile app.");
        worker.drain(created.operationId(), Duration.ofSeconds(10));
        String recordId = Collections.recordId("card-2", "text", "g1");
        assertThat(store.existingIds(cards("g1"), List.of(recordId))).isNotEmpty();

        ChangeResult invalidated = graph.apply(new ChangeSet(null, List.of(), List.of(), List.of(), List.of(), false,
                List.of(new EntityInvalidation("card-2", "superseded", null, List.of()))), "t");
        assertThat(count("MATCH (t:IndexTask {entityId: 'card-2', op: 'DELETE'}) RETURN count(t)")).isEqualTo(1);
        worker.drain(invalidated.operationId(), Duration.ofSeconds(10));
        assertThat(store.existingIds(cards("g1"), List.of(recordId))).isEmpty();
    }

    @Test
    void withTheVectorStoreDownAPipelineEndsPartialAndTheWorkerCompletesIt() throws Exception {
        documents.ingest(Files.readAllBytes(DemoCorpus.file("brd/international-transfer-brd.md")),
                "international-transfer-brd.md", null, null, "t");
        vectorFaults.down = true;
        ExecutionView partial = pipelines.start("brd-ingest", Map.of("documentKey", "doc:international-transfer-brd"), "t",
                false, true);
        assertThat(partial.status()).isEqualTo("PARTIAL");
        assertThat(partial.indexPending()).isTrue();
        assertThat(partial.error()).contains("vector index tasks are still open");
        assertThat(count("MATCH (c:KnowledgeCard) RETURN count(c)")).as("the graph write itself succeeded").isPositive();
        assertThat(count("MATCH (t:IndexTask {status: 'RETRY'}) RETURN count(t)")).isPositive();

        vectorFaults.down = false;
        assertThat(worker.drain(partial.id(), Duration.ofSeconds(15))).isZero();
        ExecutionView completed = pipelines.get(partial.id());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.indexPending()).isFalse();
        long cards = count("MATCH (c:KnowledgeCard) RETURN count(c)");
        assertThat(store.allIds(cards("g1"))).hasSize((int) cards);
    }

    @Test
    void aNewEmbeddingGenerationIsBuiltWithoutKnowledgeEventsAndSwitchedAfterEvaluation() {
        ChangeResult created = card("card-3", "International transfers need a purpose code.");
        worker.drain(created.operationId(), Duration.ofSeconds(10));
        Map<String, Long> before = footprint();

        var g2 = maintenance.createGeneration("hashing-256", true);
        assertThat(g2.status()).isEqualTo("READY");
        assertThat(g2.dimensions()).isEqualTo(256);
        assertThat(footprint()).as("re-embedding never creates knowledge events").isEqualTo(before);
        assertThat(store.existingIds(cards("g2"), List.of(Collections.recordId("card-3", "text", "g2")))).hasSize(1);
        assertThat(count("MATCH (:VectorCollection {name: 'KnowledgeCard_text'})-[:EMBEDDED_WITH]->(g:EmbeddingGeneration) RETURN count(g)"))
                .isEqualTo(2);

        // while both are writable, the worker writes both generations
        ChangeResult another = card("card-4", "A transfer is executed after the OTP check.");
        worker.drain(another.operationId(), Duration.ofSeconds(10));
        assertThat(store.existingIds(cards("g2"), List.of(Collections.recordId("card-4", "text", "g2")))).hasSize(1);

        Map<String, Long> beforeActivation = footprint();
        Map<String, Object> activation = maintenance.activate("g2", false);
        assertThat(activation).containsEntry("activated", true).containsKeys("activeRecall", "candidateRecall");
        assertThat(generations.active().id()).isEqualTo("g2");
        assertThat(generations.get("g1").status()).isEqualTo("RETIRED");
        assertThat(footprint()).as("switching generations writes no knowledge").isEqualTo(beforeActivation);
    }

    @Test
    void reconciliationRepairsMissingAndOrphanedRecords() {
        ChangeResult created = card("card-5", "Exchange rates come from the FX rate service.");
        worker.drain(created.operationId(), Duration.ofSeconds(10));
        String recordId = Collections.recordId("card-5", "text", "g1");
        store.delete(cards("g1"), List.of(recordId));
        String orphan = Collections.recordId("no-such-card", "text", "g1");
        store.upsert(cards("g1"), List.of(new VectorStorePort.VectorRecord(orphan, "orphan", Map.of("pke_id", "no-such-card",
                "type", "KnowledgeCard", "status", "CURRENT"))));

        IndexMaintenance.ReconciliationReport report = maintenance.reconcile();
        assertThat(report.enqueued()).isEqualTo(1);
        assertThat(report.orphansDeleted()).isEqualTo(1);
        worker.drain(report.operationId(), Duration.ofSeconds(10));
        assertThat(store.allIds(cards("g1"))).containsExactly(recordId);

        @SuppressWarnings("unchecked")
        Map<String, Object> index = (Map<String, Object>) sync.status().get("index");
        assertThat(index).containsEntry("provider", "neo4j").containsEntry("activeGeneration", "g1");
        assertThat(sync.status()).containsKeys("sourcesNotProcessed", "sourcesChangedSinceLastSuccess",
                "entitiesWithOnlyObsoleteEvidence", "unfinishedPipelines", "unfinishedIngestionRuns",
                "schemaMigrationsAwaiting", "openQuestions");
    }

    @Test
    void theSystemCollectionsDescribeCollectionsAndTheSchema() {
        var g1 = generations.active();
        assertThat(store.search(collections.ref(Collections.CATALOG, g1), "knowledge card statements from documents", 3, 0.0,
                Map.of())).first().satisfies(h -> assertThat(h.metadata()).containsEntry("target", "KnowledgeCard_text"));
        assertThat(store.search(collections.ref(Collections.SCHEMA_MAP, g1), "which methods implement a requirement", 5, 0.0,
                Map.of())).extracting(h -> h.metadata().get("target")).contains("IMPLEMENTED_BY");
        assertThat(count("MATCH (:VectorCollection {name: 'CollectionCatalog'})-[:DEPENDS_ON]->(:VectorCollection {name: 'KnowledgeCard_text'}) RETURN count(*)"))
                .isEqualTo(1);
        assertThat(count("MATCH (:VectorCollection {name: 'KnowledgeCard_text'})-[:INDEXES]->(:SchemaDefinition {name: 'KnowledgeCard'}) RETURN count(*)"))
                .isPositive();
    }
}
