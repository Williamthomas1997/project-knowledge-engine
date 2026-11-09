package com.omnicore.pke.engine.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reports which AI models are configured. Models are off by default ({@code spring.ai.model.*=none})
 * so the engine starts and tests run without any provider credentials.
 */
@Component
public class ModelAvailability {

    private final ObjectProvider<ChatModel> chatModel;
    private final ObjectProvider<EmbeddingModel> embeddingModel;

    public ModelAvailability(ObjectProvider<ChatModel> chatModel, ObjectProvider<EmbeddingModel> embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public boolean chatModelConfigured() {
        return chatModel.getIfAvailable() != null;
    }

    public boolean embeddingModelConfigured() {
        return embeddingModel.getIfAvailable() != null;
    }
}
