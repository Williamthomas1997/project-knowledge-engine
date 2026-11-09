package com.omnicore.pke.engine.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.StepView;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.support.KnowledgeIT;
import com.omnicore.pke.engine.support.TestPipelineSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The local executor: persisted step state, timeouts, retries, error classes, cancellation, resume, idempotency. */
class PipelineExecutorIT extends KnowledgeIT {

    @Autowired
    PipelineExecutor executor;

    private static Map<String, Object> input(String mode, long sleepMs) {
        // a unique value per test keeps idempotency keys apart
        return Map.of("mode", mode, "sleepMs", sleepMs, "run", UUID.randomUUID().toString());
    }

    private static StepView step(ExecutionView execution, String id) {
        return execution.steps().stream().filter(s -> s.stepId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void stepsRunInOrderAndTheirOutputsArePersisted() {
        ExecutionView done = executor.start("test-steps", input("ok", 0), "t", false, true);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.steps()).extracting(StepView::stepId).containsExactly("first", "work", "last");
        assertThat(done.steps()).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo("COMPLETED");
            assertThat(s.attempts()).isEqualTo(1);
            assertThat(s.outputJson()).isNotBlank();
        });
    }

    @Test
    void aTimedOutStepIsRetriedAsTransientThenFails() {
        ExecutionView failed = executor.start("test-steps", input("sleep", 3000), "t", false, true);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorClass()).isEqualTo("TRANSIENT");
        StepView work = step(failed, "work");
        assertThat(work.attempts()).isEqualTo(2);
        assertThat(work.error()).contains("timed out after PT1S");
        assertThat(step(failed, "last").status()).isEqualTo("PENDING");
    }

    @Test
    void aTransientFailureIsRetried() {
        ExecutionView done = executor.start("test-steps", input("transient-once", 0), "t", false, true);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(step(done, "work").attempts()).isEqualTo(2);
    }

    @Test
    void aPermanentFailureIsNotRetriedAndResumeContinuesFromTheFailedStep() {
        pipelineFaults.permanentWork = true;
        ExecutionView failed = executor.start("test-steps", input("permanent", 0), "t", false, true);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorClass()).isEqualTo("PERMANENT");
        assertThat(step(failed, "work").attempts()).isEqualTo(1);
        assertThat(TestPipelineSupport.runs(failed.id(), "first")).isEqualTo(1);

        pipelineFaults.permanentWork = false;
        ExecutionView resumed = executor.resume(failed.id(), "t", true);
        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(TestPipelineSupport.runs(failed.id(), "first")).as("completed steps are not run again").isEqualTo(1);
        assertThat(step(resumed, "work").attempts()).isEqualTo(2);
        assertThatThrownBy(() -> executor.resume(failed.id(), "t", true)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aRunningExecutionCanBeCancelled() throws Exception {
        ExecutionView started = executor.start("test-cancel", input("cancellable", 20_000), "t", false, false);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!"RUNNING".equals(step(executor.get(started.id()), "work").status()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        executor.cancel(started.id(), "t");
        ExecutionView view = executor.get(started.id());
        while (!"CANCELLED".equals(view.status()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            view = executor.get(started.id());
        }
        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(step(view, "work").status()).isEqualTo("CANCELLED");
        assertThat(step(view, "last").status()).isEqualTo("CANCELLED");
        assertThat(TestPipelineSupport.runs(view.id(), "last")).isZero();
    }

    @Test
    void theSameInputReturnsTheCompletedExecutionUnlessForced() {
        Map<String, Object> in = input("ok", 0);
        ExecutionView first = executor.start("test-steps", in, "t", false, true);
        ExecutionView again = executor.start("test-steps", in, "t", false, true);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(TestPipelineSupport.runs(first.id(), "first")).isEqualTo(1);
        ExecutionView forced = executor.start("test-steps", in, "t", true, true);
        assertThat(forced.id()).isNotEqualTo(first.id());
        assertThat(forced.idempotencyKey()).isEqualTo(first.idempotencyKey());
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingRuns() {
        assertThatThrownBy(() -> executor.start("test-steps", Map.of("mode", "explode"), "t", false, true))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(count("MATCH (e:PipelineExecution) RETURN count(e)")).isZero();
    }
}
