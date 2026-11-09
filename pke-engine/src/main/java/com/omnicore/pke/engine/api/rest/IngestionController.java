package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.graph.CodeQueryService.JourneySummary;
import com.omnicore.pke.engine.graph.CodeQueryService.JourneyView;
import com.omnicore.pke.engine.graph.IngestionRunStore.Run;
import com.omnicore.pke.engine.ingest.IngestionRunService;
import com.omnicore.pke.engine.ingest.IngestionRunService.Request;
import java.util.List;
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

/** Ingestion runs and the journeys they produce. */
@RestController
@RequestMapping("/api")
class IngestionController {

    private final IngestionRunService runs;
    private final CodeQueryService journeys;

    IngestionController(IngestionRunService runs, CodeQueryService journeys) {
        this.runs = runs;
        this.journeys = journeys;
    }

    /**
     * Starts a run: {@code {kind: REPOSITORY|ROUTES, target: <repository name>, mode: FULL|INCREMENTAL}}. Returns
     * {@code 202} with the run; poll {@code GET /api/ingestion/runs/{id}}. {@code ?wait=true} runs it before replying.
     */
    @PostMapping("/ingestion/runs")
    ResponseEntity<Run> start(@RequestBody Request request, @RequestParam(defaultValue = "false") boolean wait,
                              @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        Run run = runs.start(request, Actors.of(actor), wait);
        return ResponseEntity.status(wait ? HttpStatus.OK : HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/ingestion/runs")
    List<Run> recent(@RequestParam(required = false) String target, @RequestParam(defaultValue = "20") int limit) {
        return runs.recent(target, limit);
    }

    @GetMapping("/ingestion/runs/{id}")
    Run run(@PathVariable String id) {
        return runs.get(id);
    }

    /** Starts a new attempt of a finished run with the same parameters. */
    @PostMapping("/ingestion/runs/{id}/retry")
    ResponseEntity<Run> retry(@PathVariable String id, @RequestParam(defaultValue = "false") boolean wait,
                              @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        Run run = runs.retry(id, Actors.of(actor), wait);
        return ResponseEntity.status(wait ? HttpStatus.OK : HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/journeys")
    List<JourneySummary> journeys() {
        return journeys.journeys();
    }

    /** A journey by id or key: ordered steps with their APIs and handler methods, data flow and covering suites. */
    @GetMapping("/journeys/{idOrKey}")
    JourneyView journey(@PathVariable String idOrKey) {
        return journeys.journey(idOrKey);
    }
}
