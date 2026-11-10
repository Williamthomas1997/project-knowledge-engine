package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.IndexTaskStore;
import com.omnicore.pke.engine.graph.VectorCatalogStore;
import com.omnicore.pke.engine.index.EmbeddingGenerations;
import com.omnicore.pke.engine.index.IndexMaintenance;
import com.omnicore.pke.engine.ops.SyncStatusService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Synchronisation status (MASTER_SPEC §19.4), index reconciliation and embedding generations. */
@RestController
@RequestMapping("/api")
class SyncController {

    private final SyncStatusService status;
    private final IndexMaintenance maintenance;
    private final EmbeddingGenerations generations;
    private final IndexTaskStore tasks;
    private final VectorCatalogStore catalog;

    SyncController(SyncStatusService status, IndexMaintenance maintenance, EmbeddingGenerations generations,
                   IndexTaskStore tasks, VectorCatalogStore catalog) {
        this.status = status;
        this.maintenance = maintenance;
        this.generations = generations;
        this.tasks = tasks;
        this.catalog = catalog;
    }

    @GetMapping("/sync/status")
    Map<String, Object> status() {
        return status.status();
    }

    /** Compares graph and vector store per collection; enqueues missing records, deletes orphans. */
    @PostMapping("/sync/reconcile")
    IndexMaintenance.ReconciliationReport reconcile() {
        return maintenance.reconcile();
    }

    /** FAILED index tasks become PENDING again. */
    @PostMapping("/sync/retry-failed")
    Map<String, Object> retryFailed() {
        return Map.of("requeued", tasks.retryFailed());
    }

    @GetMapping("/vector/collections")
    List<Map<String, Object>> collections() {
        return catalog.collections();
    }

    @GetMapping("/embeddings/generations")
    List<VectorCatalogStore.Generation> generations() {
        return generations.all();
    }

    /** {@code {model?}} (e.g. {@code hashing-256} or {@code spring-ai}): builds a new generation in the background. */
    @PostMapping("/embeddings/generations")
    VectorCatalogStore.Generation create(@RequestBody(required = false) Map<String, String> body,
                                         @RequestParam(defaultValue = "false") boolean wait) {
        return maintenance.createGeneration(body == null ? null : body.get("model"), wait);
    }

    /** Evaluates the READY generation against the active one and switches only if it is not worse (or {@code force}). */
    @PostMapping("/embeddings/generations/{id}/activate")
    Map<String, Object> activate(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force) {
        return maintenance.activate(id, force);
    }
}
