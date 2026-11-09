package com.omnicore.pke.engine.pipeline.skill;

import com.omnicore.pke.engine.graph.RuntimeDefinitionStore;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

/**
 * Loads {@code definitions/skills/<name>/SKILL.md} (and any extra locations), validates them, stores every version
 * as {@code :SkillDefinition} and decides the active version: the highest version found for each name.
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    static final Comparator<String> SEMVER = Comparator.comparing(SkillRegistry::semverKey);

    private final RuntimeDefinitionStore store;
    private final Set<String> toolNames;
    private final List<String> locations;
    private final Map<String, TreeMap<String, SkillDefinition>> skills = new ConcurrentHashMap<>();
    private final Map<String, Resource> directories = new ConcurrentHashMap<>();

    public SkillRegistry(RuntimeDefinitionStore store, List<SkillTool> tools,
                         @Value("${pke.skills.locations:classpath*:definitions/skills/*/SKILL.md}") List<String> locations) {
        this.store = store;
        this.toolNames = tools.stream().map(SkillTool::name).collect(Collectors.toUnmodifiableSet());
        this.locations = List.copyOf(locations);
    }

    /** Loads and registers all skills; called at startup (and by tests after wiping the database). */
    public synchronized void load() {
        Map<String, TreeMap<String, SkillDefinition>> loaded = new HashMap<>();
        Map<String, Resource> dirs = new HashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String location : locations) {
            Resource[] resources;
            try {
                resources = resolver.getResources(location);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list skills at " + location, e);
            }
            for (Resource resource : resources) {
                SkillDefinition skill = read(resource);
                List<Violation> violations = new ArrayList<>();
                skill.tools().stream().filter(t -> !toolNames.contains(t)).forEach(t ->
                        violations.add(new Violation("/tools", "unknown tool '" + t + "'; known: " + toolNames)));
                if (!violations.isEmpty()) {
                    throw new ValidationFailedException("Invalid skill " + skill.location(), violations);
                }
                SkillDefinition previous = loaded.computeIfAbsent(skill.name(), n -> new TreeMap<>(SEMVER))
                        .putIfAbsent(skill.version(), skill);
                if (previous != null && !previous.hash().equals(skill.hash())) {
                    throw new ValidationFailedException("Duplicate skill", List.of(new Violation("/version",
                            skill.ref() + " is defined twice with different content")));
                }
                dirs.put(skill.ref(), resource);
            }
        }
        loaded.forEach((name, versions) -> versions.values().forEach(skill -> store.register(
                RuntimeDefinitionStore.Kind.SKILL, skill.name(), skill.version(), skill.hash(),
                skill.version().equals(versions.lastKey()), Map.of("description", skill.description(),
                        "tools", skill.tools(), "appliesTo", skill.appliesTo(), "location", skill.location()))));
        skills.clear();
        skills.putAll(loaded);
        directories.clear();
        directories.putAll(dirs);
        log.info("Skills loaded: {}", loaded.keySet());
    }

    public SkillDefinition active(String name) {
        TreeMap<String, SkillDefinition> versions = skills.get(name);
        if (versions == null || versions.isEmpty()) {
            throw new NotFoundException("Skill " + name + " is not registered");
        }
        return versions.lastEntry().getValue();
    }

    public boolean exists(String name) {
        return skills.containsKey(name);
    }

    public Collection<SkillDefinition> activeSkills() {
        return skills.values().stream().map(v -> v.lastEntry().getValue())
                .sorted(Comparator.comparing(SkillDefinition::name)).toList();
    }

    public List<SkillDefinition> versions(String name) {
        TreeMap<String, SkillDefinition> versions = skills.get(name);
        if (versions == null) {
            throw new NotFoundException("Skill " + name + " is not registered");
        }
        return List.copyOf(versions.values());
    }

    /** Reads a file next to the skill's SKILL.md, e.g. an eval case ({@code evals/x.json}). */
    public String readSkillFile(SkillDefinition skill, String relativePath) {
        Resource skillFile = directories.get(skill.ref());
        try {
            Resource file = skillFile.createRelative(relativePath);
            try (InputStream in = file.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + relativePath + " of skill " + skill.ref(), e);
        }
    }

    private static SkillDefinition read(Resource resource) {
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read skill " + resource, e);
        }
        String path = String.valueOf(resource.getDescription()).replace('\\', '/');
        String directory = null;
        try {
            String uri = resource.getURI().toString();
            String parent = uri.substring(0, uri.lastIndexOf('/'));
            directory = parent.substring(parent.lastIndexOf('/') + 1);
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot determine the directory of {}", path);
        }
        return SkillDefinitionParser.parse(content, directory, "skills/" + directory + "/SKILL.md");
    }

    private static String semverKey(String version) {
        String[] parts = version.split("\\.");
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append(String.format("%09d", Long.parseLong(part)));
        }
        return key.toString();
    }
}
