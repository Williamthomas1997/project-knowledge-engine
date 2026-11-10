package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.graph.IndexTaskStore;
import com.omnicore.pke.engine.graph.IngestionRunStore;
import com.omnicore.pke.engine.graph.PipelineExecutionStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Ties ingestion runs and pipeline executions to their index tasks (ADR-007 §3): an operation is COMPLETED only
 * when its tasks are done. {@link #await} is called at the end of a run; if tasks are still open the run ends
 * {@code PARTIAL} with {@code indexPending = true}, and {@link #indexed} completes it when the worker finishes.
 */
@Service
public class OperationIndexing {

    private final IndexTaskStore tasks;
    private final IngestionRunStore runs;
    private final PipelineExecutionStore executions;
    private final IndexWorker worker;
    private final IndexProperties properties;

    public OperationIndexing(IndexTaskStore tasks, IngestionRunStore runs, PipelineExecutionStore executions,
                             @Lazy IndexWorker worker, IndexProperties properties) {
        this.tasks = tasks;
        this.runs = runs;
        this.executions = executions;
        this.worker = worker;
        this.properties = properties;
    }

    /** Waits (processing tasks) up to {@code pke.index.await-timeout}; returns the number of tasks still open. */
    public long await(String operationId) {
        return await(operationId, properties.awaitTimeout());
    }

    public long await(String operationId, Duration timeout) {
        return worker.drain(operationId, timeout);
    }

    public long open(String operationId) {
        return tasks.open(operationId);
    }

    /** Called by the worker when the operation's last index task is done. */
    void indexed(String operationId) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", "COMPLETED");
        fields.put("indexPending", false);
        fields.put("indexedAt", OffsetDateTime.now(ZoneOffset.UTC));
        runs.find(operationId).filter(r -> r.status() == IngestionRunStore.Status.PARTIAL)
                .filter(r -> runs.indexPending(operationId)).ifPresent(r -> runs.update(operationId, fields));
        executions.find(operationId).filter(e -> "PARTIAL".equals(e.status()) && e.indexPending()).ifPresent(e -> {
            Map<String, Object> f = new HashMap<>(fields);
            f.put("error", null);
            executions.updateExecution(operationId, f);
        });
    }
}
