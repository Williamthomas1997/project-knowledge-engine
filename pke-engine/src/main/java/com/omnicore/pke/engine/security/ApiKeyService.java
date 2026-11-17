package com.omnicore.pke.engine.security;

import com.omnicore.pke.engine.graph.ApiKeyStore;
import com.omnicore.pke.engine.graph.ApiKeyStore.StoredKey;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Creates and checks API keys. A key is {@code pke_} + 43 base64url characters (256 random bits); only its SHA-256
 * is stored. A fast hash is enough because keys are random, not chosen by people (ADR-017).
 */
@Service
public class ApiKeyService {

    /** Matches a PKE key anywhere in a text, e.g. to check logs never contain one. */
    public static final Pattern KEY_PATTERN = Pattern.compile("pke_[A-Za-z0-9_-]{43}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyStore store;

    public ApiKeyService(ApiKeyStore store) {
        this.store = store;
    }

    /** A new key: {@code key} is shown once and never stored. */
    public record CreatedKey(String id, String name, List<Role> roles, String key) {
        @Override
        public String toString() {
            return "CreatedKey[id=" + id + ", name=" + name + ", roles=" + roles + "]";
        }
    }

    public CreatedKey create(String name, List<Role> roles, String createdBy) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ValidationFailedException.of("/name", "must be 1-64 lower-case letters, digits, '.', '_' or '-'");
        }
        if (roles == null || roles.isEmpty()) {
            throw ValidationFailedException.of("/roles", "at least one role is required");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String key = "pke_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id = PkeIds.uuidV7().toString();
        List<Role> sorted = roles.stream().distinct().sorted().toList();
        store.create(id, name, hash(key), key.substring(0, 8), sorted.stream().map(Role::name).toList(), createdBy);
        return new CreatedKey(id, name, sorted, key);
    }

    /** The caller for {@code key}, when it is a known, active key. */
    public Optional<Caller> authenticate(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            return Optional.empty();
        }
        return store.findActive(hash(key)).map(k -> new Caller(k.name(), effective(k.roles())));
    }

    public List<StoredKey> list() {
        return store.list();
    }

    public long revoke(String name, String actor) {
        return store.revoke(name, actor);
    }

    static Set<Role> effective(List<String> roles) {
        Set<Role> effective = EnumSet.noneOf(Role.class);
        roles.forEach(r -> effective.addAll(Role.valueOf(r).implied()));
        return effective;
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
