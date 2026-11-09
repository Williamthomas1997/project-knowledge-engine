package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.PipelineExecutionStore.ExecutionView;
import com.omnicore.pke.engine.pipeline.PipelineDefinition;
import com.omnicore.pke.engine.pipeline.PipelineExecutor;
import com.omnicore.pke.engine.pipeline.PipelineRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pipelines and their executions. */
@RestController
@RequestMapping("/api/pipelines")
class PipelineController {

    private final PipelineRegistry registry;
    private final PipelineExecutor executor;

    PipelineController(PipelineRegistry registry, PipelineExecutor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    @GetMapping
    List<Map<String, Object>> pipelines() {
        return registry.activePipelines().stream().map(PipelineController::describe).toList();
    }

    /**
     * Starts the active version: body = the pipeline input. Returns {@code 202}; {@code ?wait=true} runs it first.
     * An identical request (same input sources' versions) returns the existing execution unless {@code force=true}.
     */
    @PostMapping("/{id}/executions")
    ResponseEntity<ExecutionView> start(@PathVariable String id, @RequestBody(required = false) Map<String, Object> input,
                                        @RequestParam(defaultValue = "false") boolean wait,
                                        @RequestParam(defaultValue = "false") boolean force,
                                        @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        ExecutionView execution = executor.start(id, input, Actors.of(actor), force, wait);
        return ResponseEntity.status(wait ? HttpStatus.OK : HttpStatus.ACCEPTED).body(execution);
    }

    @GetMapping("/executions")
    List<ExecutionView> executions(@RequestParam(required = false) String pipeline,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "20") int limit) {
        return executor.recent(pipeline, status, limit);
    }

    @GetMapping("/executions/{executionId}")
    ExecutionView execution(@PathVariable String executionId) {
        return executor.get(executionId);
    }

    /** Continues a FAILED, CANCELLED or PARTIAL execution from its first step that did not complete. */
    @PostMapping("/executions/{executionId}/resume")
    ResponseEntity<ExecutionView> resume(@PathVariable String executionId, @RequestParam(defaultValue = "false") boolean wait,
                                         @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        ExecutionView execution = executor.resume(executionId, Actors.of(actor), wait);
        return ResponseEntity.status(wait ? HttpStatus.OK : HttpStatus.ACCEPTED).body(execution);
    }

    @PostMapping("/executions/{executionId}/cancel")
    ExecutionView cancel(@PathVariable String executionId,
                         @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return executor.cancel(executionId, Actors.of(actor));
    }

    private static Map<String, Object> describe(PipelineDefinition p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.id());
        view.put("version", p.version());
        view.put("description", p.description());
        view.put("steps", p.steps().stream().map(s -> Map.of("id", s.id(), "uses", s.uses())).toList());
        return view;
    }
}
