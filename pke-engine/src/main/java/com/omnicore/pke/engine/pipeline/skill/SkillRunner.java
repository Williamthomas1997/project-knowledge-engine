package com.omnicore.pke.engine.pipeline.skill;

import com.omnicore.pke.engine.ai.AiCallException;
import com.omnicore.pke.engine.ai.ChatPort;
import com.omnicore.pke.engine.ai.ChatRequest;
import com.omnicore.pke.engine.ai.ChatResult;
import com.omnicore.pke.engine.ai.ModelCallLedger;
import com.omnicore.pke.engine.ai.ModelCallLedger.CallContext;
import com.omnicore.pke.engine.ai.StructuredOutput;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a skill: validates the input against {@code inputSchema}, renders the instructions and the input, calls the
 * {@link ChatPort}, validates the answer against {@code outputSchema} and records the call in the cost ledger.
 * <ul>
 *   <li>No model configured → {@link SkillResult#skipped()} ({@code SKIPPED_NO_MODEL}); nothing is called.</li>
 *   <li>Invalid input or an answer that breaks the output schema → {@code PERMANENT} failure (never retried).</li>
 *   <li>Rate limits, timeouts, server errors → {@code TRANSIENT} failure (the pipeline retries).</li>
 * </ul>
 * The prompt is never logged; its hash is recorded on the ledger and on evidence.
 */
@Service
public class SkillRunner {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final SkillRegistry registry;
    private final ChatPort chat;
    private final ModelCallLedger ledger;
    private final Map<String, SkillTool> tools;

    public SkillRunner(SkillRegistry registry, ChatPort chat, ModelCallLedger ledger, List<SkillTool> tools) {
        this.registry = registry;
        this.chat = chat;
        this.ledger = ledger;
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(SkillTool::name, Function.identity()));
    }

    public boolean modelAvailable() {
        return chat.available();
    }

    public SkillResult run(String skillName, Map<String, Object> input, String executionId, String stepId) {
        SkillDefinition skill = registry.active(skillName);
        if (!chat.available()) {
            return SkillResult.skippedNoModel(skill.name(), skill.version());
        }
        JsonNode inputTree = JSON.valueToTree(input);
        List<Violation> inputViolations = skill.compiledInput().validate(inputTree);
        if (!inputViolations.isEmpty()) {
            throw new StepFailure(ErrorClass.PERMANENT, "Input for skill " + skill.ref() + " is invalid: " + inputViolations);
        }
        ChatRequest request = new ChatRequest(skill.name(), skill.version(), system(skill), user(input),
                CanonicalJson.write(JSON.convertValue(skill.outputSchema(), Map.class)), CanonicalJson.sha256(input), input);
        CallContext context = new CallContext(skill.name(), skill.version(), executionId, stepId);
        ChatResult result;
        try {
            result = chat.call(request);
        } catch (AiCallException e) {
            ledger.failed(context, request, chat.describe(), e.getMessage());
            throw new StepFailure(e.isTransient() ? ErrorClass.TRANSIENT : ErrorClass.PERMANENT,
                    "Skill " + skill.ref() + ": " + e.getMessage(), e);
        }
        Map<String, Object> output;
        try {
            JsonNode tree = StructuredOutput.of(skill.outputSchema(), JsonNode.class).parse(result.content());
            output = JSON.convertValue(tree, Map.class);
        } catch (StructuredOutput.StructuredOutputException e) {
            ledger.succeeded(context, request, result, "INVALID_OUTPUT");
            throw new StepFailure(ErrorClass.PERMANENT, "Skill " + skill.ref() + " answered outside its output schema: "
                    + e.violations(), e);
        }
        ledger.succeeded(context, request, result, "OK");
        return new SkillResult(false, output, new SkillResult.Provenance(skill.name(), skill.version(),
                result.provider(), result.model(), request.promptHash(), request.inputHash(), result.replayed()));
    }

    /** Calls a tool on behalf of a skill; tools the skill did not declare are refused ({@code POLICY}). */
    public Map<String, Object> tool(String skillName, String toolName, Map<String, Object> arguments) {
        SkillDefinition skill = registry.active(skillName);
        if (!skill.tools().contains(toolName)) {
            throw new StepFailure(ErrorClass.POLICY, "Skill " + skill.ref() + " did not declare tool '" + toolName
                    + "' (declared: " + skill.tools() + ")");
        }
        SkillTool tool = tools.get(toolName);
        if (tool == null) {
            throw new StepFailure(ErrorClass.POLICY, "Tool '" + toolName + "' does not exist");
        }
        return tool.call(arguments);
    }

    static String system(SkillDefinition skill) {
        return skill.instructions() + "\n\n## Answer format\n\nAnswer with exactly one JSON object that satisfies this "
                + "JSON Schema. Do not add prose or Markdown around it.\n\n```json\n"
                + PRETTY.writeValueAsString(skill.outputSchema()) + "\n```\n";
    }

    static String user(Map<String, Object> input) {
        return "Input:\n\n```json\n" + PRETTY.writeValueAsString(JSON.readTree(CanonicalJson.write(input))) + "\n```\n";
    }
}
