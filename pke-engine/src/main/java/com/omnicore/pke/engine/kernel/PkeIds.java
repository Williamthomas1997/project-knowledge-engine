package com.omnicore.pke.engine.kernel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Stable identifiers (ADR-002). Logical ids are UUIDv5 over a namespace and a canonical name, so the same
 * natural key always yields the same id, across runs and machines. Ids without a natural key are UUIDv7.
 */
public final class PkeIds {

    public static final UUID ENTITY_NAMESPACE = UUID.fromString("6f1d3c3e-4c1b-5a43-9d55-0c2d7e1f8a01");
    public static final UUID RELATIONSHIP_NAMESPACE = UUID.fromString("6f1d3c3e-4c1b-5a43-9d55-0c2d7e1f8a02");
    public static final UUID EVIDENCE_NAMESPACE = UUID.fromString("6f1d3c3e-4c1b-5a43-9d55-0c2d7e1f8a03");
    public static final UUID VERSION_NAMESPACE = UUID.fromString("6f1d3c3e-4c1b-5a43-9d55-0c2d7e1f8a04");

    private static final char SEPARATOR = '\u001F';
    private static final SecureRandom RANDOM = new SecureRandom();

    private PkeIds() {
    }

    /** Entity id from its type and the values of its natural key, in the order the schema declares them. */
    public static String entityId(String type, List<?> naturalKeyValues) {
        return uuidV5(ENTITY_NAMESPACE, join(type, naturalKeyValues)).toString();
    }

    /** Logical relationship (assertion) id. The discriminator values separate parallel relationships, if any. */
    public static String relationshipId(String type, String subjectId, String objectId, List<?> discriminatorValues) {
        return uuidV5(RELATIONSHIP_NAMESPACE,
                join(type, List.of(subjectId, objectId, discriminatorValues))).toString();
    }

    public static String evidenceId(String subjectId, List<?> evidenceKey) {
        return uuidV5(EVIDENCE_NAMESPACE, join(subjectId, evidenceKey)).toString();
    }

    public static String versionId(String subjectId, long version) {
        return uuidV5(VERSION_NAMESPACE, subjectId + SEPARATOR + version).toString();
    }

    private static String join(String head, List<?> values) {
        StringBuilder name = new StringBuilder(head);
        for (Object value : values) {
            name.append(SEPARATOR).append(CanonicalJson.write(value));
        }
        return name.toString();
    }

    /** RFC 9562 UUIDv5 (SHA-1, name-based). */
    public static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16);
            ns.putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits());
            sha1.update(ns.array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    /** RFC 9562 UUIDv7: 48-bit Unix milliseconds followed by random bits, so ids sort by creation time. */
    public static UUID uuidV7() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0fffL);
        long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
