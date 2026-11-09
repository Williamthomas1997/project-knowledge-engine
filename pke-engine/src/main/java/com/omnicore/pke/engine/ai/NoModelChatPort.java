package com.omnicore.pke.engine.ai;

/** Used when no chat model is configured. Callers check {@link #available()} first. */
final class NoModelChatPort implements ChatPort {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String describe() {
        return "none";
    }

    @Override
    public ChatResult call(ChatRequest request) {
        throw new AiCallException("No chat model is configured (start with the 'openai' profile)", false, null);
    }
}
