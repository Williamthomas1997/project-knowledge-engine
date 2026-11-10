package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.vectorstore.EmbeddingResolver;
import com.omnicore.pke.engine.vectorstore.QdrantVectorAdapter;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code pke.vector.provider=qdrant}: a Qdrant adapter over gRPC ({@code pke.vector.qdrant.*}). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pke.vector.provider", havingValue = "qdrant")
class QdrantConfiguration {

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(@Value("${pke.vector.qdrant.host:localhost}") String host,
                              @Value("${pke.vector.qdrant.port:6334}") int port,
                              @Value("${pke.vector.qdrant.tls:false}") boolean tls,
                              @Value("${pke.vector.qdrant.api-key:}") String apiKey) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, tls);
        if (!apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    QdrantVectorAdapter qdrantVectorAdapter(QdrantClient client, EmbeddingResolver embeddings) {
        return new QdrantVectorAdapter(client, embeddings);
    }
}
