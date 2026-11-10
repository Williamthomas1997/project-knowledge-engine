package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.RetrievalQueries;
import com.omnicore.pke.engine.retrieval.ContextService;
import com.omnicore.pke.engine.retrieval.EvaluationService;
import com.omnicore.pke.engine.retrieval.QueryTemplates;
import com.omnicore.pke.engine.retrieval.RetrievalService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hybrid search, task context, neighbourhoods, query templates and retrieval evaluation. */
@RestController
@RequestMapping("/api")
class RetrievalController {

    private final RetrievalService retrieval;
    private final ContextService context;
    private final RetrievalQueries queries;
    private final QueryTemplates templates;
    private final EvaluationService evaluation;

    RetrievalController(RetrievalService retrieval, ContextService context, RetrievalQueries queries,
                        QueryTemplates templates, EvaluationService evaluation) {
        this.retrieval = retrieval;
        this.context = context;
        this.queries = queries;
        this.templates = templates;
        this.evaluation = evaluation;
    }

    /** {@code {query, k?, types?, collections?, asOfSeq?, includeInvalid?, mmr?, generation?}}. */
    @PostMapping("/retrieval/search")
    RetrievalService.SearchResult search(@RequestBody RetrievalService.SearchRequest request) {
        return retrieval.search(request);
    }

    /** {@code {task, budgetTokens?, depth?, seeds?}}: context in four sections with citations, plus Markdown. */
    @PostMapping("/context")
    ContextService.ContextResult context(@RequestBody ContextService.ContextRequest request) {
        return context.context(request);
    }

    @GetMapping("/entities/{id}/neighbourhood")
    Map<String, Object> neighbourhood(@PathVariable String id, @RequestParam(defaultValue = "1") int depth,
                                      @RequestParam(defaultValue = "50") int limit) {
        return queries.neighbourhood(id, depth, limit);
    }

    @GetMapping("/retrieval/templates")
    List<QueryTemplates.Template> templates() {
        return templates.all();
    }

    /** Runs one template from a seed entity (read-only, guarded). */
    @PostMapping("/retrieval/templates/{id}/run")
    Object runTemplate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return templates.run(id, String.valueOf(body.get("seedId")),
                body.get("limit") == null ? 50 : ((Number) body.get("limit")).intValue());
    }

    /** {@code {dataset?, generation?}}: runs the evaluation datasets against the current graph. */
    @PostMapping("/eval/runs")
    Map<String, Object> evaluate(@RequestBody(required = false) Map<String, String> body) {
        return evaluation.run(body == null ? null : body.get("dataset"), body == null ? null : body.get("generation"));
    }

    @GetMapping("/eval/datasets")
    Object datasets() {
        return evaluation.datasets().values();
    }
}
