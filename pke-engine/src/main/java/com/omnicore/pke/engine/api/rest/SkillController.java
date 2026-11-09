package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.ai.ModelCallLedger;
import com.omnicore.pke.engine.pipeline.skill.SkillDefinition;
import com.omnicore.pke.engine.pipeline.skill.SkillRegistry;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Skills (read and try out) and the model-call cost ledger. */
@RestController
@RequestMapping("/api")
class SkillController {

    private final SkillRegistry registry;
    private final SkillRunner runner;
    private final ModelCallLedger ledger;

    SkillController(SkillRegistry registry, SkillRunner runner, ModelCallLedger ledger) {
        this.registry = registry;
        this.runner = runner;
        this.ledger = ledger;
    }

    @GetMapping("/skills")
    List<Map<String, Object>> skills() {
        return registry.activeSkills().stream().map(s -> describe(s, false)).toList();
    }

    @GetMapping("/skills/{name}")
    Map<String, Object> skill(@PathVariable String name) {
        Map<String, Object> view = describe(registry.active(name), true);
        view.put("versions", registry.versions(name).stream().map(SkillDefinition::version).toList());
        return view;
    }

    /** Runs a skill on an input and returns its validated output. Writes nothing to knowledge (ledger only). */
    @PostMapping("/skills/{name}/run")
    SkillResult run(@PathVariable String name, @RequestBody Map<String, Object> input) {
        return runner.run(name, input, null, "api");
    }

    @GetMapping("/ai/calls")
    List<Map<String, Object>> calls(@RequestParam(required = false) String executionId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return ledger.calls(executionId, limit);
    }

    private static Map<String, Object> describe(SkillDefinition s, boolean full) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", s.name());
        view.put("version", s.version());
        view.put("description", s.description());
        view.put("tools", s.tools());
        view.put("appliesTo", s.appliesTo());
        view.put("evalCases", s.evalCases());
        view.put("runtime", s.runtime());
        if (full) {
            view.put("inputSchema", s.inputSchema());
            view.put("outputSchema", s.outputSchema());
            view.put("instructions", s.instructions());
        }
        return view;
    }
}
