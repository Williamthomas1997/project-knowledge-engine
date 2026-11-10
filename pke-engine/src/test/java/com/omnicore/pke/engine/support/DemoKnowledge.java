package com.omnicore.pke.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.IngestionRunStore.Run;
import com.omnicore.pke.engine.graph.IngestionRunStore.Status;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.ingest.DocumentIngestionService;
import com.omnicore.pke.engine.ingest.IngestionRunService;
import com.omnicore.pke.engine.ingest.IngestionRunService.Kind;
import com.omnicore.pke.engine.ingest.IngestionRunService.Mode;
import com.omnicore.pke.engine.ingest.IngestionRunService.Request;
import com.omnicore.pke.engine.ingest.RepositoryIngestionService;
import com.omnicore.pke.engine.pipeline.PipelineExecutor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Loads the whole demo corpus the way a user would: the transfer service and the QA project (code, journeys,
 * routes), then BRD v1 through {@code brd-ingest} (replay model), cards linked to code. Used by retrieval tests and
 * the evaluation.
 */
@Component
public class DemoKnowledge {

    public static final String BRD = "doc:international-transfer-brd";

    private final RepositoryIngestionService repositories;
    private final IngestionRunService runs;
    private final DocumentIngestionService documents;
    private final PipelineExecutor pipelines;

    public DemoKnowledge(RepositoryIngestionService repositories, IngestionRunService runs, DocumentIngestionService documents,
                         PipelineExecutor pipelines) {
        this.repositories = repositories;
        this.runs = runs;
        this.documents = documents;
        this.pipelines = pipelines;
    }

    public void load() {
        repositories.register("transfer-service", DemoCorpus.repository("transfer-service").dir().toString(), "transfer-service", "t");
        repositories.register("qa-automation", DemoCorpus.repository("qa-automation").dir().toString(), null, "t");
        for (String repo : List.of("transfer-service", "qa-automation")) {
            Run run = runs.start(new Request(Kind.REPOSITORY, repo, Mode.FULL), "t", true);
            assertThat(run.status()).as("%s: %s %s", repo, run.error(), run.statsJson()).isEqualTo(Status.COMPLETED);
        }
        try {
            documents.ingest(Files.readAllBytes(DemoCorpus.file("brd/international-transfer-brd.md")),
                    "international-transfer-brd.md", null, null, "t");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ExecutionView brd = pipelines.start("brd-ingest", Map.of("documentKey", BRD), "t", false, true);
        assertThat(brd.status()).as("brd-ingest %s: %s", brd.id(), brd.error()).isEqualTo("COMPLETED");
    }
}
