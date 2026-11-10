package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.ai.JsonSchemas;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parameterised graph query templates ({@code definitions/queries/*.yaml}, ADR-007 §7 step 3). Loaded at startup,
 * validated against {@code definitions/meta/query-template.schema.json} and the {@link CypherGuard} (templates obey
 * the same read-only vocabulary rules as LLM-written Cypher), selected by seed type and question keywords.
 */
@Service
public class QueryTemplates {

    private static final Logger log = LoggerFactory.getLogger(QueryTemplates.class);
    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final JsonSchemas.Compiled META = JsonSchemas.classpath("definitions/meta/query-template.schema.json");

    public record Template(String id, String version, String description, List<String> intents, List<String> keywords,
                           List<String> seedTypes, boolean isDefault, String cypher) {

        boolean appliesTo(String type) {
            return seedTypes.contains("*") || seedTypes.contains(type);
        }

        int keywordHits(String question) {
            String q = question.toLowerCase(Locale.ROOT);
            return (int) keywords.stream().filter(k -> q.contains(k.toLowerCase(Locale.ROOT))).count();
        }
    }

    private final GuardedCypher cypher;
    private final List<String> locations;
    private final Map<String, Template> templates = new ConcurrentHashMap<>();

    public QueryTemplates(GuardedCypher cypher,
                          @Value("${pke.retrieval.template-locations:classpath*:definitions/queries/*.yaml}") List<String> locations) {
        this.cypher = cypher;
        this.locations = List.copyOf(locations);
    }

    public synchronized void load() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, Template> loaded = new ConcurrentHashMap<>();
        for (String location : locations) {
            try {
                for (Resource resource : resolver.getResources(location)) {
                    Template template = parse(resource);
                    loaded.put(template.id(), template);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list query templates at " + location, e);
            }
        }
        templates.clear();
        templates.putAll(loaded);
        log.info("Query templates loaded: {}", loaded.keySet());
    }

    public List<Template> all() {
        return templates.values().stream().sorted(Comparator.comparing(Template::id)).toList();
    }

    public Template get(String id) {
        Template template = templates.get(id);
        if (template == null) {
            throw new NotFoundException("Query template " + id + " does not exist");
        }
        return template;
    }

    /**
     * Templates for a seed of {@code type}: those whose keywords occur in the question (most hits first); when none
     * match, the type's default templates.
     */
    public List<Template> select(String question, String type) {
        List<Template> applicable = all().stream().filter(t -> t.appliesTo(type)).toList();
        List<Template> matched = applicable.stream().filter(t -> t.keywordHits(question) > 0)
                .sorted(Comparator.comparingInt((Template t) -> -t.keywordHits(question)).thenComparing(Template::id))
                .toList();
        return matched.isEmpty() ? applicable.stream().filter(Template::isDefault).toList() : matched;
    }

    public GuardedCypher.Execution run(String id, String seedId, int limit) {
        Template template = get(id);
        return cypher.run(template.cypher(), Map.of("seedId", seedId, "limit", Math.clamp(limit, 1, 200)), "template " + id);
    }

    private Template parse(Resource resource) throws IOException {
        JsonNode tree;
        try (InputStream in = resource.getInputStream()) {
            tree = YAML.readTree(in);
        }
        List<Violation> violations = new ArrayList<>(META.validate(tree));
        if (violations.isEmpty()) {
            violations.addAll(cypher.check(tree.get("cypher").asString()).violations());
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid query template " + resource.getFilename(), violations);
        }
        return new Template(tree.get("id").asString(), tree.get("version").asString(), tree.get("description").asString(),
                strings(tree.get("intents")), strings(tree.get("keywords")), strings(tree.get("seedTypes")),
                tree.path("default").asBoolean(false), tree.get("cypher").asString());
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null) {
            node.forEach(n -> values.add(n.asString()));
        }
        return List.copyOf(values);
    }
}
