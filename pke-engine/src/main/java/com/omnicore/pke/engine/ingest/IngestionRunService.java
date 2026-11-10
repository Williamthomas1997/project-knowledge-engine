package com.omnicore.pke.engine.ingest;

import com.omnicore.pke.engine.graph.IngestionRunStore;
import com.omnicore.pke.engine.index.OperationIndexing;
import com.omnicore.pke.engine.graph.IngestionRunStore.Run;
import com.omnicore.pke.engine.graph.IngestionRunStore.Status;
import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ingestion runs: create, execute (in the background by default, one at a time), record status and results, retry.
 * A run is {@code COMPLETED} only when every file was analysed and every write succeeded; otherwise
 * {@code PARTIAL} (with the failures listed) or {@code FAILED}.
 */
@Service
public class IngestionRunService {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunService.class);
    private static final int MAX_UNRESOLVED = 200;

    private final IngestionRunStore runs;
    private final RepositoryIngestionService repositories;
    private final RouteResolutionService routes;
    private final SourceQueryService sources;
    private final OperationIndexing indexing;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pke-ingestion");
        t.setDaemon(true);
        return t;
    });

    public IngestionRunService(IngestionRunStore runs, RepositoryIngestionService repositories, RouteResolutionService routes,
                               SourceQueryService sources, OperationIndexing indexing) {
        this.indexing = indexing;
        this.runs = runs;
        this.repositories = repositories;
        this.routes = routes;
        this.sources = sources;
    }

    public enum Kind { REPOSITORY, ROUTES }

    public enum Mode { FULL, INCREMENTAL }

    public record Request(Kind kind, String target, Mode mode) {
    }

    public Run start(Request request, String actor, boolean wait) {
        if (request == null || request.kind() == null) {
            throw ValidationFailedException.of("/kind", "is required (REPOSITORY or ROUTES)");
        }
        String target = request.kind() == Kind.ROUTES ? "routes" : RepositoryIngestionService.key(require(request.target()));
        if (request.kind() == Kind.REPOSITORY) {
            sources.byKey(target);
        }
        Mode mode = request.mode() == null ? Mode.INCREMENTAL : request.mode();
        Run run = runs.create(request.kind().name(), target, mode.name(), null, 1, actor);
        return submit(run, actor, wait);
    }

    public Run retry(String runId, String actor, boolean wait) {
        Run previous = runs.get(runId);
        if (previous.status() == Status.PENDING || previous.status() == Status.RUNNING) {
            throw new ConflictException("Run " + runId + " is still " + previous.status());
        }
        Run run = runs.create(previous.kind(), previous.target(), previous.mode(), runId, previous.attempt() + 1, actor);
        return submit(run, actor, wait);
    }

    public Run get(String id) {
        return runs.get(id);
    }

    public List<Run> recent(String target, int limit) {
        return runs.recent(target, limit);
    }

    private Run submit(Run run, String actor, boolean wait) {
        var future = executor.submit(() -> execute(run, actor));
        if (wait) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("Run {} failed", run.id(), e.getCause());
            }
        }
        return runs.get(run.id());
    }

    private void execute(Run run, String actor) {
        runs.update(run.id(), Map.of("status", Status.RUNNING.name(), "startedAt", OffsetDateTime.now()));
        Map<String, Object> stats = new LinkedHashMap<>();
        List<String> unresolved;
        Status status;
        String from = null;
        String to = null;
        try {
            if (Kind.valueOf(run.kind()) == Kind.REPOSITORY) {
                String since = Mode.valueOf(run.mode()) == Mode.INCREMENTAL
                        ? runs.lastProcessedVersion(run.kind(), run.target()).orElse(null) : null;
                RepositoryIngestionService.Report report = repositories.ingest(run.target(), since, run.id(), actor);
                from = report.fromCommit();
                to = report.toCommit();
                stats.put("mode", report.mode());
                stats.put("filesExtracted", report.filesExtracted());
                stats.put("outcomes", report.stats().outcomes());
                stats.put("journeys", report.journeys());
                stats.put("parseProblems", report.parseProblems());
                stats.put("failures", report.stats().failures());
                unresolved = new java.util.ArrayList<>(report.unresolved());
                RouteResolutionService.Report routeReport = routes.resolve(run.id(), actor);
                stats.put("routes", routeStats(routeReport));
                unresolved.addAll(routeReport.unmatched().stream().map(u -> "route: " + u).toList());
                boolean clean = report.stats().failures().isEmpty() && report.parseProblems().isEmpty()
                        && routeReport.stats().failures().isEmpty();
                status = clean ? Status.COMPLETED : Status.PARTIAL;
            } else {
                RouteResolutionService.Report routeReport = routes.resolve(run.id(), actor);
                stats.put("routes", routeStats(routeReport));
                unresolved = routeReport.unmatched().stream().map(u -> "route: " + u).toList();
                status = routeReport.stats().failures().isEmpty() ? Status.COMPLETED : Status.PARTIAL;
            }
            long openIndexTasks = indexing.await(run.id());
            stats.put("openIndexTasks", openIndexTasks);
            Map<String, Object> fields = new HashMap<>();
            if (openIndexTasks > 0 && status == Status.COMPLETED) {
                status = Status.PARTIAL;
                fields.put("indexPending", true);
            }
            fields.put("status", status.name());
            fields.put("finishedAt", OffsetDateTime.now());
            fields.put("fromVersion", from);
            fields.put("toVersion", to);
            fields.put("statsJson", CanonicalJson.write(stats));
            fields.put("unresolvedCount", (long) unresolved.size());
            fields.put("unresolvedJson", CanonicalJson.write(unresolved.subList(0, Math.min(MAX_UNRESOLVED, unresolved.size()))));
            runs.update(run.id(), fields);
        } catch (RuntimeException e) {
            log.error("Ingestion run {} failed", run.id(), e);
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", Status.FAILED.name());
            fields.put("finishedAt", OffsetDateTime.now());
            fields.put("error", String.valueOf(e.getMessage()));
            runs.update(run.id(), fields);
        }
    }

    private static Map<String, Object> routeStats(RouteResolutionService.Report report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("gatewayApis", report.gatewayApis());
        map.put("resolved", report.resolved());
        map.put("unmatched", report.unmatched().size());
        map.put("outcomes", report.stats().outcomes());
        map.put("failures", report.stats().failures());
        return map;
    }

    private static String require(String target) {
        if (target == null || target.isBlank()) {
            throw ValidationFailedException.of("/target", "is required: the repository name");
        }
        return target;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
