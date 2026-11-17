package com.omnicore.pke.engine.history;

import com.omnicore.pke.engine.graph.HistoryStore;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.source.DocumentStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Durability beyond Neo4j (plan §5, {@code docs/recovery.md}): change events not yet exported are written as one
 * JSONL file (one event per line, canonical JSON, seq order) to the document store, and the export is recorded as a
 * {@code :HistoryExport} node with its seq range and content hash. Runs nightly and on demand.
 */
@Service
public class HistoryExporter {

    private static final Logger log = LoggerFactory.getLogger(HistoryExporter.class);
    private static final int PAGE = 10_000;

    private final HistoryStore store;
    private final KnowledgeQueryService queries;
    private final DocumentStore documents;

    public HistoryExporter(HistoryStore store, KnowledgeQueryService queries, DocumentStore documents) {
        this.store = store;
        this.queries = queries;
        this.documents = documents;
    }

    public record Export(long fromSeq, long toSeq, long events, String ref, String sha256) {
    }

    @Scheduled(cron = "${pke.history.export-cron:0 0 2 * * *}")
    void nightly() {
        export().ifPresent(e -> log.info("Exported change events {}..{} ({}) to {}", e.fromSeq() + 1, e.toSeq(),
                e.events(), e.ref()));
    }

    /** Exports the events after the last export; empty when there is nothing new. */
    public synchronized Optional<Export> export() {
        long from = store.lastExportedSeq();
        long to = queries.currentSeq();
        if (to <= from) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long count = 0;
        long after = from;
        while (after < to) {
            List<Map<String, Object>> rows = store.eventRows(after, to, PAGE);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                Map<String, Object> line = new TreeMap<>(row);
                line.put("at", String.valueOf(row.get("at")));
                out.writeBytes((CanonicalJson.write(line) + "\n").getBytes(StandardCharsets.UTF_8));
                after = ((Number) row.get("seq")).longValue();
                count++;
            }
        }
        if (count != to - from) {
            throw new IllegalStateException("Expected " + (to - from) + " events between seq " + from + " and " + to
                    + " but read " + count + ": the event log has a gap");
        }
        DocumentStore.Stored stored = documents.put(out.toByteArray());
        store.recordExport(from, to, stored.ref(), stored.sha256(), count);
        return Optional.of(new Export(from, to, count, stored.ref(), stored.sha256()));
    }

    public List<Map<String, Object>> exports() {
        return store.exports();
    }
}
