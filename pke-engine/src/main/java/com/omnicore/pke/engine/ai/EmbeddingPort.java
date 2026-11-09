package com.omnicore.pke.engine.ai;

import java.util.List;

/**
 * Provider-neutral embeddings. Wired in M3 so availability is reported; vector collections use it from M4
 * (ADR-007 §2). Re-embedding never creates knowledge events.
 */
public interface EmbeddingPort {

    boolean available();

    String describe();

    List<float[]> embed(List<String> texts);
}
