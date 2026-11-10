package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.graph.IndexTaskStore;
import com.omnicore.pke.engine.graph.IndexTaskStore.Op;
import com.omnicore.pke.engine.graph.IndexTaskStore.Task;
import com.omnicore.pke.engine.graph.VectorCatalogStore;
import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.CollectionRef;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies the vector outbox ({@code :IndexTask}) to every writable embedding generation, idempotently: the latest
 * task per (entity, field) wins and reads the entity's <em>current</em> value, so replaying a task is harmless.
 * A failing batch is retried with exponential backoff. When an operation's last task is done, a run or pipeline
 * that ended PARTIAL only because of pending index tasks becomes COMPLETED.
 */
@Component
public class IndexWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);

    private final IndexTaskStore tasks;
    private final VectorCatalogStore catalog;
    private final EmbeddingGenerations generations;
    private final Collections collections;
    private final Vectors vectors;
    private final IndexProperties properties;
    private final OperationIndexing operations;

    public IndexWorker(IndexTaskStore tasks, VectorCatalogStore catalog, EmbeddingGenerations generations,
                       Collections collections, Vectors vectors, IndexProperties properties, OperationIndexing operations) {
        this.tasks = tasks;
        this.catalog = catalog;
        this.generations = generations;
        this.collections = collections;
        this.vectors = vectors;
        this.properties = properties;
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${pke.index.worker-interval:PT1S}", initialDelayString = "PT5S")
    void scheduled() {
        if (!properties.workerEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.debug("Index worker pass failed: {}", e.toString());
        }
    }

    /** Processes one batch of due tasks; returns how many tasks were handled (done or failed). */
    public synchronized int runOnce() {
        List<Task> due = tasks.due(properties.batchSize());
        if (due.isEmpty()) {
            return 0;
        }
        Map<String, Task> latest = new LinkedHashMap<>();
        due.forEach(t -> latest.put(t.entityId() + "|" + t.field(), t));
        Map<CollectionRef, List<VectorRecord>> upserts = new LinkedHashMap<>();
        Map<CollectionRef, List<String>> deletes = new LinkedHashMap<>();
        List<Generation> writable = generations.writable();
        for (Task task : latest.values()) {
            Optional<Collections.ContentCollection> collection = collections.forField(task.entityType(), task.field());
            if (collection.isEmpty()) {
                continue;
            }
            Optional<Map<String, Object>> data = task.op() == Op.DELETE ? Optional.empty()
                    : catalog.indexable(task.entityId(), task.field());
            boolean keep = data.isPresent() && "CURRENT".equals(data.get().get("status")) && data.get().get("text") != null
                    && !String.valueOf(data.get().get("text")).isBlank();
            for (Generation generation : writable) {
                CollectionRef ref = collections.ref(collection.get().name(), generation);
                String id = Collections.recordId(task.entityId(), task.field(), generation.id());
                if (keep) {
                    upserts.computeIfAbsent(ref, r -> new ArrayList<>()).add(record(id, collection.get().name(), data.get()));
                } else {
                    deletes.computeIfAbsent(ref, r -> new ArrayList<>()).add(id);
                }
            }
        }
        List<String> ids = due.stream().map(Task::id).toList();
        try {
            upserts.forEach(vectors::upsert);
            deletes.forEach(vectors::delete);
        } catch (RuntimeException e) {
            log.warn("Vector store {} unavailable for {} index tasks: {}", vectors.provider(), due.size(), e.getMessage());
            tasks.failed(due, e.getMessage(), properties.retryBackoff().toMillis(), properties.maxAttempts());
            return due.size();
        }
        tasks.done(ids);
        Set<String> touched = new LinkedHashSet<>();
        due.stream().map(Task::operationId).filter(java.util.Objects::nonNull).forEach(touched::add);
        for (String operation : touched) {
            if (tasks.open(operation) == 0) {
                operations.indexed(operation);
            }
        }
        return due.size();
    }

    /**
     * Processes tasks until the operation has none left or the timeout passes. Returns the number still open.
     */
    public long drain(String operationId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long open = tasks.open(operationId);
        while (open > 0 && System.nanoTime() < deadline) {
            if (runOnce() == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            open = tasks.open(operationId);
        }
        return open;
    }

    private VectorRecord record(String id, String collection, Map<String, Object> data) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pke_id", data.get("id"));
        metadata.put("type", data.get("type"));
        metadata.put("collection", collection);
        metadata.put("project", properties.project());
        metadata.put("status", data.get("status"));
        if (data.get("epistemic") != null) {
            metadata.put("epistemic", data.get("epistemic"));
        }
        if (data.get("validAt") != null) {
            metadata.put("validAt", data.get("validAt"));
        }
        if (data.get("sourceVersionId") != null) {
            metadata.put("sourceVersionId", data.get("sourceVersionId"));
        }
        return new VectorRecord(id, String.valueOf(data.get("text")), metadata);
    }
}
