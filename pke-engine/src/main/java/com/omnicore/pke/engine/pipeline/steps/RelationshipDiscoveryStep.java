package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.graph.EvolutionRequestStore;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.pipeline.LocalPipelineExecutor;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.engine.retrieval.CypherGuard;
import com.omnicore.pke.engine.retrieval.GuardedCypher;
import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import com.omnicore.pke.engine.schema.SchemaService;
import com.omnicore.pke.engine.schema.SchemaService.DefinitionView;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code relationship-discovery}: the {@code discover-relationships} skill proposes new relationship types from the
 * schema and the graph's current relationship patterns. A proposal with a population query that passes the Cypher
 * guard is registered as Level-1 metadata (schema promotion: validate, compatibility, activate, event) and populated
 * at once; anything else becomes an evolution request (M7). A business-category type is never populated by a query
 * that traverses only technical relationships (MASTER_SPEC §7: shared utilities prove nothing).
 */
@Component
class RelationshipDiscoveryStep implements PipelineStep {

    static final String SKILL = "discover-relationships";
    private static final Pattern REL_TYPES = Pattern.compile("\\[\\s*\\w*\\s*:\\s*([A-Z_][A-Z0-9_|]*)");

    private final SkillRunner skills;
    private final SchemaService schema;
    private final SchemaRegistry registry;
    private final CodeQueryService code;
    private final GuardedCypher cypher;
    private final DerivedRelationships derived;
    private final EvolutionRequestStore evolution;

    RelationshipDiscoveryStep(SkillRunner skills, SchemaService schema, SchemaRegistry registry, CodeQueryService code,
                              GuardedCypher cypher, DerivedRelationships derived, EvolutionRequestStore evolution) {
        this.skills = skills;
        this.schema = schema;
        this.registry = registry;
        this.code = code;
        this.cypher = cypher;
        this.derived = derived;
        this.evolution = evolution;
    }

    @Override
    public String name() {
        return "relationship-discovery";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        Map<String, Object> input = input(Steps.string(context.input(), "focus"));
        Map<String, Object> answer = context.memo("skill:" + CanonicalJson.sha256(input), () -> {
            SkillResult result = skills.run(SKILL, input, context.executionId(), context.stepId());
            return result.skipped() ? Map.of("skipped", true)
                    : Map.of("output", result.output(), "provenance", result.provenance().toMap());
        });
        if (Boolean.TRUE.equals(answer.get("skipped"))) {
            return Map.of("_status", LocalPipelineExecutor.SKIPPED_NO_MODEL);
        }
        String proposedBy = SKILL + "@" + Steps.map(answer.get("provenance")).get("skillVersion");
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Map<String, Object> proposal : Steps.maps(Steps.map(answer.get("output")).get("proposals"))) {
            outcomes.add(handle(proposal, proposedBy, context));
        }
        return Map.of("proposals", outcomes);
    }

    private Map<String, Object> input(String focus) {
        List<Map<String, Object>> relationshipTypes = new ArrayList<>();
        for (DefinitionView view : schema.list(DefinitionKind.RELATIONSHIP_TYPE)) {
            registry.relationshipType(view.name()).ifPresent(type -> relationshipTypes.add(Map.of("name", type.name(),
                    "category", type.category(), "sourceTypes", type.sourceTypes(), "targetTypes", type.targetTypes())));
        }
        relationshipTypes.sort(Comparator.comparing(m -> (String) m.get("name")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("entityTypes", schema.list(DefinitionKind.ENTITY_TYPE).stream().map(DefinitionView::name).sorted().toList());
        input.put("relationshipTypes", relationshipTypes);
        input.put("patterns", code.relationshipPatterns());
        if (focus != null) {
            input.put("focus", focus);
        }
        return input;
    }

    private Map<String, Object> handle(Map<String, Object> proposal, String proposedBy, StepContext context) {
        String name = Steps.string(proposal, "name");
        Map<String, Object> population = Steps.map(proposal.get("population"));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("name", name);
        if (registry.relationshipType(name).isPresent()) {
            outcome.put("outcome", "EXISTS");
            return outcome;
        }
        String query = Steps.string(population, "query");
        String refusal = null;
        if (!"query".equals(population.get("method")) || query == null) {
            refusal = "no existing capability can populate it: " + Steps.string(population, "reason");
        } else {
            CypherGuard.Result guard = cypher.check(query);
            if (!guard.allowed()) {
                refusal = "population query rejected by the Cypher guard: " + guard.violations();
            } else if ("business".equals(proposal.get("category")) && !traversesBusiness(query)) {
                refusal = "a business relationship cannot be derived from technical relationships alone (MASTER_SPEC §7)";
            }
        }
        if (refusal == null) {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("kind", "RelationshipType");
            definition.put("name", name);
            definition.put("version", "1.0.0");
            definition.put("description", proposal.get("description"));
            definition.put("category", proposal.get("category"));
            definition.put("sourceTypes", proposal.get("sourceTypes"));
            definition.put("targetTypes", proposal.get("targetTypes"));
            definition.put("evidence", Map.of("required", true));
            definition.put("policy", Map.of("discovery", List.of("deterministic"), "approval", "auto"));
            definition.put("extraction", Map.of("query", query, "proposedBy", proposedBy,
                    "rationale", String.valueOf(proposal.get("rationale"))));
            try {
                var registered = schema.register(CanonicalJson.write(definition), Format.JSON, context.actor(),
                        DefinitionKind.RELATIONSHIP_TYPE);
                outcome.put("outcome", "REGISTERED");
                outcome.put("registeredSeq", registered.seq());
                outcome.put("population", derived.populateOrReport(name, query, context.actor(), context.executionId()));
                return outcome;
            } catch (ValidationFailedException e) {
                refusal = "the definition is invalid: " + e.violations();
            }
        }
        outcome.put("outcome", "EVOLUTION_REQUESTED");
        outcome.put("reason", refusal);
        outcome.put("evolutionRequestId", evolution.request("NEW_RELATIONSHIP_TYPE", String.valueOf(name), refusal,
                proposal, context.actor(), context.executionId()));
        return outcome;
    }

    /** True when the query traverses at least one business-category relationship type. */
    private boolean traversesBusiness(String query) {
        Matcher m = REL_TYPES.matcher(query);
        while (m.find()) {
            for (String type : m.group(1).split("\\|")) {
                if (registry.relationshipType(type).map(d -> "business".equals(d.category())).orElse(false)) {
                    return true;
                }
            }
        }
        return false;
    }
}
