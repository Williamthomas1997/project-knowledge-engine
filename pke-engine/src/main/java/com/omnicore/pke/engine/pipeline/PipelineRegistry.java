package com.omnicore.pke.engine.pipeline;

import com.omnicore.pke.engine.ai.JsonSchemas;
import com.omnicore.pke.engine.graph.RuntimeDefinitionStore;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.pipeline.skill.SkillRegistry;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads pipeline definitions, validates them (meta-schema, known steps and skills, {@code forEach} references to
 * earlier steps), stores every version as {@code :PipelineDefinition} and activates the highest version.
 */
@Service
public class PipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistry.class);
    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonSchemas.Compiled META = JsonSchemas.classpath("definitions/meta/pipeline.schema.json");
    private static final Comparator<String> SEMVER = Comparator.comparing(PipelineRegistry::semverKey);

    private final RuntimeDefinitionStore store;
    private final SkillRegistry skills;
    private final Set<String> stepNames;
    private final List<String> locations;
    private final Map<String, TreeMap<String, PipelineDefinition>> pipelines = new ConcurrentHashMap<>();

    public PipelineRegistry(RuntimeDefinitionStore store, SkillRegistry skills, List<PipelineStep> steps,
                            @Value("${pke.pipelines.locations:classpath*:definitions/pipelines/*.yaml}") List<String> locations) {
        this.store = store;
        this.skills = skills;
        this.stepNames = steps.stream().map(PipelineStep::name).collect(Collectors.toUnmodifiableSet());
        this.locations = List.copyOf(locations);
    }

    public synchronized void load() {
        Map<String, TreeMap<String, PipelineDefinition>> loaded = new HashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String location : locations) {
            Resource[] resources;
            try {
                resources = resolver.getResources(location);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list pipelines at " + location, e);
            }
            for (Resource resource : resources) {
                PipelineDefinition definition = parse(read(resource), "pipelines/" + resource.getFilename());
                PipelineDefinition previous = loaded.computeIfAbsent(definition.id(), n -> new TreeMap<>(SEMVER))
                        .putIfAbsent(definition.version(), definition);
                if (previous != null && !previous.hash().equals(definition.hash())) {
                    throw new ValidationFailedException("Duplicate pipeline", List.of(new Violation("/version",
                            definition.ref() + " is defined twice with different content")));
                }
            }
        }
        loaded.forEach((id, versions) -> versions.values().forEach(p -> store.register(
                RuntimeDefinitionStore.Kind.PIPELINE, p.id(), p.version(), p.hash(), p.version().equals(versions.lastKey()),
                Map.of("description", p.description(), "steps", p.steps().stream().map(s -> s.id() + "=" + s.uses()).toList(),
                        "location", p.location()))));
        pipelines.clear();
        pipelines.putAll(loaded);
        log.info("Pipelines loaded: {}", loaded.keySet());
    }

    public PipelineDefinition active(String id) {
        TreeMap<String, PipelineDefinition> versions = pipelines.get(id);
        if (versions == null || versions.isEmpty()) {
            throw new NotFoundException("Pipeline " + id + " is not registered");
        }
        return versions.lastEntry().getValue();
    }

    public PipelineDefinition version(String id, String version) {
        TreeMap<String, PipelineDefinition> versions = pipelines.get(id);
        PipelineDefinition definition = versions == null ? null : versions.get(version);
        if (definition == null) {
            throw new NotFoundException("Pipeline " + id + " " + version + " is not registered");
        }
        return definition;
    }

    public Collection<PipelineDefinition> activePipelines() {
        return pipelines.values().stream().map(v -> v.lastEntry().getValue())
                .sorted(Comparator.comparing(PipelineDefinition::id)).toList();
    }

    PipelineDefinition parse(String content, String location) {
        String text = content.replace("\r\n", "\n");
        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (JacksonException e) {
            throw invalid(location, "$", "not valid YAML: " + e.getOriginalMessage());
        }
        if (tree == null || !tree.isObject()) {
            throw invalid(location, "$", "must be a YAML mapping");
        }
        List<Violation> violations = new ArrayList<>(META.validate(tree));
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid pipeline " + location, violations);
        }
        JsonSchemas.Compiled input = null;
        if (tree.has("input")) {
            try {
                input = JsonSchemas.compile(tree.get("input"));
            } catch (RuntimeException e) {
                violations.add(new Violation("/input", "is not a valid JSON Schema: " + e.getMessage()));
            }
        }
        List<PipelineDefinition.SourceField> sources = new ArrayList<>();
        tree.path("idempotency").path("sources").forEach(s -> sources.add(
                new PipelineDefinition.SourceField(s.get("field").asString(), s.path("prefix").asString(""))));
        List<PipelineDefinition.Step> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonNode stepNodes = tree.get("steps");
        for (int i = 0; i < stepNodes.size(); i++) {
            JsonNode s = stepNodes.get(i);
            String path = "/steps/" + i;
            String id = s.get("id").asString();
            String uses = s.get("uses").asString();
            if (!seen.add(id)) {
                violations.add(new Violation(path + "/id", "step id '" + id + "' is used twice"));
            }
            if (uses.startsWith("skill:")) {
                if (!skills.exists(uses.substring(6))) {
                    violations.add(new Violation(path + "/uses", "unknown skill '" + uses.substring(6) + "'"));
                }
                if (!s.has("forEach")) {
                    violations.add(new Violation(path + "/forEach", "is required for a skill step"));
                }
            } else {
                if (!stepNames.contains(uses)) {
                    violations.add(new Violation(path + "/uses", "unknown step '" + uses + "'; known: " + stepNames));
                }
                if (s.has("forEach")) {
                    violations.add(new Violation(path + "/forEach", "is only allowed on skill steps"));
                }
            }
            if (s.has("forEach")) {
                String source = s.get("forEach").asString().split("\\.")[0];
                if (!seen.contains(source) || source.equals(id)) {
                    violations.add(new Violation(path + "/forEach", "must refer to an earlier step, not '" + source + "'"));
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> with = s.has("with") ? JSON.convertValue(s.get("with"), Map.class) : Map.of();
            steps.add(new PipelineDefinition.Step(id, uses, s.path("forEach").asString(null), with,
                    duration(s.path("timeout").asString("PT10M"), path + "/timeout", violations),
                    s.path("retry").path("maxAttempts").asInt(1),
                    duration(s.path("retry").path("backoff").asString("PT1S"), path + "/retry/backoff", violations)));
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid pipeline " + location, violations);
        }
        return new PipelineDefinition(tree.get("id").asString(), tree.get("version").asString(),
                tree.get("description").asString(), input, List.copyOf(sources), List.copyOf(steps), Hashes.sha256(text),
                location);
    }

    private static Duration duration(String value, String path, List<Violation> violations) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            violations.add(new Violation(path, "is not an ISO-8601 duration"));
            return Duration.ofMinutes(10);
        }
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read pipeline " + resource, e);
        }
    }

    private static ValidationFailedException invalid(String location, String path, String message) {
        return new ValidationFailedException("Invalid pipeline " + location, List.of(new Violation(path, message)));
    }

    private static String semverKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) {
            key.append(String.format("%09d", Long.parseLong(part)));
        }
        return key.toString();
    }
}
