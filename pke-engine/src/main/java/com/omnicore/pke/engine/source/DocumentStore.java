package com.omnicore.pke.engine.source;

import java.io.InputStream;

/**
 * Stores original documents outside the graph (MASTER_SPEC §2.4). Content-addressed and immutable: the same
 * bytes always get the same reference, and stored content is never changed. Object storage can implement this
 * port later.
 */
public interface DocumentStore {

    record Stored(String ref, String sha256, long sizeBytes) {
    }

    /** Stores the bytes (a no-op if they are already stored) and returns their reference. */
    Stored put(byte[] content);

    InputStream open(String ref);

    boolean exists(String ref);
}
