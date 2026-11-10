package com.omnicore.pke.engine.store;

import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipInvalidation;

/**
 * The graph store port (ADR-007 §1, ADR-014): applies change sets with history in one transaction. Neo4j
 * ({@code graph.Neo4jGraphStore}, over the M1 single writer) is the only adapter; Memgraph or FalkorDB would be
 * further adapters. No Neo4j type appears in this package or outside {@code graph} (ArchUnit).
 * <p>
 * Reads use the graph package's query services, which return plain Java values.
 */
public interface GraphStore {

    ChangeResult apply(ChangeSet changes, String actor);

    ChangeResult invalidate(RelationshipInvalidation invalidation, String actor);
}
