package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.HistoryStore.SnapshotView;
import com.omnicore.pke.engine.history.SnapshotService;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code snapshot}: records a snapshot when the input's {@code tag} is one of the step's {@code tags} (default
 * {@code uat}, {@code pilot}) or the input asks for one ({@code snapshot: true}). The label is the tag, or the
 * pipeline id.
 */
@Component
class SnapshotStep implements PipelineStep {

    private final SnapshotService snapshots;

    SnapshotStep(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public String name() {
        return "snapshot";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        String tag = Steps.string(context.input(), "tag");
        List<String> tags = context.parameters().containsKey("tags")
                ? Steps.strings(context.parameters().get("tags")) : List.of("uat", "pilot");
        boolean requested = Boolean.TRUE.equals(context.input().get("snapshot"));
        if (!requested && (tag == null || !tags.contains(tag))) {
            return Map.of("created", false);
        }
        // a retried or resumed step returns the snapshot it already made
        Map<String, Object> made = context.memo("snapshot", () -> {
            SnapshotView s = snapshots.create(tag != null ? tag : context.pipelineId(), context.actor());
            return Map.of("snapshotId", s.id(), "seq", s.seq());
        });
        return Map.of("created", true, "snapshotId", made.get("snapshotId"), "seq", made.get("seq"));
    }
}
