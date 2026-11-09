package com.omnicore.pke.engine.reconcile;

import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the {@code reconcile-knowledge} answer against its contract and maps the short references back.
 * As in Mem0, the model only sees short ids ({@code C1..} for candidates, {@code E1..} for existing cards) and may
 * only use ids it was given. Rules:
 * <ul>
 *   <li>every candidate gets exactly one of ADD, UPDATE, NOOP or CONFLICT;</li>
 *   <li>UPDATE and CONFLICT name a target; INVALIDATE names a target and no candidate; ADD names no target;</li>
 *   <li>an existing card is updated or invalidated at most once.</li>
 * </ul>
 * A broken contract is a PERMANENT failure (like an answer that breaks the output schema): nothing is written.
 */
public final class ReconcileDecisions {

    public enum Decision { ADD, UPDATE, NOOP, INVALIDATE, CONFLICT }

    /**
     * @param candidate index into the candidates, or null
     * @param target    index into the existing cards, or null
     */
    public record Resolved(Decision decision, Integer candidate, Integer target, String reason, String question) {
    }

    private ReconcileDecisions() {
    }

    public static String candidateRef(int index) {
        return "C" + (index + 1);
    }

    public static String existingRef(int index) {
        return "E" + (index + 1);
    }

    public static List<Resolved> resolve(int candidates, int existing, List<Map<String, Object>> raw) {
        List<Violation> violations = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        Set<Integer> decidedCandidates = new HashSet<>();
        Set<Integer> changedTargets = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> d = raw.get(i);
            String path = "/decisions/" + i;
            Decision decision;
            try {
                decision = Decision.valueOf(String.valueOf(d.get("decision")));
            } catch (IllegalArgumentException e) {
                violations.add(new Violation(path + "/decision", "unknown decision " + d.get("decision")));
                continue;
            }
            Integer candidate = ref(d.get("candidate"), "C", candidates, path + "/candidate", violations);
            Integer target = ref(d.get("target"), "E", existing, path + "/target", violations);
            switch (decision) {
                case ADD -> {
                    require(candidate, d.get("candidate"), path + "/candidate", violations);
                    forbid(d.get("target"), path + "/target", "ADD", violations);
                }
                case UPDATE, CONFLICT -> {
                    require(candidate, d.get("candidate"), path + "/candidate", violations);
                    require(target, d.get("target"), path + "/target", violations);
                }
                case NOOP -> require(candidate, d.get("candidate"), path + "/candidate", violations);
                case INVALIDATE -> {
                    require(target, d.get("target"), path + "/target", violations);
                    forbid(d.get("candidate"), path + "/candidate", "INVALIDATE", violations);
                }
            }
            if (candidate != null && decision != Decision.INVALIDATE && !decidedCandidates.add(candidate)) {
                violations.add(new Violation(path + "/candidate", candidateRef(candidate) + " has more than one decision"));
            }
            if (target != null && (decision == Decision.UPDATE || decision == Decision.INVALIDATE)
                    && !changedTargets.add(target)) {
                violations.add(new Violation(path + "/target", existingRef(target) + " is changed more than once"));
            }
            String question = d.get("question") == null ? null : String.valueOf(d.get("question"));
            if (decision == Decision.CONFLICT && (question == null || question.isBlank())) {
                violations.add(new Violation(path + "/question", "is required for CONFLICT"));
            }
            resolved.add(new Resolved(decision, candidate, target, String.valueOf(d.getOrDefault("reason", "")), question));
        }
        for (int c = 0; c < candidates; c++) {
            if (!decidedCandidates.contains(c)) {
                violations.add(new Violation("/decisions", candidateRef(c) + " has no decision"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("The reconcile-knowledge answer breaks its contract", violations);
        }
        return resolved;
    }

    private static Integer ref(Object value, String prefix, int count, String path, List<Violation> violations) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String ref = String.valueOf(value);
        if (ref.startsWith(prefix)) {
            try {
                int index = Integer.parseInt(ref.substring(1)) - 1;
                if (index >= 0 && index < count) {
                    return index;
                }
            } catch (NumberFormatException e) {
                // reported below
            }
        }
        violations.add(new Violation(path, "'" + ref + "' is not one of the given ids"));
        return null;
    }

    private static void require(Integer resolved, Object raw, String path, List<Violation> violations) {
        if (resolved == null && (raw == null || String.valueOf(raw).isBlank())) {
            violations.add(new Violation(path, "is required"));
        }
    }

    private static void forbid(Object raw, String path, String decision, List<Violation> violations) {
        if (raw != null && !String.valueOf(raw).isBlank()) {
            violations.add(new Violation(path, "must be empty for " + decision));
        }
    }
}
