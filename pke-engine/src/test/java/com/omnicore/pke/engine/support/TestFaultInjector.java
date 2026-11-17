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
    /** -1: fail every time; n >= 0: let n hits pass, fail the next one, then stop failing. */
    private final java.util.concurrent.atomic.AtomicInteger skip = new java.util.concurrent.atomic.AtomicInteger(-1);

    public void failAt(WriteStage stage) {
        skip.set(-1);
        this.failAt = stage;
    }

    /** Lets {@code stage} pass {@code times} times, then fails it once (e.g. the second batch of a migration). */
    public void failOnceAfter(WriteStage stage, int times) {
        skip.set(times);
        this.failAt = stage;
    }

    public void reset() {
        this.failAt = null;
    }

    @Override
    public void reached(WriteStage stage) {
        if (stage != failAt) {
            return;
        }
        if (skip.get() < 0) {
            throw new InjectedFailure(stage);
        }
        if (skip.getAndDecrement() == 0) {
            failAt = null;
            throw new InjectedFailure(stage);
        }
    }
}
