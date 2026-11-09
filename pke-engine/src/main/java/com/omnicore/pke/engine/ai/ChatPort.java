package com.omnicore.pke.engine.ai;

/**
 * Provider-neutral chat completion used by skills. Implementations: the Spring AI model ({@code openai} profile),
 * recorded fixtures ({@code replay}), recording ({@code record}) and none (no model configured).
 * <p>
 * Implementations never log prompts or responses at INFO (they contain source content) and never log keys.
 */
public interface ChatPort {

    /** False when no model is configured: AI steps then report {@code SKIPPED_NO_MODEL}. */
    boolean available();

    /** Short description for system info, e.g. {@code openai}, {@code replay}, {@code none}. */
    String describe();

    /**
     * @throws AiCallException when the call fails; {@link AiCallException#isTransient()} tells whether a retry
     *                         may help
     */
    ChatResult call(ChatRequest request);
}
