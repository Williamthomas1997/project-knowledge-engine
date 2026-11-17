package com.omnicore.pke.engine.history;

import com.omnicore.pke.engine.graph.HistoryQueries;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemChange;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemState;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Restores knowledge to its state at a checkpoint {@code seq} by writing the inverse of every change made since,
 * through the single writer and in one transaction. Nothing is deleted: the restore is itself history (new versions
 * and events with operation id {@code restore:<seq>}), so it can be inspected and undone.
 */
@Service
public class RecoveryService {

    public static final String METHOD = "recovery.restore";

    private final HistoryQueries history;
    private final GraphStore store;

    public RecoveryService(HistoryQueries history, GraphStore store) {
        this.history = history;
        this.store = store;
    }

    public ChangeResult restoreTo(long seq, long currentSeq, String reason, String actor) {
        List<EntityWrite> entities = new ArrayList<>();
        List<RelationshipWrite> relationships = new ArrayList<>();
        List<RelationshipInvalidation> invalidations = new ArrayList<>();
        List<EntityInvalidation> entityInvalidations = new ArrayList<>();
        List<EvidenceInput> evidence = List.of(new EvidenceInput("recovery", "seq:" + seq, "checkpoint", null, METHOD,
                null, null, null, null, reason, null, null, EpistemicStatus.EXTRACTED_DETERMINISTIC));
        for (ItemChange change : history.diff(seq, currentSeq)) {
            ItemState before = change.before();
            boolean entity = change.kind().equals("ENTITY");
            if (before == null) {
                if (entity) {
                    entityInvalidations.add(new EntityInvalidation(change.id(), reason, null, evidence));
                } else {
                    invalidations.add(new RelationshipInvalidation(change.id(), reason, null, evidence));
                }
            } else if (entity) {
                entities.add(new EntityWrite(before.type(), change.id(), before.properties(),
                        EpistemicStatus.valueOf(before.epistemic()), null, evidence));
            } else {
                relationships.add(new RelationshipWrite(before.type(), EntityRef.id(before.subjectId()),
                        EntityRef.id(before.objectId()), before.properties(), EpistemicStatus.valueOf(before.epistemic()),
                        null, evidence));
            }
        }
        // the writer applies entities, relationships, then relationship and entity invalidations
        ChangeSet changes = new ChangeSet("restore:" + seq + ":" + PkeIds.uuidV7(), entities, relationships, invalidations,
                List.of(), false, entityInvalidations);
        return changes.isEmpty() ? new ChangeResult(changes.operationId(), List.of(), null, null) : store.apply(changes, actor);
    }
}
