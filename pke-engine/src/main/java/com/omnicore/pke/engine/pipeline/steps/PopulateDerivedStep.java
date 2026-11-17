package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code populate-derived}: re-runs every relationship type's {@code extraction.query} after the code changed. */
@Component
class PopulateDerivedStep implements PipelineStep {

    private final DerivedRelationships derived;

    PopulateDerivedStep(DerivedRelationships derived) {
        this.derived = derived;
    }

    @Override
    public String name() {
        return "populate-derived";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        return Map.of("types", derived.populateAll(context.actor(), context.executionId()));
    }
}
