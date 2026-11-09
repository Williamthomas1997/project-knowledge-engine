package com.omnicore.pke.engine.ai;

import org.springframework.stereotype.Component;

/**
 * Reports which AI models are configured. Models are off by default ({@code spring.ai.model.*=none})
 * so the engine starts and tests run without any provider credentials.
 */
@Component
public class ModelAvailability {

    private final ChatPort chat;
    private final EmbeddingPort embeddings;

    public ModelAvailability(ChatPort chat, EmbeddingPort embeddings) {
        this.chat = chat;
        this.embeddings = embeddings;
    }

    public boolean chatModelConfigured() {
        return chat.available();
    }

    public boolean embeddingModelConfigured() {
        return embeddings.available();
    }

    public String chatModel() {
        return chat.describe();
    }
}
