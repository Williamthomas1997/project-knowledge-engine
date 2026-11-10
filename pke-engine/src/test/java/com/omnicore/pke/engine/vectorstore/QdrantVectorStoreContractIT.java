package com.omnicore.pke.engine.vectorstore;

import com.omnicore.pke.engine.ai.EmbeddingModels;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * The vector store contract against Qdrant (Testcontainers {@code qdrant/qdrant:v1.18.0}). Needs Docker; reported
 * as skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class QdrantVectorStoreContractIT implements VectorStoreContract {

    private static QdrantContainer qdrant;
    private static QdrantClient client;
    private static QdrantVectorAdapter adapter;

    @BeforeAll
    static void start() {
        qdrant = new QdrantContainer("qdrant/qdrant:v1.18.0");
        qdrant.start();
        client = new QdrantClient(QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getGrpcPort(), false).build());
        EmbeddingModels models = new EmbeddingModels(new StaticListableBeanFactory().getBeanProvider(
                org.springframework.ai.embedding.EmbeddingModel.class), "hashing-384");
        adapter = new QdrantVectorAdapter(client, models);
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (qdrant != null) {
            qdrant.stop();
        }
    }

    @Override
    public VectorStorePort port() {
        return adapter;
    }
}
