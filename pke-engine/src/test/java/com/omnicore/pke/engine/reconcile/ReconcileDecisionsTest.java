package com.omnicore.pke.engine.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.reconcile.ReconcileDecisions.Decision;
import com.omnicore.pke.engine.reconcile.ReconcileDecisions.Resolved;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconcileDecisionsTest {

    private static Map<String, Object> d(String decision, String candidate, String target) {
        Map<String, Object> m = new HashMap<>();
        m.put("decision", decision);
        m.put("candidate", candidate);
        m.put("target", target);
        m.put("reason", "r");
        if ("CONFLICT".equals(decision)) {
            m.put("question", "which one?");
        }
        return m;
    }

    private static Map<String, Object> withoutQuestion(Map<String, Object> decision) {
        decision.remove("question");
        return decision;
    }

    @Test
    void validDecisionsAreMappedBackToIndexes() {
        List<Resolved> resolved = ReconcileDecisions.resolve(3, 2, List.of(d("UPDATE", "C1", "E1"), d("ADD", "C2", null),
                d("CONFLICT", "C3", "E2"), d("INVALIDATE", null, "E2")));
        assertThat(resolved).extracting(Resolved::decision)
                .containsExactly(Decision.UPDATE, Decision.ADD, Decision.CONFLICT, Decision.INVALIDATE);
        assertThat(resolved.getFirst().candidate()).isZero();
        assertThat(resolved.getFirst().target()).isZero();
        assertThat(resolved.get(3).target()).isEqualTo(1);
    }

    @Test
    void theContractIsEnforced() {
        assertThatThrownBy(() -> ReconcileDecisions.resolve(2, 1, List.of(
                d("ADD", "C1", "E1"),          // ADD with a target
                d("UPDATE", "C9", "E1"),       // unknown candidate id (invented)
                d("NOOP", "C1", null),         // C1 decided twice
                d("INVALIDATE", "C2", "E1"),   // INVALIDATE with a candidate; E1 changed twice
                withoutQuestion(d("CONFLICT", "C2", "E1")),
                d("SPLIT", "C2", null))))      // unknown decision
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::message)
                        .anyMatch(m -> m.contains("must be empty for ADD"))
                        .anyMatch(m -> m.contains("'C9' is not one of the given ids"))
                        .anyMatch(m -> m.contains("C1 has more than one decision"))
                        .anyMatch(m -> m.contains("must be empty for INVALIDATE"))
                        .anyMatch(m -> m.contains("is required for CONFLICT"))
                        .anyMatch(m -> m.contains("unknown decision SPLIT")));
    }

    @Test
    void everyCandidateNeedsADecision() {
        assertThatThrownBy(() -> ReconcileDecisions.resolve(2, 1, List.of(d("ADD", "C1", null))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::message).containsExactly("C2 has no decision"));
    }
}
