package com.omnicore.pke.engine.security;

import com.omnicore.pke.engine.ai.JsonSchemas;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The execution policy of this deployment profile ({@code definitions/policies/execution.yaml}): which pipelines an
 * MCP caller may start and which evolution requests it may submit. Checked after the role check.
 */
@Service
public class ExecutionPolicies {

    private final String profile;
    private final List<String> pipelines;
    private final List<String> evolutionKinds;

    public ExecutionPolicies(@Value("${pke.security.policy-profile:dev}") String profile,
                             @Value("${pke.policies.execution:classpath:definitions/policies/execution.yaml}") String location) {
        JsonNode tree = read(location);
        List<Violation> violations = JsonSchemas.classpath("definitions/meta/execution-policy.schema.json").validate(tree);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid execution policy " + location, violations);
        }
        JsonNode selected = tree.get("profiles").get(profile);
        if (selected == null) {
            throw ValidationFailedException.of("/profiles/" + profile, "no execution policy for profile " + profile);
        }
        this.profile = profile;
        this.pipelines = strings(selected.get("triggerIngestion").get("pipelines"));
        this.evolutionKinds = strings(selected.get("evolutionRequests").get("kinds"));
    }

    public String profile() {
        return profile;
    }

    public void checkPipeline(String pipelineId) {
        if (!allows(pipelines, pipelineId)) {
            throw new AccessDeniedException("The " + profile + " execution policy does not allow starting pipeline '"
                    + pipelineId + "' through MCP; allowed: " + pipelines);
        }
    }

    public void checkEvolutionRequest(String kind) {
        if (!allows(evolutionKinds, kind)) {
            throw new AccessDeniedException("The " + profile + " execution policy does not accept evolution requests of"
                    + " kind '" + kind + "'; allowed: " + evolutionKinds);
        }
    }

    private static boolean allows(List<String> allowed, String value) {
        return allowed.contains("*") || allowed.contains(value);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return List.copyOf(values);
    }

    private static JsonNode read(String location) {
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            return YAMLMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read execution policy " + location, e);
        }
    }
}
