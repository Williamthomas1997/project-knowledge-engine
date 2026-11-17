package com.omnicore.pke.engine.history;

import com.omnicore.pke.engine.graph.HistoryQueries;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemState;
import com.omnicore.pke.engine.graph.HistoryStore.SnapshotView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Declarative data migrations (plan §6) with a checkpoint: a snapshot is taken first, entities are rewritten in
 * batches through the single writer, and if any batch fails every change made since the checkpoint is reverted
 * ({@link RecoveryService}). Both properties of an operation must exist in the active schema version.
 * <p>
 * Operations: {@code setDefault {property, value}} (sets the value where the property is missing, i.e. the data
 * half of {@code addRequired(default)}) and {@code renameProperty {from, to}} (moves the value, drops {@code from}).
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    public static final String METHOD = "migration";

    private final HistoryQueries history;
    private final GraphStore store;
    private final SnapshotService snapshots;
    private final RecoveryService recovery;
    private final KnowledgeQueryService queries;
    private final SchemaRegistry registry;

    public MigrationService(HistoryQueries history, GraphStore store, SnapshotService snapshots, RecoveryService recovery,
                            KnowledgeQueryService queries, SchemaRegistry registry) {
        this.history = history;
        this.store = store;
        this.snapshots = snapshots;
        this.recovery = recovery;
        this.queries = queries;
        this.registry = registry;
    }

    public record Operation(String op, String property, Object value, String from, String to) {
    }

    public record Migration(String id, String entityType, List<Operation> operations, Integer batchSize) {
    }

    /** {@code COMPLETED}, or {@code RESTORED} (a batch failed and the checkpoint was restored). */
    public record Result(String id, String status, String checkpointId, long checkpointSeq, int migrated, int batches,
                         String error, Long restoreFirstSeq, Long restoreLastSeq) {
    }

    public Result run(Migration m, String actor) {
        validate(m);
        SnapshotView checkpoint = snapshots.create("checkpoint:migration:" + m.id(), actor);
        int batchSize = m.batchSize() == null ? 100 : Math.clamp(m.batchSize(), 1, 1000);
        List<EntityWrite> writes = new ArrayList<>();
        for (ItemState entity : history.currentEntities(m.entityType())) {
            Map<String, Object> props = apply(m.operations(), entity.properties());
            if (!props.equals(entity.properties())) {
                writes.add(new EntityWrite(entity.type(), entity.id(), props, EpistemicStatus.valueOf(entity.epistemic()),
                        null, List.of(new EvidenceInput("migration:" + m.id(), checkpoint.id(), m.entityType(), null, METHOD,
                        null, null, null, null, null, null, null, EpistemicStatus.EXTRACTED_DETERMINISTIC))));
            }
        }
        int batches = 0;
        try {
            for (int i = 0; i < writes.size(); i += batchSize) {
                store.apply(new ChangeSet("migration:" + m.id(), writes.subList(i, Math.min(writes.size(), i + batchSize)),
                        List.of(), List.of()), actor);
                batches++;
            }
            return new Result(m.id(), "COMPLETED", checkpoint.id(), checkpoint.seq(), writes.size(), batches, null, null, null);
        } catch (RuntimeException e) {
            log.warn("Migration {} failed after {} batch(es), restoring checkpoint seq {}", m.id(), batches, checkpoint.seq(), e);
            ChangeResult restored = recovery.restoreTo(checkpoint.seq(), queries.currentSeq(),
                    "migration " + m.id() + " failed: " + e.getMessage(), actor);
            return new Result(m.id(), "RESTORED", checkpoint.id(), checkpoint.seq(), 0, batches, e.getMessage(),
                    restored.firstSeq(), restored.lastSeq());
        }
    }

    static Map<String, Object> apply(List<Operation> operations, Map<String, Object> properties) {
        Map<String, Object> props = new LinkedHashMap<>(properties);
        for (Operation op : operations) {
            switch (op.op()) {
                case "setDefault" -> props.putIfAbsent(op.property(), op.value());
                case "renameProperty" -> {
                    if (props.containsKey(op.from())) {
                        props.put(op.to(), props.remove(op.from()));
                    }
                }
                default -> throw new IllegalArgumentException(op.op());
            }
        }
        return props;
    }

    private void validate(Migration m) {
        if (m == null || m.id() == null || !m.id().matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")) {
            throw ValidationFailedException.of("/id", "is required: letters, digits and ._- only");
        }
        var type = registry.entityType(m.entityType()).orElseThrow(() ->
                ValidationFailedException.of("/entityType", "is not an active entity type"));
        if (m.operations() == null || m.operations().isEmpty()) {
            throw ValidationFailedException.of("/operations", "at least one operation is required");
        }
        for (int i = 0; i < m.operations().size(); i++) {
            Operation op = m.operations().get(i);
            List<String> names = switch (op.op() == null ? "" : op.op()) {
                case "setDefault" -> java.util.Arrays.asList(op.property());
                case "renameProperty" -> java.util.Arrays.asList(op.from(), op.to());
                default -> throw ValidationFailedException.of("/operations/" + i + "/op", "must be setDefault or renameProperty");
            };
            for (String name : names) {
                if (name == null || !type.properties().containsKey(name)) {
                    throw ValidationFailedException.of("/operations/" + i, "property " + name + " is not declared by "
                            + m.entityType() + " " + type.version());
                }
                if (type.naturalKey().contains(name)) {
                    throw ValidationFailedException.of("/operations/" + i, name + " is part of the natural key (identity)");
                }
            }
        }
    }
}
