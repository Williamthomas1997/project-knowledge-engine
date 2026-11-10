package com.omnicore.pke.engine.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Rank fusion and diversification, as in Graphiti's search (ADR-014): reciprocal-rank fusion over ranked lists and
 * maximal marginal relevance over embeddings.
 */
public final class Fusion {

    /** RRF rank constant. Graphiti uses 1; 60 (the original RRF paper) flattens the head of each list. */
    public static final int RRF_K = 60;

    private Fusion() {
    }

    /** score(id) = Σ over lists of 1 / (k + rank), rank starting at 1. Ties keep first-seen order. */
    public static Map<String, Double> rrf(List<List<String>> rankings, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                scores.merge(ranking.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Double> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /**
     * Greedy MMR: repeatedly picks the candidate maximising {@code λ·sim(query, c) − (1−λ)·max sim(c, selected)}.
     * Vectors must be L2-normalised (dot product = cosine).
     */
    public static <T> List<T> mmr(List<T> candidates, Function<T, float[]> vector, float[] query, double lambda, int limit) {
        List<T> remaining = new ArrayList<>(candidates);
        List<T> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            T best = remaining.stream().max(Comparator.comparingDouble(c -> {
                double relevance = dot(query, vector.apply(c));
                double redundancy = selected.stream().mapToDouble(s -> dot(vector.apply(s), vector.apply(c))).max().orElse(0);
                return lambda * relevance - (1 - lambda) * redundancy;
            })).orElseThrow();
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }

    static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
