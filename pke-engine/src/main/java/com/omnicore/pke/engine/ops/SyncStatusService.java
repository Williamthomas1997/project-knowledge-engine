package com.omnicore.pke.engine.ops;

import com.omnicore.pke.engine.graph.IndexTaskStore;
import com.omnicore.pke.engine.graph.SyncQueries;
import com.omnicore.pke.engine.graph.VectorCatalogStore;
import com.omnicore.pke.engine.index.Collections;
import com.omnicore.pke.engine.index.EmbeddingGenerations;
import com.omnicore.pke.engine.index.IndexMaintenance;
import com.omnicore.pke.engine.index.Vectors;
import com.omnicore.pke.engine.pipeline.SourceVersionResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Synchronisation observability (MASTER_SPEC §19.4): unprocessed and changed sources, entities with only obsolete
 * evidence, index state per collection (expected vs indexed, open tasks), unfinished runs and pipelines, schema
 * migrations awaiting completion (none exist yet) and knowledge waiting for a human answer.
 */
@Service
public class SyncStatusService {

    private final SyncQueries queries;
    private final IndexTaskStore tasks;
    private final VectorCatalogStore catalog;
    private final Collections collections;
    private final EmbeddingGenerations generations;
    private final Vectors vectors;
    private final IndexMaintenance maintenance;
    private final SourceVersionResolver sourceVersions;

    public SyncStatusService(SyncQueries queries, IndexTaskStore tasks, VectorCatalogStore catalog, Collections collections,
                             EmbeddingGenerations generations, Vectors vectors, IndexMaintenance maintenance,
                             SourceVersionResolver sourceVersions) {
        this.queries = queries;
        this.tasks = tasks;
        this.catalog = catalog;
        this.collections = collections;
        this.generations = generations;
        this.vectors = vectors;
        this.maintenance = maintenance;
        this.sourceVersions = sourceVersions;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        List<Map<String, Object>> notProcessed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> source : queries.sources()) {
            String key = (String) source.get("key");
            if ("repository".equals(source.get("kind"))) {
                String head = sourceVersions.currentVersion(key);
                Object last = source.get("lastIngestedVersion");
                if (last == null) {
                    notProcessed.add(Map.of("key", key, "kind", "repository"));
                } else if (!last.equals(head)) {
                    changed.add(Map.of("key", key, "kind", "repository", "lastIngested", last, "head", head));
                }
            } else if (!"document".equals(source.get("kind"))) {
                continue; // exports (route files) are read by ingestion runs, not by a pipeline of their own
            } else if (source.get("lastPipelineAt") == null) {
                notProcessed.add(Map.of("key", key, "kind", "document", "pipeline", "brd-ingest"));
            } else if (source.get("versionAt") != null
                    && String.valueOf(source.get("versionAt")).compareTo(String.valueOf(source.get("lastPipelineAt"))) > 0) {
                changed.add(Map.of("key", key, "kind", "document", "currentVersion", source.get("currentVersion")));
            }
        }
        status.put("sourcesNotProcessed", notProcessed);
        status.put("sourcesChangedSinceLastSuccess", changed);
        status.put("entitiesWithOnlyObsoleteEvidence", queries.entitiesWithOnlyObsoleteEvidence());

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("provider", vectors.provider());
        index.put("activeGeneration", generations.active().id());
        index.put("generations", generations.all());
        index.put("tasks", tasks.summary());
        Map<String, Long> open = new LinkedHashMap<>();
        tasks.perCollection().forEach(r -> open.merge(r.get("type") + "_" + r.get("field") + ":" + r.get("status"),
                ((Number) r.get("c")).longValue(), Long::sum));
        List<Map<String, Object>> perCollection = new ArrayList<>();
        var active = generations.active();
        for (Collections.ContentCollection c : collections.content()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("collection", c.name());
            row.put("expected", catalog.indexableOfType(c.type(), c.field()).size());
            row.put("indexed", vectors.allIds(collections.ref(c.name(), active)).size());
            row.put("openTasks", open.entrySet().stream().filter(e -> e.getKey().startsWith(c.name() + ":"))
                    .mapToLong(Map.Entry::getValue).sum());
            perCollection.add(row);
        }
        index.put("collections", perCollection);
        index.put("lastReconciliation", maintenance.lastReconciliation().orElse(null));
        status.put("index", index);

        status.put("unfinishedPipelines", queries.unfinishedPipelines());
        status.put("unfinishedIngestionRuns", queries.unfinishedRuns());
        status.put("schemaMigrationsAwaiting", List.of());
        status.put("openQuestions", queries.openQuestions());
        return status;
    }
}
