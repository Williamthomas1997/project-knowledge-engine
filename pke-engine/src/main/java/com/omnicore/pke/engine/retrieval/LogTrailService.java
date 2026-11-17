package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.pipeline.skill.SkillResult;
import com.omnicore.pke.engine.pipeline.skill.SkillRunner;
import com.omnicore.pke.engine.retrieval.RetrievalService.Hit;
import com.omnicore.pke.engine.retrieval.RetrievalService.SearchRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Log trail (M5, good to have): given the last log line someone saw (pasted text), finds the matching
 * {@code LogStatement} (fulltext + vector on {@code LogStatement_template}), then, along the API's log trail
 * (template {@code log-trail-of-api}), the logs expected next, the method that most likely failed (the matched
 * log's method: the failure came after its log line) and the error codes it or its callees can raise. The
 * {@code explain-log-trail} skill adds an explanation when a model is configured.
 */
@Service
public class LogTrailService {

    static final String TEMPLATE = "log-trail-of-api";
    static final String SKILL = "explain-log-trail";
    private static final int MAX_LINE = 2000;

    private final RetrievalService retrieval;
    private final QueryTemplates templates;
    private final CodeQueryService code;
    private final SkillRunner skills;

    public LogTrailService(RetrievalService retrieval, QueryTemplates templates, CodeQueryService code, SkillRunner skills) {
        this.retrieval = retrieval;
        this.templates = templates;
        this.code = code;
        this.skills = skills;
    }

    public record LogEntry(String id, String label, String method) {
    }

    /** {@code explanation} is the skill's answer, or null with {@code explanationStatus} saying why. */
    public record Explanation(String line, LogEntry match, List<LogEntry> candidates, String api,
                              List<LogEntry> nextExpected, String likelyFailingMethod, List<String> errorCodes,
                              Map<String, Object> explanation, String explanationStatus) {
    }

    public Explanation explain(String line, String apiId) {
        if (line == null || line.isBlank() || line.length() > MAX_LINE) {
            throw ValidationFailedException.of("/line", "is required, at most " + MAX_LINE + " characters");
        }
        List<LogEntry> candidates = new ArrayList<>();
        for (Hit hit : retrieval.search(new SearchRequest(line, 5, List.of("LogStatement"), List.of("LogStatement_template"),
                null, null, null, null)).hits()) {
            if ("LogStatement".equals(hit.type())) {
                candidates.add(new LogEntry(hit.id(), hit.label(), null));
            }
        }
        if (candidates.isEmpty()) {
            return new Explanation(line, null, List.of(), null, List.of(), null, List.of(), null, "NO_MATCH");
        }
        LogEntry top = candidates.getFirst();
        Map<String, Object> context = code.logContext(top.id());
        String method = (String) context.get("method");
        LogEntry match = new LogEntry(top.id(), context.get("level") + " " + context.get("template"), method);
        @SuppressWarnings("unchecked")
        List<String> apis = (List<String>) context.getOrDefault("apis", List.of());
        String api = apiId != null ? apiId : apis.isEmpty() ? null : apis.getFirst();
        List<LogEntry> next = new ArrayList<>();
        if (api != null) {
            boolean after = false;
            for (Map<String, Object> row : templates.run(TEMPLATE, api, 200).rows()) {
                if (after) {
                    next.add(new LogEntry((String) row.get("id"), (String) row.get("label"), (String) row.get("detail")));
                }
                after |= top.id().equals(row.get("id"));
            }
        }
        @SuppressWarnings("unchecked")
        List<String> errorCodes = ((List<String>) context.getOrDefault("errorCodes", List.of())).stream().sorted().toList();
        Map<String, Object> explanation = null;
        String status;
        if (!skills.modelAvailable()) {
            status = "SKIPPED_NO_MODEL";
        } else {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("logLine", line);
            input.put("matched", Map.of("log", match.label(), "method", String.valueOf(method)));
            input.put("nextExpected", next.stream().map(e -> Map.of("log", e.label(), "method", e.method())).toList());
            input.put("errorCodes", errorCodes);
            SkillResult result = skills.run(SKILL, input, null, "explain-log-trail");
            explanation = result.output();
            status = "OK";
        }
        return new Explanation(line, match, candidates, api, next, method, errorCodes, explanation, status);
    }
}
