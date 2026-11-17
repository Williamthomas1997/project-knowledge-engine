package com.omnicore.pke.engine.graph;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * API keys (ADR-017): {@code (:ApiKey {pke_id, name, keyHash, prefix, roles, createdAt, createdBy, revokedAt})}.
 * Only the SHA-256 of a key is stored; the key itself is shown once, by the CLI that creates it. Operational records,
 * no change events.
 */
@Service
public class ApiKeyStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public ApiKeyStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public record StoredKey(String id, String name, String prefix, List<String> roles, String createdAt,
                            String createdBy, String revokedAt) {
    }

    public void create(String id, String name, String keyHash, String prefix, List<String> roles, String createdBy) {
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", id);
        props.put("name", name);
        props.put("keyHash", keyHash);
        props.put("prefix", prefix);
        props.put("roles", roles);
        props.put("createdBy", createdBy);
        transactions.write(createdBy, null, ctx -> {
            props.put("createdAt", ctx.now());
            return ctx.run("CREATE (k:ApiKey) SET k = $props", Map.of("props", props));
        });
    }

    /** The active (not revoked) key with this hash. */
    public Optional<StoredKey> findActive(String keyHash) {
        return reader.single("MATCH (k:ApiKey {keyHash: $hash}) WHERE k.revokedAt IS NULL RETURN properties(k) AS k",
                Map.of("hash", keyHash)).map(r -> view(KnowledgeQueryService.map(r.get("k"))));
    }

    public List<StoredKey> list() {
        return reader.query("MATCH (k:ApiKey) RETURN properties(k) AS k ORDER BY k.createdAt", Map.of())
                .stream().map(r -> view(KnowledgeQueryService.map(r.get("k")))).toList();
    }

    /** Revokes every active key with this name; returns how many. */
    public long revoke(String name, String actor) {
        return transactions.write(actor, null, ctx -> ctx.run("""
                MATCH (k:ApiKey {name: $name}) WHERE k.revokedAt IS NULL SET k.revokedAt = $now
                RETURN count(k) AS n""", Map.of("name", name, "now", OffsetDateTime.now(ZoneOffset.UTC)))
                .getFirst().get("n").asLong());
    }

    @SuppressWarnings("unchecked")
    private static StoredKey view(Map<String, Object> k) {
        return new StoredKey((String) k.get("pke_id"), (String) k.get("name"), (String) k.get("prefix"),
                (List<String>) k.get("roles"), String.valueOf(k.get("createdAt")), (String) k.get("createdBy"),
                k.get("revokedAt") == null ? null : String.valueOf(k.get("revokedAt")));
    }
}
