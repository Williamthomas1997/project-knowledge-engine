package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.EvolutionRequestStore;
import com.omnicore.pke.engine.retrieval.LogTrailService;
import com.omnicore.pke.engine.retrieval.LogTrailService.Explanation;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M5: log-trail explanation and the evolution requests left by relationship discovery. */
@RestController
@RequestMapping("/api")
class DiscoveryController {

    private final LogTrailService logTrail;
    private final EvolutionRequestStore evolution;

    DiscoveryController(LogTrailService logTrail, EvolutionRequestStore evolution) {
        this.logTrail = logTrail;
        this.evolution = evolution;
    }

    record LogLine(String line, String apiId) {
    }

    /** The last log line seen → its log statement, the logs expected next, the likely failing method, error codes. */
    @PostMapping("/logs/explain")
    Explanation explain(@RequestBody LogLine body) {
        return logTrail.explain(body == null ? null : body.line(), body == null ? null : body.apiId());
    }

    @GetMapping("/evolution/requests")
    List<Map<String, Object>> evolutionRequests(@RequestParam(required = false) String status) {
        return evolution.list(status);
    }
}
