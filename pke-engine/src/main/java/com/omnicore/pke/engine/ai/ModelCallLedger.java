package com.omnicore.pke.engine.ai;

import com.omnicore.pke.engine.graph.ModelCallStore;
import com.omnicore.pke.engine.graph.ModelCallStore.ModelCall;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The cost ledger: every model call (live or replayed) becomes a {@code :ModelCall} node and Micrometer metrics
 * ({@code pke.ai.calls}, {@code pke.ai.tokens}, {@code pke.ai.call.duration}).
 */
@Service
public class ModelCallLedger {

    private final ModelCallStore store;
    private final AiProperties properties;
    private final MeterRegistry meters;

    public ModelCallLedger(ModelCallStore store, AiProperties properties, ObjectProvider<MeterRegistry> meters) {
        this.store = store;
        this.properties = properties;
        this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    }

    public record CallContext(String skillId, String skillVersion, String executionId, String stepId) {
    }

    public void succeeded(CallContext context, ChatRequest request, ChatResult result, String outcome) {
        Double cost = estimate(result.model(), result.tokensIn(), result.tokensOut());
        store.record(new ModelCall(result.provider(), result.model(), context.skillId(), context.skillVersion(),
                result.tokensIn(), result.tokensOut(), cost, context.executionId(), context.stepId(),
                request.inputHash(), request.promptHash(), result.durationMs(), result.replayed(), outcome, null));
        metrics(context.skillId(), result.provider(), result.model(), outcome, result.tokensIn(), result.tokensOut(),
                result.durationMs());
    }

    public void failed(CallContext context, ChatRequest request, String provider, String error) {
        store.record(new ModelCall(provider, null, context.skillId(), context.skillVersion(), 0, 0, null,
                context.executionId(), context.stepId(), request.inputHash(), request.promptHash(), 0, false, "ERROR", error));
        metrics(context.skillId(), provider, "unknown", "ERROR", 0, 0, 0);
    }

    public List<Map<String, Object>> calls(String executionId, int limit) {
        return store.recent(executionId, limit);
    }

    /** Cost in USD from {@code pke.ai.pricing}; null when the model has no configured price. */
    public Double estimate(String model, int tokensIn, int tokensOut) {
        AiProperties.Price price = model == null ? null : properties.pricing().get(model);
        if (price == null) {
            return null;
        }
        return (tokensIn * price.inputPerMillion() + tokensOut * price.outputPerMillion()) / 1_000_000d;
    }

    private void metrics(String skill, String provider, String model, String outcome, int in, int out, long durationMs) {
        Counter.builder("pke.ai.calls").tag("skill", skill).tag("provider", provider).tag("model", model)
                .tag("outcome", outcome).register(meters).increment();
        Counter.builder("pke.ai.tokens").tag("skill", skill).tag("direction", "in").register(meters).increment(in);
        Counter.builder("pke.ai.tokens").tag("skill", skill).tag("direction", "out").register(meters).increment(out);
        Timer.builder("pke.ai.call.duration").tag("skill", skill).register(meters).record(Duration.ofMillis(durationMs));
    }
}
