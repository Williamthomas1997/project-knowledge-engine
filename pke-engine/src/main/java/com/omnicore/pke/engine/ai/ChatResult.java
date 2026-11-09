package com.omnicore.pke.engine.ai;

/**
 * The model's answer.
 *
 * @param content   raw text returned (expected to be JSON)
 * @param provider  e.g. {@code openai}, or {@code fixture} for a hand-authored replay fixture
 * @param model     model id as reported by the provider (or recorded in the fixture)
 * @param replayed  true when the answer came from a fixture instead of a live call
 */
public record ChatResult(String content, String provider, String model, int tokensIn, int tokensOut, long durationMs,
                         boolean replayed) {
}
