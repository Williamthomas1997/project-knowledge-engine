package com.omnicore.pke.engine.history;

import com.omnicore.pke.engine.graph.HistoryQueries;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemChange;
import com.omnicore.pke.engine.graph.HistoryStore;
import com.omnicore.pke.engine.graph.HistoryStore.SnapshotView;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.ops.SystemInfo;
import com.omnicore.pke.engine.ops.SystemInfoService;
import com.omnicore.pke.engine.retrieval.QueryTemplates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Snapshots (plan §5): the {@code seq} plus everything needed to explain the knowledge at that point, i.e. active
 * schema, skill, pipeline and query-template versions, source versions (Git commits and document versions), model
 * and embedding configuration, the embedding generation of every collection and the application release.
 */
@Service
public class SnapshotService {

    private final HistoryStore store;
    private final HistoryQueries history;
    private final QueryTemplates templates;
    private final SystemInfoService system;
    private final String configuredRelease;
    private volatile String release;

    public SnapshotService(HistoryStore store, HistoryQueries history, QueryTemplates templates, SystemInfoService system,
                           @Value("${pke.release:}") String configuredRelease) {
        this.store = store;
        this.history = history;
        this.templates = templates;
        this.system = system;
        this.configuredRelease = configuredRelease;
    }

    public record SnapshotDiff(SnapshotView from, SnapshotView to, Map<String, Object> configuration,
                               Map<String, Map<String, List<ItemChange>>> entities,
                               Map<String, Map<String, List<ItemChange>>> relationships, Map<String, Integer> counts) {
    }

    public SnapshotView create(String label, String actor) {
        if (label != null && !label.matches("^[A-Za-z0-9][A-Za-z0-9 ._:/-]{0,99}$")) {
            throw ValidationFailedException.of("/label", "letters, digits, space and ._:/- only, at most 100 characters");
        }
        Map<String, Object> contents = new TreeMap<>(store.graphConfiguration());
        Map<String, Object> queries = new TreeMap<>();
        templates.all().forEach(t -> queries.put(t.id(), t.version()));
        contents.put("queryTemplates", queries);
        SystemInfo info = system.info();
        Map<String, Object> model = new TreeMap<>();
        model.put("chatModel", Objects.toString(info.chatModel(), "none"));
        model.put("embeddingModel", Objects.toString(info.embeddingModel(), "none"));
        model.put("vectorStore", info.vectorStore());
        model.put("profiles", String.join(",", info.activeProfiles()));
        contents.put("model", model);
        contents.put("appRelease", release(info.appVersion()));
        return store.create(label, contents, actor);
    }

    public SnapshotView get(String id) {
        return store.snapshot(id);
    }

    public List<SnapshotView> list(int limit) {
        return store.snapshots(limit);
    }

    /** What changed between two snapshots: knowledge grouped by kind of change and type, and configuration. */
    public SnapshotDiff diff(String fromId, String toId) {
        SnapshotView from = store.snapshot(fromId);
        SnapshotView to = store.snapshot(toId);
        if (from.seq() > to.seq()) {
            throw ValidationFailedException.of("/from", "snapshot " + fromId + " is later than " + toId);
        }
        Map<String, Map<String, List<ItemChange>>> entities = new TreeMap<>();
        Map<String, Map<String, List<ItemChange>>> relationships = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemChange change : history.diff(from.seq(), to.seq())) {
            var target = change.kind().equals("ENTITY") ? entities : relationships;
            target.computeIfAbsent(change.change(), k -> new TreeMap<>())
                    .computeIfAbsent(change.type(), k -> new java.util.ArrayList<>()).add(change);
            counts.merge(change.kind() + ":" + change.change(), 1, Integer::sum);
        }
        return new SnapshotDiff(from, to, configurationDiff(from.contents(), to.contents()), entities, relationships, counts);
    }

    /** Per section, the keys whose value differs: {@code {section: {key: {from, to}}}}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> configurationDiff(Map<String, Object> from, Map<String, Object> to) {
        Map<String, Object> diff = new TreeMap<>();
        TreeSet<String> sections = new TreeSet<>(from.keySet());
        sections.addAll(to.keySet());
        for (String section : sections) {
            Object a = from.get(section);
            Object b = to.get(section);
            if (Objects.equals(a, b)) {
                continue;
            }
            if (a instanceof Map<?, ?> || b instanceof Map<?, ?>) {
                Map<String, Object> left = a instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                Map<String, Object> right = b instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                TreeSet<String> keys = new TreeSet<>(left.keySet());
                keys.addAll(right.keySet());
                Map<String, Object> changed = new TreeMap<>();
                for (String key : keys) {
                    if (!Objects.equals(left.get(key), right.get(key))) {
                        Map<String, Object> pair = new LinkedHashMap<>();
                        pair.put("from", left.get(key));
                        pair.put("to", right.get(key));
                        changed.put(key, pair);
                    }
                }
                diff.put(section, changed);
            } else {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("from", a);
                pair.put("to", b);
                diff.put(section, pair);
            }
        }
        return diff;
    }

    /** {@code pke.release}, else {@code git describe} of the working directory, else the build version. */
    private String release(String buildVersion) {
        if (!configuredRelease.isBlank()) {
            return configuredRelease;
        }
        if (release == null) {
            release = gitDescribe().orElse(buildVersion);
        }
        return release;
    }

    private static java.util.Optional<String> gitDescribe() {
        try {
            Process process = new ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return java.util.Optional.empty();
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 && !out.isEmpty() ? java.util.Optional.of(out) : java.util.Optional.empty();
        } catch (IOException e) {
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
    }
}
