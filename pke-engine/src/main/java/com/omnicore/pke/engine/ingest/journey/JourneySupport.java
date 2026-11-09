package com.omnicore.pke.engine.ingest.journey;

import com.omnicore.pke.spi.journey.JourneyDefinition.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers shared by the journey adapters: keys, JSON structure without values, variable references. */
final class JourneySupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern NON_KEY = Pattern.compile("[^a-z0-9]+");

    private JourneySupport() {
    }

    /** "International Transfer", "InternationalTransferJourney" and "international_transfer" all give "international-transfer". */
    static String key(String name, String... suffixes) {
        String base = name.strip();
        for (String suffix : suffixes) {
            if (base.endsWith(suffix) && base.length() > suffix.length()) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        base = base.replaceAll("([a-z0-9])([A-Z])", "$1-$2");
        return NON_KEY.matcher(base.toLowerCase(Locale.ROOT)).replaceAll("-").replaceAll("^-+|-+$", "");
    }

    /** Parses JSON that may contain comments or template variables; empty when it is not JSON. */
    static Optional<JsonNode> lenientJson(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = stripComments(text).replaceAll("(?<![\"\\w])\\{\\{[^}]+}}(?![\"\\w])", "null")
                .replaceAll("(?<![\"\\w])\\$\\{[^}]+}(?![\"\\w])", "null")
                .replaceAll("(?<![\"\\w])#\\{[^}]+}(?![\"\\w])", "null")
                .replaceAll(",\\s*([}\\]])", "$1");
        try {
            JsonNode node = JSON.readTree(cleaned);
            return node == null || node.isMissingNode() ? Optional.empty() : Optional.of(node);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /** Field names and types of a JSON value; values are dropped. Arrays describe their first element. */
    static Field structure(String name, JsonNode node) {
        if (node.isObject()) {
            List<Field> children = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                children.add(structure(e.getKey(), e.getValue()));
            }
            return new Field(name, "object", children);
        }
        if (node.isArray()) {
            return new Field(name, "array", node.isEmpty() ? List.of() : List.of(structure(null, node.get(0))));
        }
        if (node.isNumber()) {
            return new Field(name, "number", List.of());
        }
        if (node.isBoolean()) {
            return new Field(name, "boolean", List.of());
        }
        if (node.isNull()) {
            return new Field(name, "null", List.of());
        }
        return new Field(name, "string", List.of());
    }

    /** Calls {@code visitor} with (dotted field path, text value) for every text value in a JSON tree. */
    static void textValues(String path, JsonNode node, BiConsumer<String, String> visitor) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                textValues(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), visitor);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                textValues(path + "[]", item, visitor);
            }
        } else if (node.isString()) {
            visitor.accept(path, node.asString());
        }
    }

    /** Removes // and /* *&#47; comments outside JSON strings. */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
