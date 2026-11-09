package com.omnicore.pke.engine.support;

import com.omnicore.pke.engine.graph.WriteFaultInjector;
import com.omnicore.pke.engine.graph.WriteStage;
import org.springframework.stereotype.Component;

/** Fails a write transaction at a chosen {@link WriteStage}. Inactive unless a test arms it. */
@Component
public class TestFaultInjector implements WriteFaultInjector {

    public static class InjectedFailure extends RuntimeException {
        InjectedFailure(WriteStage stage) {
            super("Injected failure at " + stage);
        }
    }

    private volatile WriteStage failAt;

    public void failAt(WriteStage stage) {
        this.failAt = stage;
    }

    public void reset() {
        this.failAt = null;
    }

    @Override
    public void reached(WriteStage stage) {
        if (stage == failAt) {
            throw new InjectedFailure(stage);
        }
    }
}
