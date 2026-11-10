package com.omnicore.pke.engine.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.vectorstore.VectorStorePort.CollectionRef;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorHit;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link VectorStorePort} adapter passes (Neo4j vector index and Qdrant run the same tests):
 * upsert = replace by id, delete, search by text with metadata filters, existing/all ids, collection isolation.
 */
public interface VectorStoreContract {

    VectorStorePort port();

    private static CollectionRef collection() {
        // a fresh collection per test keeps adapters' state apart
        return new CollectionRef("Contract_" + UUID.randomUUID().toString().substring(0, 8), "g1", "hashing-384", 384);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static VectorRecord record(String id, String pkeId, String text, String type, String status) {
        return new VectorRecord(id, text, Map.of("pke_id", pkeId, "type", type, "status", status, "collection", "test"));
    }

    @Test
    default void upsertedRecordsAreFoundBySimilarTextWithTheirPayload() {
        CollectionRef c = collection();
        String limit = id();
        port().upsert(c, List.of(
                record(limit, "card-limit", "The daily limit for international transfers is QAR 30,000.", "KnowledgeCard", "CURRENT"),
                record(id(), "card-otp", "An international transfer is confirmed with an OTP.", "KnowledgeCard", "CURRENT"),
                record(id(), "card-fx", "Exchange rates are refreshed every minute.", "KnowledgeCard", "CURRENT")));
        List<VectorHit> hits = port().search(c, "daily transfer limit in QAR", 2, 0.0, Map.of());
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().id()).isEqualTo(limit);
        assertThat(hits.getFirst().pkeId()).isEqualTo("card-limit");
        assertThat(hits.getFirst().text()).contains("QAR 30,000");
        assertThat(hits.getFirst().metadata()).containsEntry("type", "KnowledgeCard");
        assertThat(hits.getFirst().score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    default void upsertReplacesTheRecordWithTheSameId() {
        CollectionRef c = collection();
        String id = id();
        port().upsert(c, List.of(record(id, "card-1", "The limit is QAR 50,000.", "KnowledgeCard", "CURRENT")));
        port().upsert(c, List.of(record(id, "card-1", "The limit is QAR 30,000.", "KnowledgeCard", "CURRENT")));
        assertThat(port().allIds(c)).containsExactly(id);
        assertThat(port().search(c, "limit", 5, 0.0, Map.of())).singleElement()
                .satisfies(h -> assertThat(h.text()).contains("30,000"));
    }

    @Test
    default void metadataFiltersRestrictTheSearch() {
        CollectionRef c = collection();
        port().upsert(c, List.of(
                record(id(), "current", "beneficiary currency validation rejects unsupported currencies", "KnowledgeCard", "CURRENT"),
                record(id(), "method", "beneficiary currency validation method", "JavaMethod", "CURRENT")));
        assertThat(port().search(c, "beneficiary currency validation", 5, 0.0, Map.of("type", "JavaMethod")))
                .extracting(VectorHit::pkeId).containsExactly("method");
        assertThat(port().search(c, "beneficiary currency validation", 5, 0.0, Map.of("type", "KnowledgeCard", "status", "CURRENT")))
                .extracting(VectorHit::pkeId).containsExactly("current");
    }

    @Test
    default void deletedRecordsAreGoneAndIdsAreReported() {
        CollectionRef c = collection();
        String keep = id();
        String drop = id();
        port().upsert(c, List.of(record(keep, "a", "first text about transfers", "KnowledgeCard", "CURRENT"),
                record(drop, "b", "second text about limits", "KnowledgeCard", "CURRENT")));
        assertThat(port().existingIds(c, List.of(keep, drop, id()))).containsExactlyInAnyOrder(keep, drop);
        port().delete(c, List.of(drop));
        assertThat(port().allIds(c)).containsExactly(keep);
        assertThat(port().existingIds(c, List.of(drop))).isEmpty();
        assertThat(port().search(c, "limits", 5, 0.0, Map.of())).extracting(VectorHit::pkeId).doesNotContain("b");
    }

    @Test
    default void collectionsAndGenerationsAreIsolated() {
        CollectionRef c = collection();
        CollectionRef other = new CollectionRef(c.name(), "g2", "hashing-256", 256);
        port().upsert(c, List.of(record(id(), "a", "only in generation one", "KnowledgeCard", "CURRENT")));
        port().upsert(other, List.of(record(id(), "b", "only in generation two", "KnowledgeCard", "CURRENT")));
        assertThat(port().search(c, "generation", 5, 0.0, Map.of())).extracting(VectorHit::pkeId).containsExactly("a");
        assertThat(port().search(other, "generation", 5, 0.0, Map.of())).extracting(VectorHit::pkeId).containsExactly("b");
    }
}
