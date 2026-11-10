package com.omnicore.pke.engine.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.DemoKnowledge;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The guarded LLM-Cypher fallback (ADR-007 §7 step 4) with the replay model. */
class CypherFallbackIT extends KnowledgeIT {

    @Autowired
    DemoKnowledge demo;

    @Autowired
    CypherFallback fallback;

    @Autowired
    RetrievalService retrieval;

    private List<RetrievalService.Hit> seeds(String query, String type) {
        return retrieval.search(new RetrievalService.SearchRequest(query, 3, List.of(type), null, null, null, null, null)).hits();
    }

    @Test
    void aQuestionNoTemplateCoversIsAnsweredByGuardedModelWrittenCypher() {
        demo.load();
        List<RetrievalService.Hit> seeds = seeds("publishExecuted transfer executed event", "JavaMethod");
        assertThat(seeds).isNotEmpty();
        CypherFallback.Answer answer = fallback.answer("Which Kafka topics does the transfer service publish to?", seeds)
                .orElseThrow();
        assertThat(answer.rejected()).isNull();
        assertThat(answer.cypher()).contains("PUBLISHES_TO").endsWith("LIMIT 50");
        assertThat(answer.rows()).extracting(r -> r.get("label")).containsExactly("transfer.executed");
    }

    @Test
    void aModelWrittenWriteQueryIsRejectedAndNeverRun() {
        demo.load();
        List<RetrievalService.Hit> seeds = seeds("publishExecuted transfer executed event", "JavaMethod");
        Map<String, Long> before = footprint();
        CypherFallback.Answer answer = fallback.answer("Remove the Kafka topics the transfer service publishes to.", seeds)
                .orElseThrow();
        assertThat(answer.rejected()).contains("DETACH").contains("is not allowed");
        assertThat(answer.rows()).isEmpty();
        assertThat(footprint()).isEqualTo(before);
        assertThat(count("MATCH (t:KafkaTopic) RETURN count(t)")).isPositive();
    }
}
