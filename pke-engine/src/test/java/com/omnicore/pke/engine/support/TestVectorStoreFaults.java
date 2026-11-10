package com.omnicore.pke.engine.support;

import com.omnicore.pke.engine.index.VectorStoreInterceptor;
import org.springframework.stereotype.Component;

/** Makes every vector store operation fail while {@link #down} is set (the store is "down"). */
@Component
public class TestVectorStoreFaults implements VectorStoreInterceptor {

    public volatile boolean down;

    @Override
    public void before(String operation) {
        if (down) {
            throw new IllegalStateException("simulated vector store outage (" + operation + ")");
        }
    }
}
