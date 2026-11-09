package com.omnicore.pke.engine.ingest;

import com.omnicore.pke.engine.extract.ExtractedKnowledge;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ItemOutcome;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ScopeRetraction;
import com.omnicore.pke.engine.graph.KnowledgeWriter;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.schema.EntityTypeDefinition;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Writes the knowledge of one ingestion run in two passes, through the single writer:
 * <ol>
 *   <li>all entities of all scopes, merged by natural key (first properties win, evidence is combined), in batches;</li>
 *   <li>per scope: its relationships plus a retraction of that scope, so relationships the scope no longer produces
 *       are invalidated (history kept).</li>
 * </ol>
 * A failing batch or scope is recorded and the run continues (the run then ends {@code PARTIAL}).
 */
@Component
public class KnowledgeBatchWriter {

    private final KnowledgeWriter writer;
    private final SchemaRegistry registry;

    public KnowledgeBatchWriter(KnowledgeWriter writer, SchemaRegistry registry) {
        this.writer = writer;
        this.registry = registry;
    }

    /** Knowledge of one scope with the source and extraction method its retraction applies to. */
    public record Scoped(ExtractedKnowledge knowledge, String artifactKey, String method) {
    }

    /** Outcome counts ({@code ENTITY:CREATED -> 12}) and failures ({@code scope: message}). */
    public record Stats(Map<String, Long> outcomes, List<String> failures) {
        static Stats empty() {
            return new Stats(new TreeMap<>(), new ArrayList<>());
        }

        void count(ChangeResult result) {
            for (ItemOutcome item : result.items()) {
                outcomes.merge(item.kind() + ":" + item.outcome(), 1L, Long::sum);
            }
        }

        public long get(String key) {
            return outcomes.getOrDefault(key, 0L);
        }
    }

    public Stats write(List<Scoped> scopes, List<ScopeRetraction> extraRetractions, String operationId, String actor,
                       int batchSize, Stats stats) {
        Map<String, EntityWrite> entities = new LinkedHashMap<>();
        for (Scoped scoped : scopes) {
            for (EntityWrite entity : scoped.knowledge().entities()) {
                entities.merge(key(entity), entity, KnowledgeBatchWriter::mergeEntities);
            }
        }
        List<EntityWrite> all = new ArrayList<>(entities.values());
        for (int i = 0; i < all.size(); i += batchSize) {
            List<EntityWrite> batch = all.subList(i, Math.min(all.size(), i + batchSize));
            try {
                stats.count(writer.apply(new ChangeSet(operationId, batch, List.of(), List.of(), List.of(), true), actor));
            } catch (RuntimeException e) {
                stats.failures().add("entities " + i + ".." + (i + batch.size() - 1) + ": " + e.getMessage());
            }
        }
        for (Scoped scoped : scopes) {
            ScopeRetraction retraction = new ScopeRetraction(scoped.artifactKey(), scoped.knowledge().scope(),
                    scoped.method(), "no longer found in " + scoped.knowledge().scope());
            try {
                stats.count(writer.apply(new ChangeSet(operationId, List.of(), scoped.knowledge().relationships(),
                        List.of(), List.of(retraction), true), actor));
            } catch (RuntimeException e) {
                stats.failures().add(scoped.knowledge().scope() + ": " + e.getMessage());
            }
        }
        for (ScopeRetraction retraction : extraRetractions) {
            try {
                stats.count(writer.apply(new ChangeSet(operationId, List.of(), List.of(), List.of(), List.of(retraction), true), actor));
            } catch (RuntimeException e) {
                stats.failures().add(retraction.scope() + " (retraction): " + e.getMessage());
            }
        }
        return stats;
    }

    public Stats write(List<Scoped> scopes, List<ScopeRetraction> extraRetractions, String operationId, String actor,
                       int batchSize) {
        return write(scopes, extraRetractions, operationId, actor, batchSize, Stats.empty());
    }

    private String key(EntityWrite entity) {
        EntityTypeDefinition definition = registry.entityType(entity.type()).orElse(null);
        if (definition == null || definition.naturalKey().isEmpty()) {
            return entity.type() + CanonicalJson.write(entity.properties());
        }
        Map<String, Object> key = new LinkedHashMap<>();
        definition.naturalKey().forEach(k -> key.put(k, entity.properties().get(k)));
        return entity.type() + CanonicalJson.write(key);
    }

    private static EntityWrite mergeEntities(EntityWrite first, EntityWrite second) {
        List<EvidenceInput> evidence = new ArrayList<>(first.evidence());
        second.evidence().stream().filter(e -> !evidence.contains(e)).forEach(evidence::add);
        return new EntityWrite(first.type(), first.id(), first.properties(), first.epistemic(), first.validAt(), evidence,
                first.renamedFrom() != null ? first.renamedFrom() : second.renamedFrom());
    }
}
