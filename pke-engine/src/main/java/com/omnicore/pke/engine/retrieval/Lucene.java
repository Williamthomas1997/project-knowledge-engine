package com.omnicore.pke.engine.retrieval;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds a safe Lucene OR-query from free text: significant words only, no Lucene syntax from the user. */
public final class Lucene {

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "when", "what", "which", "who", "how", "does",
            "are", "is", "if", "in", "of", "to", "a", "an", "on", "by", "be", "it", "this", "that", "from", "into", "do", "or",
            "changes", "change", "affected", "modify", "show", "list", "all", "any", "about");

    private Lucene() {
    }

    public static String orQuery(String text) {
        Set<String> words = new LinkedHashSet<>();
        // the whole identifier as Lucene's standard analyzer indexes it (publishExecuted -> publishexecuted) ...
        Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3 && !STOP.contains(w)).forEach(words::add);
        // ... and its camelCase parts
        String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        Arrays.stream(spaced.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3 && !STOP.contains(w)).forEach(words::add);
        return words.stream().limit(24).collect(Collectors.joining(" OR "));
    }
}
