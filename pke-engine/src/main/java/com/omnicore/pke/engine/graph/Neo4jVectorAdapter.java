package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.vectorstore.EmbeddingResolver;
import com.omnicore.pke.engine.vectorstore.SpringAiVectorSupport;
import com.omnicore.pke.engine.vectorstore.VectorStorePort;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.neo4j.driver.Driver;
import org.springframework.ai.vectorstore.neo4j.Neo4jVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Neo4j vector index adapter (default): one label and one vector index per physical collection
 * ({@code :PkeVector_<physical name>}). Vector nodes are a derived projection: they are not {@code :Entity} nodes,
 * carry no knowledge and are written by Spring AI's store directly (ADR-014 records this exception to ADR-009).
 * Community-compatible (vector indexes exist in Community).
 */
@Component
@ConditionalOnProperty(name = "pke.vector.provider", havingValue = "neo4j", matchIfMissing = true)
public class Neo4jVectorAdapter implements VectorStorePort {

    private final Driver driver;
    private final String database;
    private final GraphReader reader;
    private final EmbeddingResolver embeddings;
    private final Map<String, Neo4jVectorStore> stores = new ConcurrentHashMap<>();

    public Neo4jVectorAdapter(Driver driver, @Value("${spring.data.neo4j.database:neo4j}") String database,
                              GraphReader reader, EmbeddingResolver embeddings) {
        this.driver = driver;
        this.database = database;
        this.reader = reader;
        this.embeddings = embeddings;
    }

    @Override
    public String provider() {
        return "neo4j";
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
        Set<String> found = new HashSet<>();
        reader.query("MATCH (n:$($label)) WHERE n.id IN $ids RETURN n.id AS id",
                Map.of("label", label(collection), "ids", List.copyOf(ids))).forEach(r -> found.add((String) r.get("id")));
        return found;
    }

    @Override
    public Set<String> allIds(CollectionRef collection) {
        Set<String> found = new HashSet<>();
        reader.query("MATCH (n:$($label)) RETURN n.id AS id", Map.of("label", label(collection)))
                .forEach(r -> found.add((String) r.get("id")));
        return found;
    }

    static String label(CollectionRef collection) {
        return "PkeVector_" + collection.physicalName();
    }

    private Neo4jVectorStore store(CollectionRef collection) {
        return stores.computeIfAbsent(collection.physicalName(), name -> {
            Neo4jVectorStore store = Neo4jVectorStore.builder(driver, embeddings.resolve(collection.embeddingModel()))
                    .databaseName(database)
                    .label(label(collection))
                    .indexName(name)
                    .constraintName(name + "_id")
                    .embeddingDimension(collection.dimensions())
                    .distanceType(Neo4jVectorStore.Neo4jDistanceType.COSINE)
                    .initializeSchema(true)
                    .build();
            store.afterPropertiesSet();
            return store;
        });
    }
}
