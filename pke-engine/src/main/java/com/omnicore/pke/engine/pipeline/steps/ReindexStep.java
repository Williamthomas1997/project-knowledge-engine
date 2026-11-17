package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.index.IndexMaintenance;
import com.omnicore.pke.engine.index.IndexMaintenance.ReconciliationReport;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code reindex}: after a schema change (new or changed {@code embedding.indexFields}) or for a new embedding model.
 * With {@code model}, builds a new generation (and activates it when {@code activate}, which evaluates it first);
 * then reconciles every collection, which creates collections for new index fields and enqueues missing vectors.
 * Re-embedding never creates knowledge events (ADR-007).
 */
@Component
class ReindexStep implements PipelineStep {

    private final IndexMaintenance maintenance;

    ReindexStep(IndexMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public String name() {
        return "reindex";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        String model = Steps.string(context.input(), "model");
        if (model != null) {
            Generation generation = maintenance.createGeneration(model, true);
            out.put("generation", generation.id());
            out.put("generationStatus", generation.status());
            if (Boolean.TRUE.equals(context.input().get("activate"))) {
                out.put("activation", maintenance.activate(generation.id(), false));
            }
        }
        ReconciliationReport report = maintenance.reconcile();
        out.put("reconciliation", report.operationId());
        out.put("enqueued", report.enqueued());
        out.put("orphansDeleted", report.orphansDeleted());
        return out;
    }
}
