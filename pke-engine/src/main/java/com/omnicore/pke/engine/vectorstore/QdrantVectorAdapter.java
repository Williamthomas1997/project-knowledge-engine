package com.omnicore.pke.engine.vectorstore;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/** Qdrant adapter: one Qdrant collection per physical collection ({@link CollectionRef#physicalName()}). */
public class QdrantVectorAdapter implements VectorStorePort {

    private final QdrantClient client;
    private final EmbeddingResolver embeddings;
    private final Map<String, QdrantVectorStore> stores = new ConcurrentHashMap<>();

    public QdrantVectorAdapter(QdrantClient client, EmbeddingResolver embeddings) {
        this.client = client;
        this.embeddings = embeddings;
    }

    @Override
    public String provider() {
        return "qdrant";
    }

    @Override
    public void upsert(CollectionRef collection, List<VectorRecord> records) {
        if (!records.isEmpty()) {
            store(collection).add(SpringAiVectorSupport.documents(records));
        }
    }

    @Override
    public void delete(CollectionRef collection, Collection<String> ids) {
        if (!ids.isEmpty()) {
            store(collection).delete(List.copyOf(ids));
        }
    }

    @Override
    public List<VectorHit> search(CollectionRef collection, String query, int topK, double minScore, Map<String, Object> filter) {
        return SpringAiVectorSupport.search(store(collection), query, topK, minScore, filter);
    }

    @Override
    public Set<String> existingIds(CollectionRef collection, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        store(collection);
        List<PointId> pointIds = ids.stream().map(id -> PointIdFactory.id(UUID.fromString(id))).toList();
        Set<String> found = new HashSet<>();
        get(client.retrieveAsync(collection.physicalName(), pointIds, false, false, null))
                .forEach(p -> found.add(p.getId().getUuid()));
        return found;
    }

    @Override
    public Set<String> allIds(CollectionRef collection) {
        store(collection);
        Set<String> ids = new HashSet<>();
        PointId offset = null;
        do {
            Points.ScrollPoints.Builder request = Points.ScrollPoints.newBuilder()
                    .setCollectionName(collection.physicalName()).setLimit(1000)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(false))
                    .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false));
            if (offset != null) {
                request.setOffset(offset);
            }
            Points.ScrollResponse response = get(client.scrollAsync(request.build()));
            response.getResultList().forEach(p -> ids.add(p.getId().getUuid()));
            offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
        } while (offset != null);
        return ids;
    }

    private QdrantVectorStore store(CollectionRef collection) {
        return stores.computeIfAbsent(collection.physicalName(), name -> {
            QdrantVectorStore store = QdrantVectorStore.builder(client, embeddings.resolve(collection.embeddingModel()))
                    .collectionName(name).initializeSchema(true).build();
            try {
                store.afterPropertiesSet();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create Qdrant collection " + name + ": " + e.getMessage(), e);
            }
            return store;
        });
    }

    private static <T> T get(com.google.common.util.concurrent.ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant request failed: " + e.getCause().getMessage(), e.getCause());
        }
    }
}
