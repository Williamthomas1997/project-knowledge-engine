package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.graph.IndexTaskStore;
import com.omnicore.pke.engine.graph.IndexTaskStore.Op;
import com.omnicore.pke.engine.graph.IndexTaskStore.Task;
import com.omnicore.pke.engine.graph.VectorCatalogStore;
import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.CollectionRef;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorRecord;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Index upkeep that is not driven by knowledge writes:
 * <ul>
 *   <li><b>Reconciliation</b>: per collection and writable generation, compares the graph (current entities with
 *       a value) with the store's record ids; missing records are enqueued as UPSERT tasks, orphans deleted.</li>
 *   <li><b>Generations</b>: a new generation is built in the background straight from the graph (no tasks, no
 *       knowledge events), then evaluated against the active one and switched only if it is not worse.</li>
 * </ul>
 */
@Service
public class IndexMaintenance {

    private static final Logger log = LoggerFactory.getLogger(IndexMaintenance.class);

    public record ReconciliationReport(String operationId, Map<String, Map<String, Integer>> collections, int enqueued,
                                       int orphansDeleted) {
    }

    private final Collections collections;
    private final EmbeddingGenerations generations;
    private final VectorCatalogStore catalog;
    private final IndexTaskStore tasks;
    private final Vectors vectors;
    private final IndexProperties properties;
    private final ObjectProvider<GenerationEvaluator> evaluator;
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pke-embedding-build");
        t.setDaemon(true);
        return t;
    });
    private volatile ReconciliationReport lastReconciliation;

    public IndexMaintenance(Collections collections, EmbeddingGenerations generations, VectorCatalogStore catalog,
                            IndexTaskStore tasks, Vectors vectors, IndexProperties properties,
                            ObjectProvider<GenerationEvaluator> evaluator) {
        this.collections = collections;
        this.generations = generations;
        this.catalog = catalog;
        this.tasks = tasks;
        this.vectors = vectors;
        this.properties = properties;
        this.evaluator = evaluator;
    }

    @Scheduled(cron = "${pke.index.reconcile-cron:0 30 3 * * *}")
    void scheduledReconciliation() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("Scheduled index reconciliation failed: {}", e.getMessage());
        }
    }

    public ReconciliationReport reconcile() {
        collections.sync();
        String operationId = "reconcile:" + PkeIds.uuidV7();
        Map<String, Map<String, Integer>> report = new LinkedHashMap<>();
        List<Task> missing = new ArrayList<>();
        int orphans = 0;
        for (Collections.ContentCollection c : collections.content()) {
            List<String> entityIds = catalog.indexableOfType(c.type(), c.field()).stream().map(r -> (String) r.get("id")).toList();
            for (Generation generation : generations.writable()) {
                CollectionRef ref = collections.ref(c.name(), generation);
                Map<String, String> expected = new HashMap<>();
                entityIds.forEach(id -> expected.put(Collections.recordId(id, c.field(), generation.id()), id));
                Set<String> actual = vectors.allIds(ref);
                List<String> extra = actual.stream().filter(id -> !expected.containsKey(id)).toList();
                List<String> absent = expected.keySet().stream().filter(id -> !actual.contains(id)).toList();
                absent.forEach(id -> missing.add(new Task(null, expected.get(id), c.type(), c.field(), Op.UPSERT, 0, operationId)));
                vectors.delete(ref, extra);
                orphans += extra.size();
                report.put(c.name() + "@" + generation.id(), Map.of("expected", expected.size(), "indexed", actual.size(),
                        "missing", absent.size(), "orphans", extra.size()));
            }
        }
        // one task per entity and field is enough: the worker writes every writable generation
        Map<String, Task> unique = new LinkedHashMap<>();
        missing.forEach(t -> unique.putIfAbsent(t.entityId() + "|" + t.field(), t));
        tasks.enqueue(List.copyOf(unique.values()), operationId);
        lastReconciliation = new ReconciliationReport(operationId, report, unique.size(), orphans);
        return lastReconciliation;
    }

    public Optional<ReconciliationReport> lastReconciliation() {
        return Optional.ofNullable(lastReconciliation);
    }

    /** Creates a generation and builds it in the background (or before returning when {@code wait}). */
    public Generation createGeneration(String model, boolean wait) {
        Generation generation = generations.create(model);
        collections.sync();
        Future<?> build = builder.submit(() -> build(generation));
        if (wait) {
            try {
                build.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("Building generation " + generation.id() + " failed: " + e.getCause().getMessage(),
                        e.getCause());
            }
        }
        return generations.get(generation.id());
    }

    private void build(Generation generation) {
        int records = 0;
        for (Collections.ContentCollection c : collections.content()) {
            CollectionRef ref = collections.ref(c.name(), generation);
            List<String> ids = catalog.indexableOfType(c.type(), c.field()).stream().map(r -> (String) r.get("id")).toList();
            for (int from = 0; from < ids.size(); from += properties.batchSize()) {
                List<VectorRecord> batch = new ArrayList<>();
                for (String id : ids.subList(from, Math.min(ids.size(), from + properties.batchSize()))) {
                    catalog.indexable(id, c.field()).ifPresent(data -> batch.add(new VectorRecord(
                            Collections.recordId(id, c.field(), generation.id()), String.valueOf(data.get("text")),
                            Collections.metadata(id, String.valueOf(data.get("type")), c.name(),
                                    Map.of("project", properties.project())))));
                }
                vectors.upsert(ref, batch);
                records += batch.size();
            }
        }
        collections.sync();
        generations.markReady(generation.id());
        log.info("Embedding generation {} ({}) built: {} records", generation.id(), generation.model(), records);
    }

    /**
     * Switches searches to a READY generation. It is evaluated first (evaluation datasets, recall@k against the active
     * generation); a worse generation is refused unless {@code force}.
     */
    public Map<String, Object> activate(String id, boolean force) {
        Generation candidate = generations.get(id);
        if (!"READY".equals(candidate.status())) {
            throw new ConflictException("Generation " + id + " is " + candidate.status() + "; only READY generations can be activated");
        }
        Generation active = generations.active();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidate", id);
        result.put("active", active.id());
        GenerationEvaluator eval = evaluator.getIfAvailable();
        if (eval != null) {
            double current = eval.recall(active.id());
            double proposed = eval.recall(id);
            result.put("activeRecall", current);
            result.put("candidateRecall", proposed);
            if (proposed + 1e-9 < current && !force) {
                generations.recordEvaluation(id, "READY", CanonicalJson.write(result));
                throw new ConflictException("Generation " + id + " is worse than " + active.id() + " (recall " + proposed
                        + " < " + current + "); use force=true to switch anyway");
            }
        } else if (!force) {
            throw new ConflictException("No evaluator is available; use force=true to switch without evaluation");
        }
        generations.activate(id, CanonicalJson.write(result));
        result.put("activated", true);
        return result;
    }

    @PreDestroy
    void shutdown() {
        builder.shutdownNow();
    }

    /** Evaluates retrieval against a given generation; implemented by the evaluation runner. */
    public interface GenerationEvaluator {

        /** Mean recall@k over the evaluation datasets when searching generation {@code generationId}. */
        double recall(String generationId);
    }
}
