package com.omnicore.pke.engine.vectorstore;

import org.springframework.ai.embedding.EmbeddingModel;

/** Resolves an embedding generation's model name to a Spring AI model that embeds document text only. */
public interface EmbeddingResolver {

    EmbeddingModel resolve(String modelName);
}
