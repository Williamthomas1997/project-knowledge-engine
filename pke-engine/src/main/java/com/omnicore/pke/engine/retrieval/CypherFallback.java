package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.index.Collections;
import com.omnicore.pke.engine.index.EmbeddingGenerations;
import com.omnicore.pke.engine.index.Vectors;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ADR-007 §7 step 4: when no query template fits, the {@code write-cypher} skill writes a query from the relevant
 * {@code GraphSchemaMap} records and the seeds; it only runs after every guard of {@link GuardedCypher}.
 */
@Service
public class CypherFallback {

    private static final Logger log = LoggerFactory.getLogger(CypherFallback.class);
    static final String SKILL = "write-cypher";

    public record Answer(String cypher, String explanation, List<Map<String, Object>> rows, String rejected) {
    }

    private final SkillRunner skills;
    private final GuardedCypher guarded;
    private final Vectors vectors;
    private final Collections collections;
    private final EmbeddingGenerations generations;

    public CypherFallback(SkillRunner skills, GuardedCypher guarded, Vectors vectors, Collections collections,
                          EmbeddingGenerations generations) {
        this.skills = skills;
        this.guarded = guarded;
        this.vectors = vectors;
        this.collections = collections;
        this.generations = generations;
    }

    /** Empty when no model is configured. A rejected query is returned with the reason, never executed. */
    public Optional<Answer> answer(String question, List<RetrievalService.Hit> seeds) {
        if (!skills.modelAvailable()) {
            return Optional.empty();
        }
        Generation generation = generations.active();
        String schemaQuery = question + " " + String.join(" ", seeds.stream().map(RetrievalService.Hit::type).distinct().toList());
        List<String> schema = vectors.search(collections.ref(Collections.SCHEMA_MAP, generation), schemaQuery, 8, 0.0, Map.of())
                .stream().map(h -> h.text()).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("question", question);
        input.put("seeds", seeds.stream().map(h -> Map.of("id", h.id(), "type", h.type(), "label", h.label())).toList());
        input.put("schema", schema);
        SkillResult result = skills.run(SKILL, input, null, "retrieval");
        if (result.skipped()) {
            return Optional.empty();
        }
        String cypher = String.valueOf(result.output().get("cypher"));
        String explanation = String.valueOf(result.output().get("explanation"));
        try {
            GuardedCypher.Execution execution = guarded.run(cypher, Map.of("seedIds",
                    seeds.stream().map(RetrievalService.Hit::id).toList()), "fallback");
            return Optional.of(new Answer(execution.cypher(), explanation, execution.rows(), null));
        } catch (ValidationFailedException e) {
            log.warn("Fallback Cypher rejected: {}", e.violations());
            return Optional.of(new Answer(cypher, explanation, List.of(), e.violations().toString()));
        }
    }
}
