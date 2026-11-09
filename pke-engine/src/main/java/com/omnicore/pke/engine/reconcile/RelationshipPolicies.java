package com.omnicore.pke.engine.reconcile;

import com.omnicore.pke.engine.ai.JsonSchemas;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.schema.RelationshipTypeDefinition;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Decides what happens to a proposed relationship ({@code definitions/policies/relationships.yaml}, ADR-013):
 * written automatically, written as inferred, asked as a question, or rejected. Rules first; without a matching
 * rule the relationship type's schema policy ({@code discovery}, {@code approval}) decides.
 */
@Service
public class RelationshipPolicies {

    public enum Origin { DETERMINISTIC, AI, MANUAL }

    public enum Decision { AUTO_ACCEPT, ACCEPT_AS_INFERRED, QUESTION, REJECT }

    public record Verdict(Decision decision, String reason) {
    }

    record Rule(String relationship, Origin origin, Decision decision, boolean evidenceRequired, Double minConfidence) {
    }

    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    private final SchemaRegistry schema;
    private final List<Rule> rules;
    private final String version;

    public RelationshipPolicies(SchemaRegistry schema,
                                @Value("${pke.policies.relationships:classpath:definitions/policies/relationships.yaml}")
                                String location) {
        this.schema = schema;
        JsonNode tree = read(location);
        List<Violation> violations = JsonSchemas.classpath("definitions/meta/relationship-policies.schema.json").validate(tree);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid relationship policies " + location, violations);
        }
        List<Rule> parsed = new ArrayList<>();
        tree.get("rules").forEach(r -> parsed.add(new Rule(r.get("relationship").asString(),
                Origin.valueOf(r.get("origin").asString().toUpperCase()), Decision.valueOf(r.get("decision").asString()),
                r.path("evidenceRequired").asBoolean(false),
                r.has("minConfidence") ? r.get("minConfidence").asDouble() : null)));
        this.rules = List.copyOf(parsed);
        this.version = tree.get("version").asString();
    }

    public String version() {
        return version;
    }

    /**
     * @param confidence model-reported confidence, or null
     */
    public Verdict decide(String relationshipType, Origin origin, int evidenceCount, Double confidence) {
        Optional<Rule> rule = rules.stream()
                .filter(r -> r.origin() == origin && (r.relationship().equals("*") || r.relationship().equals(relationshipType)))
                .findFirst();
        if (rule.isPresent()) {
            Rule r = rule.get();
            if (r.evidenceRequired() && evidenceCount == 0) {
                return new Verdict(Decision.REJECT, "policy requires evidence");
            }
            if (r.minConfidence() != null && (confidence == null || confidence < r.minConfidence())) {
                return new Verdict(Decision.REJECT, "confidence " + confidence + " is below the policy minimum " + r.minConfidence());
            }
            return new Verdict(r.decision(), "rule " + r.relationship() + "/" + origin.name().toLowerCase());
        }
        RelationshipTypeDefinition definition = schema.relationshipType(relationshipType).orElse(null);
        if (definition == null) {
            return new Verdict(Decision.REJECT, "unknown relationship type " + relationshipType);
        }
        if (!definition.discovery().isEmpty() && !definition.discovery().contains(origin.name().toLowerCase())) {
            return new Verdict(Decision.REJECT, relationshipType + " may not be discovered by " + origin.name().toLowerCase());
        }
        Decision decision = switch (definition.approval() == null ? "auto" : definition.approval()) {
            case "inferred" -> Decision.ACCEPT_AS_INFERRED;
            case "review" -> Decision.QUESTION;
            default -> Decision.AUTO_ACCEPT;
        };
        return new Verdict(decision, "schema policy of " + relationshipType);
    }

    private static JsonNode read(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return YAML.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read relationship policies " + location, e);
        }
    }
}
