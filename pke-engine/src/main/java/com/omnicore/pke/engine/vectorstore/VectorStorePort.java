package com.omnicore.pke.engine.vectorstore;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection-aware vector store (ADR-007 §1, ADR-014), built on Spring AI {@code VectorStore}. Adapters:
 * Neo4j vector index (default, {@code pke.vector.provider=neo4j}) and Qdrant ({@code qdrant}). Every adapter passes
 * the same contract tests. Vectors are a derived projection of the graph: they never create knowledge events.
 */
public interface VectorStorePort {

    String provider();

    /** Adds or replaces records (same id = replaced). The text is embedded with the collection's model. */
    void upsert(CollectionRef collection, List<VectorRecord> records);

    void delete(CollectionRef collection, Collection<String> ids);

    /**
     * @param filter metadata equality constraints (e.g. {@code status=CURRENT}, {@code type=KnowledgeCard})
     */
    List<VectorHit> search(CollectionRef collection, String query, int topK, double minScore, Map<String, Object> filter);

    /** Which of {@code ids} the store holds (reconciliation). */
    Set<String> existingIds(CollectionRef collection, Collection<String> ids);

    /** Every record id of a collection (reconciliation; collections are expected to fit in memory as ids). */
    Set<String> allIds(CollectionRef collection);

    /** One record to store: {@code id} = UUIDv5(pke_id, field, generation); payload as required by ADR-007 §2. */
    record VectorRecord(String id, String text, Map<String, Object> metadata) {
    }

    record VectorHit(String id, String pkeId, double score, String text, Map<String, Object> metadata) {
    }

    /**
     * A physical collection: a logical collection ({@code <Type>_<field>} or a system collection) in one embedding
     * generation. Generations never share a physical collection, so dimensions are never mixed.
     */
    record CollectionRef(String name, String generation, String embeddingModel, int dimensions) {

        /** Store-side name, e.g. {@code pke_knowledgecard_text_g1}. */
        public String physicalName() {
            return ("pke_" + name + "_" + generation).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        }
    }
}
