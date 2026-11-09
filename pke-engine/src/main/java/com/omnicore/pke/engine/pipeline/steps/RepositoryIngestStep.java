package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.IngestionRunStore.Run;
import com.omnicore.pke.engine.ingest.IngestionRunService;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code repository-ingest}: wraps the M2 deterministic ingestion (Java extraction, journeys, routes) as a step.
 * A FAILED run fails the step; a PARTIAL run completes it and its failures are reported in the output.
 */
@Component
class RepositoryIngestStep implements PipelineStep {

    private final IngestionRunService runs;

    RepositoryIngestStep(IngestionRunService runs) {
        this.runs = runs;
    }

    @Override
    public String name() {
        return "repository-ingest";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        String repository = Steps.string(context.input(), "repository");
        String mode = Steps.string(context.input(), "mode");
        Run run = runs.start(new IngestionRunService.Request(IngestionRunService.Kind.REPOSITORY, repository,
                mode == null ? IngestionRunService.Mode.INCREMENTAL : IngestionRunService.Mode.valueOf(mode)),
                context.actor(), true);
        if (run.status() == com.omnicore.pke.engine.graph.IngestionRunStore.Status.FAILED) {
            throw new StepFailure(ErrorClass.PERMANENT, "ingestion run " + run.id() + " failed: " + run.error());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("status", run.status().name());
        out.put("fromVersion", run.fromVersion());
        out.put("toVersion", run.toVersion());
        out.put("statsJson", run.statsJson());
        return out;
    }
}
