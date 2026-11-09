package com.omnicore.pke.engine.ai;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Calls the configured Spring AI {@link ChatModel}. For OpenAI the request asks for a JSON object response; the
 * schema itself is part of the system message and the answer is validated by {@link StructuredOutput} for every
 * provider (strict provider-side schemas would force every property to be required).
 */
final class SpringAiChatPort implements ChatPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatPort.class);

    private final ChatModel model;
    private final String provider;

    SpringAiChatPort(ChatModel model, String provider) {
        this.model = model;
        this.provider = provider;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return provider;
    }

    @Override
    public ChatResult call(ChatRequest request) {
        List<Message> messages = List.of(new SystemMessage(request.system()), new UserMessage(request.user()));
        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(new Prompt(messages, options()));
        } catch (RuntimeException e) {
            // Never include the prompt: it contains source content.
            log.warn("Model call for skill {} failed: {}", request.skillId(), e.getClass().getSimpleName());
            throw new AiCallException("Model call failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    AiCallException.looksTransient(e), e);
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new AiCallException("The model returned no result", true, null);
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String modelId = response.getMetadata() == null ? null : response.getMetadata().getModel();
        log.debug("Skill {} answered by {} in {} ms (prompt {})", request.skillId(), modelId, durationMs, request.promptHash());
        return new ChatResult(response.getResult().getOutput().getText(), provider,
                modelId == null || modelId.isBlank() ? "unknown" : modelId,
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(), durationMs, false);
    }

    private ChatOptions options() {
        if (model instanceof OpenAiChatModel) {
            return OpenAiChatOptions.builder()
                    .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                            .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build())
                    .build();
        }
        return null;
    }
}
