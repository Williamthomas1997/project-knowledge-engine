package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.ItemOutcome;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.ScopeRetraction;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code discover-shared-logic}: derives {@code SHARES_LOGIC_WITH} (ADR-007 §6) between APIs whose call trees reach
 * the same non-trivial method, with the shared methods as evidence. Classes matching
 * {@code pke.discovery.shared-logic.exclude} (utilities, configuration, constants, logging, DTOs, mappers, generated
 * code) never count. The whole derivation is one evidence scope, so pairs that no longer share logic are invalidated
 * (history kept), and an unchanged graph writes nothing.
 */
@Component
class SharedLogicStep implements PipelineStep {

    static final String SOURCE = "derived:shared-logic";
    static final String METHOD = "discover.shared-logic";

    private final CodeQueryService code;
    private final GraphStore store;
    private final String exclude;

    SharedLogicStep(CodeQueryService code, GraphStore store,
                    @Value("${pke.discovery.shared-logic.exclude:(?i).*(util|config|constant|logging|logger|dto|mapper|properties|generated).*}")
                    String exclude) {
        this.code = code;
        this.store = store;
        this.exclude = exclude;
    }

    @Override
    public String name() {
        return "discover-shared-logic";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        List<RelationshipWrite> writes = new ArrayList<>();
        for (Map<String, Object> row : code.sharedLogic(exclude)) {
            List<String> shared = Steps.strings(row.get("shared"));
            String hash = CanonicalJson.sha256(shared);
            writes.add(new RelationshipWrite("SHARES_LOGIC_WITH", EntityRef.id((String) row.get("a")),
                    EntityRef.id((String) row.get("b")), Map.of("sharedMethods", shared, "sharedCount", shared.size()),
                    EpistemicStatus.EXTRACTED_DETERMINISTIC, null,
                    List.of(EvidenceInput.scoped(SOURCE, hash, SOURCE, "call-graph", METHOD,
                            Steps.truncate(String.join(", ", shared), 2000)))));
        }
        ChangeResult result = store.apply(new ChangeSet(context.executionId(), List.of(), writes, List.of(),
                List.of(new ScopeRetraction(SOURCE, SOURCE, METHOD, "the APIs no longer share non-trivial logic")), true),
                context.actor());
        Map<String, Object> outcomes = new TreeMap<>();
        for (ItemOutcome item : result.items()) {
            outcomes.merge(item.outcome().name(), 1L, (x, y) -> (Long) x + (Long) y);
        }
        return Map.of("pairs", writes.size(), "outcomes", outcomes);
    }
}
