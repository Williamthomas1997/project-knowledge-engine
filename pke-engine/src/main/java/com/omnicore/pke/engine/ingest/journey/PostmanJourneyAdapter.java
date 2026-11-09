package com.omnicore.pke.engine.ingest.journey;

import com.omnicore.pke.spi.journey.JourneyContext;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import com.omnicore.pke.spi.journey.JourneyDefinition.ApiCall;
import com.omnicore.pke.spi.journey.JourneyDefinition.Feed;
import com.omnicore.pke.spi.journey.JourneyDefinition.Field;
import com.omnicore.pke.spi.journey.JourneyDefinition.Step;
import com.omnicore.pke.spi.journey.JourneyDefinition.Unresolved;
import com.omnicore.pke.spi.journey.JourneySourceAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Postman collections (v2.x). One journey per collection; its requests, depth first, are the ordered steps.
 * Header codes of the gateway service headers are kept (they identify the API); every other value (tokens, sample
 * bodies, environment values) is dropped. {@code pm.environment.set("x", ...)} in a step's test script plus a later
 * {@code {{x}}} in a URL, header or body field gives a {@code FEEDS} edge.
 */
@Component
public class PostmanJourneyAdapter implements JourneySourceAdapter {

    private static final Pattern SET = Pattern.compile(
            "pm\\.(?:environment|collectionVariables|globals|variables)\\.set\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern USE = Pattern.compile("\\{\\{([^}]+)}}");

    @Override
    public String id() {
        return "journey.postman";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(JourneyContext context, Path file) {
        if (!file.toString().endsWith(".json")) {
            return false;
        }
        try {
            String head = new String(Files.readAllBytes(context.root().resolve(file)), StandardCharsets.UTF_8);
            return head.contains("schema.getpostman.com") && head.contains("\"item\"");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public List<JourneyDefinition> read(JourneyContext context, Path file) {
        JsonNode collection;
        try {
            collection = JourneySupport.JSON.readTree(Files.readString(context.root().resolve(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = collection.path("info").path("name").asString(file.getFileName().toString());
        Map<String, String> collectionVariables = new HashMap<>();
        for (JsonNode v : collection.path("variable")) {
            collectionVariables.put(v.path("key").asString(), v.path("value").asString(""));
        }
        List<JsonNode> requests = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        flatten(collection.path("item"), "", requests, locations);

        List<Step> steps = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        Map<String, Integer> setBy = new HashMap<>();
        List<Feed> feeds = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            int order = i + 1;
            JsonNode item = requests.get(i);
            JsonNode request = item.path("request");
            String label = item.path("name").asString("request " + order);
            Map<String, String> usages = new LinkedHashMap<>();
            ApiCall api = apiCall(context, request, item, collectionVariables, usages, unresolved, locations.get(i));
            steps.add(new Step(order, label, api, locations.get(i)));
            usages.forEach((field, variable) -> {
                Integer from = setBy.get(variable);
                if (from != null) {
                    feeds.add(new Feed(from, order, variable, field));
                }
            });
            for (JsonNode event : item.path("event")) {
                for (JsonNode line : event.path("script").path("exec")) {
                    Matcher m = SET.matcher(line.asString(""));
                    while (m.find()) {
                        setBy.put(m.group(1), order);
                    }
                }
            }
        }
        return List.of(new JourneyDefinition(JourneySupport.key(name), name, "postman", file.toString().replace('\\', '/'),
                steps, feeds, List.of(), unresolved));
    }

    private void flatten(JsonNode items, String path, List<JsonNode> requests, List<String> locations) {
        for (JsonNode item : items) {
            String location = path.isEmpty() ? item.path("name").asString("") : path + " / " + item.path("name").asString("");
            if (item.has("item")) {
                flatten(item.path("item"), location, requests, locations);
            } else if (item.has("request")) {
                requests.add(item);
                locations.add(location);
            }
        }
    }

    private ApiCall apiCall(JourneyContext context, JsonNode request, JsonNode item, Map<String, String> variables,
                            Map<String, String> usages, List<Unresolved> unresolved, String location) {
        String method = request.path("method").asString("GET").toUpperCase(Locale.ROOT);
        JsonNode url = request.path("url");
        String path;
        if (url.isString()) {
            path = url.asString();
        } else if (url.path("path").isArray()) {
            List<String> segments = new ArrayList<>();
            url.path("path").forEach(s -> segments.add(s.asString()));
            path = "/" + String.join("/", segments);
        } else {
            path = url.path("raw").asString("/");
        }
        path = path.replaceAll("^\\{\\{[^}]+}}", "");
        collectUses("path", path, usages);
        List<String> query = new ArrayList<>();
        for (JsonNode q : url.path("query")) {
            if (!q.path("disabled").asBoolean(false)) {
                query.add(q.path("key").asString());
                collectUses("query:" + q.path("key").asString(), q.path("value").asString(""), usages);
            }
        }
        Map<String, String> gateway = new LinkedHashMap<>();
        List<String> otherHeaders = new ArrayList<>();
        for (JsonNode h : request.path("header")) {
            if (h.path("disabled").asBoolean(false)) {
                continue;
            }
            String key = h.path("key").asString();
            String value = h.path("value").asString("");
            if (context.serviceHeaders().stream().anyMatch(s -> s.equalsIgnoreCase(key))) {
                Matcher m = USE.matcher(value);
                if (m.matches()) {
                    String resolved = variables.get(m.group(1));
                    if (resolved == null) {
                        unresolved.add(new Unresolved(location, "header " + key + " uses variable " + value));
                    }
                    value = resolved == null ? "" : resolved;
                }
                gateway.put(canonicalHeader(context, key), value);
            } else {
                otherHeaders.add(key);
                collectUses("header:" + key, value, usages);
            }
        }
        Field requestFields = null;
        String raw = request.path("body").path("raw").asString(null);
        if (raw != null && !raw.isBlank()) {
            Optional<JsonNode> body = JourneySupport.lenientJson(raw);
            if (body.isPresent()) {
                requestFields = JourneySupport.structure(null, body.get());
                JsonNode original = JourneySupport.lenientJson(raw.replaceAll("\\{\\{([^}]+)}}", "@@$1@@")).orElse(null);
                if (original != null) {
                    JourneySupport.textValues("", original, (field, value) -> {
                        Matcher m = Pattern.compile("@@([^@]+)@@").matcher(value);
                        while (m.find()) {
                            usages.put(field, m.group(1));
                        }
                    });
                }
            } else {
                unresolved.add(new Unresolved(location, "request body is not JSON; field structure not recorded"));
            }
        }
        Field responseFields = null;
        for (JsonNode example : item.path("response")) {
            Optional<JsonNode> body = JourneySupport.lenientJson(example.path("body").asString(null));
            if (body.isPresent()) {
                responseFields = JourneySupport.structure(null, body.get());
                break;
            }
        }
        return new ApiCall(method, path, gateway, otherHeaders, List.of(), query, requestFields, responseFields);
    }

    private static void collectUses(String field, String text, Map<String, String> usages) {
        Matcher m = USE.matcher(text == null ? "" : text);
        while (m.find()) {
            usages.put(field.equals("path") ? "path:" + m.group(1).strip() : field, m.group(1).strip());
        }
    }

    private static String canonicalHeader(JourneyContext context, String key) {
        return context.serviceHeaders().stream().filter(s -> s.equalsIgnoreCase(key)).findFirst().orElse(key);
    }
}
