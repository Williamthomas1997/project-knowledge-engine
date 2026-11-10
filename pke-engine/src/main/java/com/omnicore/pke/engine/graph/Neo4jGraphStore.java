package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.store.KnowledgeChanges;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipInvalidation;
import org.springframework.stereotype.Component;

/** The Neo4j adapter of {@link GraphStore}: the M1 single writer (ADR-009). */
@Component
public class Neo4jGraphStore implements GraphStore {

    private final KnowledgeWriter writer;

    public Neo4jGraphStore(KnowledgeWriter writer) {
        this.writer = writer;
    }

    @Override
    public ChangeResult apply(ChangeSet changes, String actor) {
        return writer.apply(changes, actor);
    }

    @Override
    public ChangeResult invalidate(RelationshipInvalidation invalidation, String actor) {
        return writer.invalidate(invalidation, actor);
    }
}
