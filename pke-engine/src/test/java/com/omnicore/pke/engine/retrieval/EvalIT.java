package com.omnicore.pke.engine.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.DemoKnowledge;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval evaluation on the demo corpus ({@code eval/datasets/demo-transfer.yaml}); results are written to
 * {@code target/eval/demo-transfer.json}. {@code mvn -Peval verify} runs only this test. The thresholds are regression
 * guards just below the values measured in M4 (hashing embeddings, see the M4 report); raise them when retrieval improves.
 */
class EvalIT extends KnowledgeIT {

    @Autowired
    DemoKnowledge demo;

    @Autowired
    EvaluationService evaluation;

    @Test
    @SuppressWarnings("unchecked")
    void theDemoDatasetMeetsItsThresholds() throws Exception {
        demo.load();
        Map<String, Object> run = evaluation.run("demo-transfer", null);
        Path out = Path.of("target/eval/demo-transfer.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(run));

        Map<String, Object> summary = (Map<String, Object>) run.get("summary");
        assertThat((Integer) summary.get("questions")).isEqualTo(8);
        List<Map<String, Object>> questions = (List<Map<String, Object>>) run.get("questions");
        assertThat(questions).allSatisfy(q -> assertThat(q.get("unresolvedExpectations")).as("dataset entities exist: %s", q)
                .isEqualTo(0L));
        assertThat(((Number) summary.get("recallAtK")).doubleValue()).isGreaterThanOrEqualTo(0.5);
        assertThat(((Number) summary.get("mrr")).doubleValue()).isGreaterThanOrEqualTo(0.35);
        assertThat(((Number) summary.get("contextRecall")).doubleValue()).isGreaterThanOrEqualTo(0.9);
        assertThat(((Number) summary.get("graphCompleteness")).doubleValue()).isGreaterThanOrEqualTo(0.8);
        assertThat(((Number) summary.get("sourceCoverage")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) summary.get("citationCorrectness")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) summary.get("contextTokens")).doubleValue()).isLessThanOrEqualTo(2000);
        assertThat(count("MATCH (r:EvalRun {dataset: 'demo-transfer'}) RETURN count(r)")).isEqualTo(1);
    }
}
