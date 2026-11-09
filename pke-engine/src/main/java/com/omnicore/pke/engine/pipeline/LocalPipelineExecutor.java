package com.omnicore.pke.engine.pipeline;

import com.omnicore.pke.engine.ai.AiCallException;
import com.omnicore.pke.engine.graph.PipelineExecutionStore;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.graph.PipelineExecutionStore.StepView;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-process {@link PipelineExecutor}: executions run one at a time on a worker thread; each step attempt runs on
 * its own thread so its timeout can be enforced.
 * <ul>
 *   <li>Step outputs are persisted, so resume never repeats a completed step; {@link StepContext#memo} persists
 *       work inside a step (one model call per item).</li>
 *   <li>TRANSIENT failures are retried up to {@code retry.maxAttempts} with {@code retry.backoff}; PERMANENT and
 *       POLICY failures stop the execution.</li>
 *   <li>A step whose output has {@code _status = SKIPPED_NO_MODEL} (no model configured) skips the rest of the
 *       pipeline: the execution ends {@code PARTIAL}, and resuming it later reruns from that step.</li>
 *   <li>A timed-out attempt is interrupted, but a step blocked in I/O may finish later; steps are written to be
 *       idempotent (the graph writer is), so a retry after a timeout is safe.</li>
 * </ul>
 */
@Service
public class LocalPipelineExecutor implements PipelineExecutor {

    public static final String SKIPPED_NO_MODEL = "SKIPPED_NO_MODEL";
    private static final Logger log = LoggerFactory.getLogger(LocalPipelineExecutor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FINISHED_STEP = Set.of("COMPLETED");

    private final PipelineRegistry registry;
    private final PipelineExecutionStore store;
    private final Map<String, PipelineStep> steps;
    private final SkillRunner skills;
    private final SourceVersionResolver sourceVersions;
    private final List<PipelineFaultInjector> faults;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "pke-pipeline"));
    private final ExecutorService stepThreads = Executors.newCachedThreadPool(r -> daemon(r, "pke-pipeline-step"));
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();

    public LocalPipelineExecutor(PipelineRegistry registry, PipelineExecutionStore store, List<PipelineStep> steps,
                                 SkillRunner skills, SourceVersionResolver sourceVersions,
                                 ObjectProvider<PipelineFaultInjector> faults) {
        this.registry = registry;
        this.store = store;
        this.steps = steps.stream().collect(Collectors.toUnmodifiableMap(PipelineStep::name, Function.identity()));
        this.skills = skills;
        this.sourceVersions = sourceVersions;
        this.faults = faults.orderedStream().toList();
    }

    @Override
    public ExecutionView start(String pipelineId, Map<String, Object> input, String actor, boolean force, boolean wait) {
        PipelineDefinition definition = registry.active(pipelineId);
        Map<String, Object> in = input == null ? Map.of() : input;
        if (definition.input() != null) {
            List<Violation> violations = definition.input().validate(JSON.valueToTree(in));
            if (!violations.isEmpty()) {
                throw new ValidationFailedException("Invalid input for pipeline " + definition.ref(), violations);
            }
        }
        String key = idempotencyKey(definition, in);
        if (!force) {
            Optional<String> existing = store.findByKey(key, List.of("COMPLETED", "PENDING", "RUNNING"));
            if (existing.isPresent()) {
                return store.get(existing.get());
            }
        }
        List<PipelineExecutionStore.NewStep> newSteps = new ArrayList<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            newSteps.add(new PipelineExecutionStore.NewStep(definition.steps().get(i).id(), definition.steps().get(i).uses(), i));
        }
        String id = store.create(definition.id(), definition.version(), CanonicalJson.write(in), key, actor, newSteps);
        return dispatch(id, wait);
    }

    @Override
    public ExecutionView resume(String executionId, String actor, boolean wait) {
        ExecutionView execution = store.get(executionId);
        if (!Set.of("FAILED", "CANCELLED", "PARTIAL").contains(execution.status())) {
            throw new ConflictException("Execution " + executionId + " is " + execution.status()
                    + "; only FAILED, CANCELLED or PARTIAL executions can be resumed");
        }
        cancelled.remove(executionId);
        store.updateExecution(executionId, fields("status", "PENDING", "cancelRequested", false, "resumedBy", actor));
        return dispatch(executionId, wait);
    }

    @Override
    public ExecutionView cancel(String executionId, String actor) {
        ExecutionView execution = store.get(executionId);
        switch (execution.status()) {
            case "PENDING", "FAILED", "PARTIAL" -> store.updateExecution(executionId, fields("status", "CANCELLED",
                    "cancelRequested", true, "cancelledBy", actor, "finishedAt", now()));
            case "RUNNING" -> {
                cancelled.put(executionId, true);
                store.updateExecution(executionId, fields("cancelRequested", true, "cancelledBy", actor));
            }
            default -> throw new ConflictException("Execution " + executionId + " is " + execution.status()
                    + " and cannot be cancelled");
        }
        return store.get(executionId);
    }

    @Override
    public ExecutionView get(String executionId) {
        return store.get(executionId);
    }

    @Override
    public List<ExecutionView> recent(String pipelineId, String status, int limit) {
        return store.recent(pipelineId, status, limit);
    }

    /** Executions interrupted by a restart become FAILED (TRANSIENT) so they can be resumed. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (String status : List.of("RUNNING", "PENDING")) {
            store.recent(null, status, 200).forEach(e -> store.updateExecution(e.id(), fields("status", "FAILED",
                    "errorClass", ErrorClass.TRANSIENT.name(), "error", "interrupted by an application restart",
                    "finishedAt", now())));
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        stepThreads.shutdownNow();
    }

    private ExecutionView dispatch(String id, boolean wait) {
        Future<?> future = worker.submit(() -> run(id));
        if (wait) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Pipeline execution {} crashed", id, e.getCause());
            }
        }
        return store.get(id);
    }

    // ------------------------------------------------------------------------------------------------ running

    private void run(String executionId) {
        ExecutionView execution = store.get(executionId);
        if (!"PENDING".equals(execution.status())) {
            return;
        }
        PipelineDefinition definition = registry.version(execution.pipelineId(), execution.pipelineVersion());
        @SuppressWarnings("unchecked")
        Map<String, Object> input = JSON.readValue(execution.inputJson(), Map.class);
        store.updateExecution(executionId, fields("status", "RUNNING", "startedAt", now(), "finishedAt", null,
                "error", null, "errorClass", null));
        Map<String, StepView> views = execution.steps().stream()
                .collect(Collectors.toMap(StepView::stepId, Function.identity()));
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        String skippedAt = null;
        for (PipelineDefinition.Step step : definition.steps()) {
            StepView view = views.get(step.id());
            if (FINISHED_STEP.contains(view.status())) {
                outputs.put(step.id(), parse(view.outputJson()));
                continue;
            }
            if (skippedAt != null) {
                store.updateStep(executionId, step.id(), fields("status", SKIPPED_NO_MODEL,
                        "error", "skipped because step '" + skippedAt + "' had no model", "finishedAt", now()));
                continue;
            }
            if (isCancelled(executionId)) {
                finishCancelled(executionId, definition, step.id());
                return;
            }
            StepOutcome outcome = runWithRetries(executionId, definition, step, view.attempts(), input, outputs, execution.actor());
            if (outcome.failure() != null) {
                StepFailure failure = outcome.failure();
                if (failure.errorClass() == ErrorClass.CANCELLED) {
                    finishCancelled(executionId, definition, step.id());
                } else {
                    store.updateExecution(executionId, fields("status", "FAILED", "errorClass", failure.errorClass().name(),
                            "error", "step '" + step.id() + "': " + failure.getMessage(), "finishedAt", now()));
                    log.warn("Pipeline {} execution {} failed at step {} ({}): {}", definition.ref(), executionId, step.id(),
                            failure.errorClass(), failure.getMessage());
                }
                return;
            }
            outputs.put(step.id(), outcome.output());
            if (SKIPPED_NO_MODEL.equals(outcome.output().get("_status"))) {
                skippedAt = step.id();
            }
        }
        store.updateExecution(executionId, fields("status", skippedAt == null ? "COMPLETED" : "PARTIAL",
                "error", skippedAt == null ? null : "no model configured: skipped from step '" + skippedAt + "'",
                "finishedAt", now()));
    }

    private record StepOutcome(Map<String, Object> output, StepFailure failure) {
    }

    private StepOutcome runWithRetries(String executionId, PipelineDefinition definition, PipelineDefinition.Step step,
                                       int previousAttempts, Map<String, Object> input,
                                       Map<String, Map<String, Object>> outputs, String actor) {
        int attempts = previousAttempts;
        for (int attemptThisRun = 1; ; attemptThisRun++) {
            attempts++;
            store.updateStep(executionId, step.id(), fields("status", "RUNNING", "attempts", attempts,
                    "startedAt", now(), "finishedAt", null, "error", null, "errorClass", null));
            Context context = new Context(executionId, definition, step, attempts, actor, input, outputs);
            try {
                Map<String, Object> output = runAttempt(context, step);
                String status = SKIPPED_NO_MODEL.equals(output.get("_status")) ? SKIPPED_NO_MODEL : "COMPLETED";
                store.updateStep(executionId, step.id(), fields("status", status,
                        "outputJson", JSON.writeValueAsString(output), "finishedAt", now()));
                return new StepOutcome(output, null);
            } catch (RuntimeException e) {
                StepFailure failure = classify(e);
                boolean retry = failure.errorClass() == ErrorClass.TRANSIENT && attemptThisRun < step.maxAttempts();
                store.updateStep(executionId, step.id(), fields("status", retry ? "RETRYING" : statusOf(failure),
                        "errorClass", failure.errorClass().name(), "error", failure.getMessage(), "finishedAt", now()));
                if (!retry) {
                    return new StepOutcome(null, failure);
                }
                if (!sleep(step.backoff().multipliedBy(attemptThisRun), executionId)) {
                    return new StepOutcome(null, new StepFailure(ErrorClass.CANCELLED, "cancelled"));
                }
            }
        }
    }

    private Map<String, Object> runAttempt(Context context, PipelineDefinition.Step step) {
        faults.forEach(f -> f.beforeStep(context.pipelineId(), step.id(), context.attempt()));
        Future<Map<String, Object>> future = stepThreads.submit(() -> step.isSkill()
                ? runSkillStep(context, step) : runBeanStep(context, step));
        try {
            Map<String, Object> output = future.get(step.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return output == null ? Map.of() : output;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StepFailure(ErrorClass.TRANSIENT, "timed out after " + step.timeout());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StepFailure(ErrorClass.CANCELLED, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new StepFailure(ErrorClass.PERMANENT,
                    String.valueOf(cause), cause);
        }
    }

    private Map<String, Object> runBeanStep(Context context, PipelineDefinition.Step step) {
        PipelineStep bean = steps.get(step.uses());
        if (bean == null) {
            throw new StepFailure(ErrorClass.PERMANENT, "step bean '" + step.uses() + "' is not registered");
        }
        return bean.execute(context);
    }

    /** {@code uses: skill:<name>}: runs the skill once per {@code {key, input}} item of {@code forEach}. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runSkillStep(Context context, PipelineDefinition.Step step) {
        String[] ref = step.forEach().split("\\.", 2);
        Object list = context.output(ref[0]).map(o -> o.get(ref[1])).orElse(null);
        if (!(list instanceof List<?> items)) {
            throw new StepFailure(ErrorClass.PERMANENT, "forEach " + step.forEach() + " is not a list");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", step.skillName());
        if (!items.isEmpty() && !skills.modelAvailable()) {
            result.put("_status", SKIPPED_NO_MODEL);
            result.put("results", List.of());
            return result;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : items) {
            context.checkCancelled();
            Map<String, Object> entry = (Map<String, Object>) item;
            String key = String.valueOf(entry.get("key"));
            Map<String, Object> skillInput = (Map<String, Object>) entry.get("input");
            results.add(context.memo("item:" + key + ":" + CanonicalJson.sha256(skillInput), () -> {
                SkillResult run = skills.run(step.skillName(), skillInput, context.executionId(), step.id());
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", key);
                one.put("output", run.output());
                one.put("provenance", run.provenance().toMap());
                return one;
            }));
        }
        result.put("results", results);
        return result;
    }

    private void finishCancelled(String executionId, PipelineDefinition definition, String fromStep) {
        boolean reached = false;
        for (PipelineDefinition.Step step : definition.steps()) {
            reached |= step.id().equals(fromStep);
            if (reached) {
                store.updateStep(executionId, step.id(), fields("status", "CANCELLED", "finishedAt", now()));
            }
        }
        store.updateExecution(executionId, fields("status", "CANCELLED", "finishedAt", now()));
        cancelled.remove(executionId);
    }

    private boolean isCancelled(String executionId) {
        return cancelled.containsKey(executionId) || store.get(executionId).cancelRequested();
    }

    private boolean sleep(Duration duration, String executionId) {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) {
            if (isCancelled(executionId)) {
                return false;
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    static StepFailure classify(Throwable error) {
        if (error instanceof StepFailure failure) {
            return failure;
        }
        if (error instanceof AiCallException ai) {
            return new StepFailure(ai.isTransient() ? ErrorClass.TRANSIENT : ErrorClass.PERMANENT, ai.getMessage(), ai);
        }
        if (error instanceof ValidationFailedException || error instanceof NotFoundException
                || error instanceof ConflictException) {
            return new StepFailure(ErrorClass.PERMANENT, error.getMessage(), error);
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.contains("Transient") || name.contains("ServiceUnavailable") || name.contains("SessionExpired")
                    || t instanceof java.io.IOException || t instanceof TimeoutException) {
                return new StepFailure(ErrorClass.TRANSIENT, String.valueOf(error.getMessage()), error);
            }
        }
        return new StepFailure(ErrorClass.PERMANENT, error.getClass().getSimpleName() + ": " + error.getMessage(), error);
    }

    private static String statusOf(StepFailure failure) {
        return failure.errorClass() == ErrorClass.CANCELLED ? "CANCELLED" : "FAILED";
    }

    String idempotencyKey(PipelineDefinition definition, Map<String, Object> input) {
        Map<String, Object> sources = new TreeMap<>();
        for (PipelineDefinition.SourceField field : definition.idempotencySources()) {
            Object value = input.get(field.field());
            if (value != null) {
                String key = field.prefix() + value;
                sources.put(key, sourceVersions.currentVersion(key));
            }
        }
        return CanonicalJson.sha256(Map.of("pipeline", definition.ref(), "sources", sources, "input", input));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return json == null ? Map.of() : JSON.readValue(json, Map.class);
    }

    private static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** {@link StepContext} for one attempt. */
    private final class Context implements StepContext {

        private final String executionId;
        private final PipelineDefinition definition;
        private final PipelineDefinition.Step step;
        private final int attempt;
        private final String actor;
        private final Map<String, Object> input;
        private final Map<String, Map<String, Object>> outputs;

        private Context(String executionId, PipelineDefinition definition, PipelineDefinition.Step step, int attempt,
                        String actor, Map<String, Object> input, Map<String, Map<String, Object>> outputs) {
            this.executionId = executionId;
            this.definition = definition;
            this.step = step;
            this.attempt = attempt;
            this.actor = actor;
            this.input = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
            this.outputs = Map.copyOf(outputs);
        }

        @Override
        public String executionId() {
            return executionId;
        }

        @Override
        public String pipelineId() {
            return definition.id();
        }

        @Override
        public String pipelineVersion() {
            return definition.version();
        }

        @Override
        public String stepId() {
            return step.id();
        }

        @Override
        public int attempt() {
            return attempt;
        }

        @Override
        public String actor() {
            return actor == null ? "pipeline" : actor;
        }

        @Override
        public Map<String, Object> input() {
            return input;
        }

        @Override
        public Map<String, Object> parameters() {
            return step.with();
        }

        @Override
        public Optional<Map<String, Object>> output(String stepId) {
            return Optional.ofNullable(outputs.get(stepId));
        }

        @Override
        public void checkCancelled() {
            if (Thread.currentThread().isInterrupted() || isCancelled(executionId)) {
                throw new StepFailure(ErrorClass.CANCELLED, "execution cancelled");
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> memo(String key, Supplier<Map<String, Object>> compute) {
            Optional<String> stored = store.memo(executionId, step.id(), key);
            if (stored.isPresent()) {
                return JSON.readValue(stored.get(), Map.class);
            }
            Map<String, Object> value = compute.get();
            store.putMemo(executionId, step.id(), key, JSON.writeValueAsString(value));
            return value;
        }
    }
}
