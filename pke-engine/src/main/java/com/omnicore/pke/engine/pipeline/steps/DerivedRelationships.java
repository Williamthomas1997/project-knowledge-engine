package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.retrieval.GuardedCypher;
import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.SchemaService;
import com.omnicore.pke.engine.schema.SchemaService.DefinitionView;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.ScopeRetraction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Populates relationship types declared with {@code extraction.query} (Level-1 metadata, ADR-016): the query runs
 * behind every Cypher guard (read-only, registered vocabulary, row limit), each row {@code subjectId, objectId
 * [, evidence]} becomes a relationship, and the whole type is one evidence scope, so pairs the query no longer
 * returns are invalidated with history. An unchanged graph writes nothing.
 */
@Component
class DerivedRelationships {

    static final String METHOD = "derived.query";

    private final SchemaService schema;
    private final GuardedCypher cypher;
    private final GraphStore store;
    private final int maxRows;

    DerivedRelationships(SchemaService schema, GuardedCypher cypher, GraphStore store,
                         @Value("${pke.discovery.population-max-rows:5000}") int maxRows) {
        this.schema = schema;
        this.cypher = cypher;
        this.store = store;
        this.maxRows = maxRows;
    }

    static String source(String type) {
        return "derived:" + type;
    }

    @SuppressWarnings("unchecked")
    static String query(DefinitionView view) {
        Object extraction = view.definition().get("extraction");
        return extraction instanceof Map<?, ?> map ? (String) ((Map<String, Object>) map).get("query") : null;
    }

    /** Populates every active relationship type that has an {@code extraction.query}; one failing type is reported. */
    Map<String, Object> populateAll(String actor, String operationId) {
        Map<String, Object> results = new LinkedHashMap<>();
        for (DefinitionView view : schema.list(DefinitionKind.RELATIONSHIP_TYPE)) {
            String query = query(view);
            if (query != null && "ACTIVE".equals(view.status())) {
                results.put(view.name(), populateOrReport(view.name(), query, actor, operationId));
            }
        }
        return results;
    }

    /** {@link #populate}, with a failure (e.g. rows whose endpoints have the wrong types) returned as {@code error}. */
    Map<String, Object> populateOrReport(String type, String query, String actor, String operationId) {
        try {
            return populate(type, query, actor, operationId);
        } catch (RuntimeException e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    Map<String, Object> populate(String type, String query, String actor, String operationId) {
        List<Map<String, Object>> rows = cypher.run(query, Map.of(), "populate " + type, Duration.ofSeconds(30), maxRows).rows();
        List<RelationshipWrite> writes = new ArrayList<>();
        String version = CanonicalJson.sha256(query);
        for (Map<String, Object> row : rows) {
            Object subject = row.get("subjectId");
            Object object = row.get("objectId");
            if (subject == null || object == null) {
                continue;
            }
            String excerpt = row.get("evidence") == null ? null : Steps.truncate(String.valueOf(row.get("evidence")), 2000);
            writes.add(new RelationshipWrite(type, EntityRef.id(subject.toString()), EntityRef.id(object.toString()), Map.of(),
                    EpistemicStatus.EXTRACTED_DETERMINISTIC, null,
                    List.of(EvidenceInput.scoped(source(type), version, source(type), "query", METHOD, excerpt))));
        }
        ChangeResult result = store.apply(new ChangeSet(operationId, List.of(), writes, List.of(),
                List.of(new ScopeRetraction(source(type), source(type), METHOD, "no longer returned by the population query")),
                true), actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows.size());
        out.put("relationships", writes.size());
        out.put("events", result.eventCount());
        out.put("truncated", rows.size() >= maxRows);
        return out;
    }
}
