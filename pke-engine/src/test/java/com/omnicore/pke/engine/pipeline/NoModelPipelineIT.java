package com.omnicore.pke.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.ai.ModelAvailability;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.StepView;
import com.omnicore.pke.engine.ingest.DocumentIngestionService;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Without a model the AI steps report SKIPPED_NO_MODEL, nothing is guessed and nothing is written. */
@TestPropertySource(properties = "pke.ai.mode=none")
class NoModelPipelineIT extends KnowledgeIT {

    @Autowired
    DocumentIngestionService documents;

    @Autowired
    PipelineExecutor executor;

    @Autowired
    ModelAvailability models;

    @Autowired
    SkillRunner skills;

    @Test
    void aiStepsAreSkippedWithoutAModel() throws Exception {
        assertThat(models.chatModelConfigured()).isFalse();
        documents.ingest(Files.readAllBytes(DemoCorpus.file("brd/international-transfer-brd.md")),
                "international-transfer-brd.md", null, null, "t");
        Map<String, Long> before = footprint();

        ExecutionView execution = executor.start("brd-ingest", Map.of("documentKey", BrdIngestPipelineIT.DOC), "t", false, true);
        assertThat(execution.status()).isEqualTo("PARTIAL");
        assertThat(execution.error()).contains("no model configured");
        assertThat(execution.steps()).extracting(StepView::status)
                .containsExactly("COMPLETED", "COMPLETED", "SKIPPED_NO_MODEL", "SKIPPED_NO_MODEL", "SKIPPED_NO_MODEL",
                        "SKIPPED_NO_MODEL");
        assertThat(footprint()).isEqualTo(before);
        assertThat(count("MATCH (c:ModelCall) RETURN count(c)")).isZero();
        assertThat(skills.run("summarise-api", Map.of("api", Map.of("httpMethod", "GET", "pathTemplate", "/x")), null, "t")
                .skipped()).isTrue();
    }
}
