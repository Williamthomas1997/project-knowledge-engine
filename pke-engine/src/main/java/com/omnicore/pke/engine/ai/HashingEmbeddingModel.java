package com.omnicore.pke.engine.ai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, local embeddings by feature hashing (no network, no key): lower-cased words, camelCase parts,
 * word bigrams and character trigrams are hashed into {@code dimensions} buckets with a sign, then L2-normalised.
 * Texts that share words are close; it has no notion of synonyms. Default for development and tests (ADR-014).
 */
public final class HashingEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public HashingEmbeddingModel(int dimensions) {
        if (dimensions < 16) {
            throw new IllegalArgumentException("dimensions must be at least 16");
        }
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vector(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    float[] vector(String text) {
        float[] v = new float[dimensions];
        List<String> words = words(text == null ? "" : text);
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            add(v, "w:" + word, 1.0f);
            if (i > 0) {
                add(v, "b:" + words.get(i - 1) + " " + word, 0.5f);
            }
            String padded = "^" + word + "$";
            for (int c = 0; c + 3 <= padded.length(); c++) {
                add(v, "c:" + padded.substring(c, c + 3), 0.25f);
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= scale;
            }
        } else {
            v[0] = 1; // a zero vector has no direction; cosine search needs one
        }
        return v;
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        // split camelCase and PascalCase before lower-casing: validateCurrency -> validate, currency
        String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        for (String w : spaced.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isBlank()) {
                words.add(w);
            }
        }
        return words;
    }

    private void add(float[] v, String feature, float weight) {
        long h = fnv1a64(feature);
        int bucket = (int) Math.floorMod(h, (long) dimensions);
        v[bucket] += ((h >>> 63) == 0 ? 1 : -1) * weight;
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
