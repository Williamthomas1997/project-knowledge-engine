package com.omnicore.pke.engine.ai;

import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Chooses the {@link ChatPort} and {@link EmbeddingPort} from {@code pke.ai.mode} (see {@link AiProperties}). */
@Configuration(proxyBeanMethods = false)
class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    @Bean
    ChatPort chatPort(AiProperties properties, ObjectProvider<ChatModel> chatModel, ResourceLoader resources) {
        ChatModel model = chatModel.getIfAvailable();
        ChatPort port = switch (properties.mode()) {
            case NONE -> new NoModelChatPort();
            case AUTO -> model == null ? new NoModelChatPort() : new SpringAiChatPort(model, properties.provider());
            case REPLAY -> replay(properties, resources);
            case RECORD -> {
                if (model == null || properties.record().dir() == null) {
                    throw new IllegalStateException("pke.ai.mode=record needs a chat model (OPENAI_API_KEY, "
                            + "spring.ai.model.chat=openai) and pke.ai.record.dir");
                }
                yield new RecordingChatPort(replay(properties, resources), new SpringAiChatPort(model, properties.provider()),
                        Path.of(properties.record().dir()), properties.record().overwrite());
            }
        };
        log.info("Chat model: {}", port.describe());
        return port;
    }

    @Bean
    EmbeddingPort embeddingPort(AiProperties properties, ObjectProvider<EmbeddingModel> embeddingModel) {
        EmbeddingModel model = properties.mode() == AiProperties.Mode.NONE ? null : embeddingModel.getIfAvailable();
        return new EmbeddingPort() {
            @Override
            public boolean available() {
                return model != null;
            }

            @Override
            public String describe() {
                return model == null ? "none" : properties.provider();
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                if (model == null) {
                    throw new AiCallException("No embedding model is configured", false, null);
                }
                return model.embed(texts);
            }
        };
    }

    private static ReplayChatPort replay(AiProperties properties, ResourceLoader resources) {
        return new ReplayChatPort(resources, properties.replay().fixtures(), Path.of(properties.replay().missingDir()));
    }
}
