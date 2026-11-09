package com.omnicore.pke.engine.support;

import com.omnicore.pke.engine.pipeline.PipelineFaultInjector;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only pipeline steps (used by {@code test-pipelines/*.yaml}) and a pipeline fault injector. Inactive unless a
 * test arms them.
 */
@Configuration(proxyBeanMethods = false)
public class TestPipelineSupport {

    /** Counts executions per step name and execution, so tests can prove a step did not run again. */
    public static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

    public static int runs(String executionId, String step) {
        AtomicInteger count = RUNS.get(executionId + "/" + step);
        return count == null ? 0 : count.get();
    }

    private static void count(StepContext context) {
        RUNS.computeIfAbsent(context.executionId() + "/" + context.stepId(), k -> new AtomicInteger()).incrementAndGet();
    }

    /** {@code test-echo}: returns the input. */
    @Bean
    PipelineStep testEcho() {
        return new PipelineStep() {
            @Override
            public String name() {
                return "test-echo";
            }

            @Override
            public Map<String, Object> execute(StepContext context) {
                count(context);
                return Map.of("echo", context.input());
            }
        };
    }

    /**
     * {@code test-work}: behaviour chosen by the input's {@code mode}: {@code ok}; {@code sleep} (blocks
     * {@code sleepMs}, ignoring cancellation, to hit the timeout); {@code cancellable} (waits, checking for
     * cancellation); {@code transient-once} (fails TRANSIENT on the first attempt); {@code permanent} (fails
     * PERMANENT while {@link PipelineFaults#permanentWork} is set).
     */
    @Bean
    PipelineStep testWork(PipelineFaults faults) {
        return new PipelineStep() {
            @Override
            public String name() {
                return "test-work";
            }

            @Override
            public Map<String, Object> execute(StepContext context) {
                count(context);
                String mode = String.valueOf(context.input().getOrDefault("mode", "ok"));
                long sleepMs = ((Number) context.input().getOrDefault("sleepMs", 0)).longValue();
                switch (mode) {
                    case "sleep" -> sleep(sleepMs);
                    case "cancellable" -> {
                        long until = System.currentTimeMillis() + sleepMs;
                        while (System.currentTimeMillis() < until) {
                            context.checkCancelled();
                            sleep(20);
                        }
                    }
                    case "transient-once" -> {
                        if (context.attempt() == 1) {
                            throw new StepFailure(StepFailure.ErrorClass.TRANSIENT, "simulated rate limit");
                        }
                    }
                    case "permanent" -> {
                        if (faults.permanentWork) {
                            throw new StepFailure(StepFailure.ErrorClass.PERMANENT, "simulated permanent failure");
                        }
                    }
                    default -> {
                    }
                }
                return Map.of("mode", mode, "attempt", context.attempt());
            }
        };
    }

    @Bean
    PipelineFaults pipelineFaults() {
        return new PipelineFaults();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Crashes a chosen step of a chosen pipeline (simulates a process failure in the middle of a pipeline). */
    public static class PipelineFaults implements PipelineFaultInjector {

        public volatile boolean permanentWork;
        private volatile String pipelineId;
        private volatile String stepId;

        public void crashAt(String pipelineId, String stepId) {
            this.pipelineId = pipelineId;
            this.stepId = stepId;
        }

        public void reset() {
            pipelineId = null;
            stepId = null;
            permanentWork = false;
        }

        @Override
        public void beforeStep(String pipelineId, String stepId, int attempt) {
            if (pipelineId.equals(this.pipelineId) && stepId.equals(this.stepId)) {
                throw new IllegalStateException("simulated crash before step " + stepId);
            }
        }
    }
}
