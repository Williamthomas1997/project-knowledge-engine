package com.omnicore.pke.engine.index;

/** Test hook: called before every vector store operation; tests throw from it to simulate the store being down. */
public interface VectorStoreInterceptor {

    void before(String operation);
}
