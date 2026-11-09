package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Registered skill and pipeline definitions ({@code :SkillDefinition}, {@code :PipelineDefinition}). A name +
 * version is immutable: the same version with different content is refused. The registry marks exactly one
 * version per name {@code active}. Operational records: no change events (evidence records the skill and pipeline
 * versions that produced each fact).
 */
@Service
public class RuntimeDefinitionStore {

    public enum Kind {
        SKILL("SkillDefinition"), PIPELINE("PipelineDefinition");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public enum Outcome { CREATED, UNCHANGED }

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public RuntimeDefinitionStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    /** Registers a version and makes {@code active} the only active version of the name. */
    public Outcome register(Kind kind, String name, String version, String hash, boolean active,
                            Map<String, Object> properties) {
        String id = PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, kind.name() + ":" + name + "@" + version).toString();
        return transactions.write("registry", null, ctx -> {
            var existing = ctx.single("MATCH (d:$($label) {pke_id: $id}) RETURN d.hash AS hash",
                    Map.of("label", kind.label, "id", id));
            Outcome outcome;
            if (existing.isPresent()) {
                String stored = existing.get().get("hash").asString();
                if (!stored.equals(hash)) {
                    throw new ConflictException(kind.label + " " + name + " " + version
                            + " is already registered with different content: change the content, bump the version");
                }
                outcome = Outcome.UNCHANGED;
            } else {
                Map<String, Object> props = new HashMap<>(properties);
                props.put("pke_id", id);
                props.put("name", name);
                props.put("version", version);
                props.put("hash", hash);
                props.put("registeredAt", OffsetDateTime.now(ZoneOffset.UTC));
                ctx.run("CREATE (d:$($label)) SET d = $props", Map.of("label", kind.label, "props", props));
                outcome = Outcome.CREATED;
            }
            ctx.run("MATCH (d:$($label) {name: $name}) SET d.active = (d.pke_id = $id)",
                    Map.of("label", kind.label, "name", name, "id", id));
            return outcome;
        });
    }

    public List<Map<String, Object>> list(Kind kind, String name) {
        Map<String, Object> params = new HashMap<>();
        params.put("label", kind.label);
        params.put("name", name);
        return reader.query("""
                MATCH (d:$($label)) WHERE $name IS NULL OR d.name = $name
                RETURN properties(d) AS d ORDER BY d.name, d.registeredAt""", params)
                .stream().map(r -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = new HashMap<>((Map<String, Object>) r.get("d"));
                    d.put("id", d.remove("pke_id"));
                    return d;
                }).toList();
    }
}
