package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.HistoryQueries;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemChange;
import com.omnicore.pke.engine.graph.HistoryQueries.ItemState;
import com.omnicore.pke.engine.graph.HistoryStore.SnapshotView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EventFilter;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EventView;
import com.omnicore.pke.engine.history.HistoryExporter;
import com.omnicore.pke.engine.history.MigrationService;
import com.omnicore.pke.engine.history.RecoveryService;
import com.omnicore.pke.engine.history.SnapshotService;
import com.omnicore.pke.engine.history.SnapshotService.SnapshotDiff;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** History (M5): state as of a seq, diffs, snapshots, commit → knowledge, event export, restore and migrations. */
@RestController
@RequestMapping("/api")
class HistoryController {

    private final HistoryQueries history;
    private final KnowledgeQueryService queries;
    private final SnapshotService snapshots;
    private final HistoryExporter exporter;
    private final RecoveryService recovery;
    private final MigrationService migrations;

    HistoryController(HistoryQueries history, KnowledgeQueryService queries, SnapshotService snapshots,
                      HistoryExporter exporter, RecoveryService recovery, MigrationService migrations) {
        this.history = history;
        this.queries = queries;
        this.snapshots = snapshots;
        this.exporter = exporter;
        this.recovery = recovery;
        this.migrations = migrations;
    }

    record NewSnapshot(String label) {
    }

    record Restore(Long seq, String reason) {
    }

    @GetMapping("/history/state")
    List<ItemState> state(@RequestParam long seq, @RequestParam(required = false) String type,
                          @RequestParam(defaultValue = "1000") int limit) {
        return history.stateAt(seq, type, limit);
    }

    @GetMapping("/history/diff")
    List<ItemChange> diff(@RequestParam long fromSeq, @RequestParam(required = false) Long toSeq) {
        long to = toSeq == null ? queries.currentSeq() : toSeq;
        if (fromSeq > to) {
            throw ValidationFailedException.of("/fromSeq", "must not be after toSeq");
        }
        return history.diff(fromSeq, to);
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    SnapshotView createSnapshot(@RequestBody(required = false) NewSnapshot body,
                                @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return snapshots.create(body == null ? null : body.label(), Actors.of(actor));
    }

    @GetMapping("/snapshots")
    List<SnapshotView> listSnapshots(@RequestParam(defaultValue = "50") int limit) {
        return snapshots.list(limit);
    }

    @GetMapping("/snapshots/diff")
    SnapshotDiff snapshotDiff(@RequestParam String from, @RequestParam String to) {
        return snapshots.diff(from, to);
    }

    @GetMapping("/snapshots/{id}")
    SnapshotView snapshot(@PathVariable String id) {
        return snapshots.get(id);
    }

    /** Knowledge changes produced by a commit: the events of the runs that processed it and of evidence citing it. */
    @GetMapping("/commits/{sha}/knowledge-changes")
    Map<String, Object> commitChanges(@PathVariable String sha, @RequestParam(defaultValue = "200") int limit) {
        if (!sha.matches("^[0-9a-f]{7,40}$")) {
            throw ValidationFailedException.of("/sha", "must be 7 to 40 lower-case hex characters");
        }
        List<EventView> events = queries.events(new EventFilter(0, null, limit, null, null, sha, null));
        Map<String, Long> counts = new TreeMap<>();
        events.forEach(e -> counts.merge(e.type(), 1L, Long::sum));
        return Map.of("commit", sha, "counts", counts, "events", events);
    }

    @PostMapping("/history/exports")
    ExportResult export() {
        return new ExportResult(exporter.export().orElse(null));
    }

    record ExportResult(HistoryExporter.Export export) {
    }

    @GetMapping("/history/exports")
    List<Map<String, Object>> exports() {
        return exporter.exports();
    }

    /** Reverts every knowledge change after {@code seq}, with history (nothing is deleted). */
    @PostMapping("/history/restore")
    ChangeResult restore(@RequestBody Restore body, @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        long current = queries.currentSeq();
        if (body == null || body.seq() == null || body.seq() < 0 || body.seq() > current) {
            throw ValidationFailedException.of("/seq", "is required, between 0 and the current seq " + current);
        }
        return recovery.restoreTo(body.seq(), current, body.reason() == null ? "restore to seq " + body.seq() : body.reason(),
                Actors.of(actor));
    }

    @PostMapping("/schema/migrations")
    MigrationService.Result migrate(@RequestBody MigrationService.Migration migration,
                                    @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return migrations.run(migration, Actors.of(actor));
    }
}
