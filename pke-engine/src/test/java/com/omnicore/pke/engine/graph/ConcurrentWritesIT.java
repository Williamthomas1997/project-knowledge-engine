package com.omnicore.pke.engine.graph;

import static com.omnicore.pke.engine.support.Demo.method;
import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.support.KnowledgeIT;
import com.omnicore.pke.engine.support.Neo4jTestDatabase;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Concurrent writers still produce gap-free, strictly ordered seq values (plan §5, ADR-009). */
class ConcurrentWritesIT extends KnowledgeIT {

    private static final int THREADS = 8;
    private static final int WRITES_PER_THREAD = 10;

    @Autowired
    KnowledgeWriter writer;

    @Autowired
    KnowledgeQueryService queries;

    @Test
    void concurrentWritersGetGapFreeStrictlyOrderedSeqs() throws Exception {
        long start = queries.currentSeq();
        List<Callable<List<ChangeResult>>> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            tasks.add(() -> {
                List<ChangeResult> results = new ArrayList<>();
                for (int i = 0; i < WRITES_PER_THREAD; i++) {
                    // each change set creates its own method and updates one shared feature (contended entity)
                    EntityWrite feature = new EntityWrite("BusinessFeature", null, Map.of("featureKey",
                            "international-transfer", "name", "International transfer", "description",
                            "touched by thread " + thread + " write " + i), EpistemicStatus.OBSERVED, null, null);
                    results.add(writer.apply(new ChangeSet(null,
                            List.of(method("com.demo.T" + thread, "m" + i + "()"), feature), List.of(), List.of()), "t" + thread));
                }
                return results;
            });
        }
        List<ChangeResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (Future<List<ChangeResult>> future : pool.invokeAll(tasks)) {
                results.addAll(future.get());
            }
        }

        long expectedEvents = (long) THREADS * WRITES_PER_THREAD * 2;
        assertThat(results).hasSize(THREADS * WRITES_PER_THREAD).allSatisfy(r -> assertThat(r.eventCount()).isEqualTo(2));
        // each transaction got a contiguous block
        assertThat(results).allSatisfy(r -> assertThat(r.lastSeq() - r.firstSeq()).isEqualTo(1));

        List<Long> seqs = new ArrayList<>();
        List<ZonedDateTime> times = new ArrayList<>();
        try (var session = Neo4jTestDatabase.driver().session(Neo4jTestDatabase.sessionConfig())) {
            session.run("MATCH (e:ChangeEvent) WHERE e.seq > $start RETURN e.seq AS seq, e.at AS at ORDER BY e.seq",
                    Map.of("start", start)).forEachRemaining(r -> {
                seqs.add(r.get("seq").asLong());
                times.add(r.get("at").asZonedDateTime());
            });
        }
        // gap-free and unique
        assertThat(seqs).containsExactlyElementsOf(LongStream.rangeClosed(start + 1, start + expectedEvents).boxed().toList());
        // strictly ordered: commit order follows seq order, so event times never go backwards
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isAfterOrEqualTo(times.get(i - 1));
        }
        assertThat(queries.currentSeq()).isEqualTo(start + expectedEvents);
        // the contended entity has exactly one version per write
        assertThat(count("MATCH (:Entity:BusinessFeature)-[:HAS_VERSION]->(v) RETURN count(v)"))
                .isEqualTo((long) THREADS * WRITES_PER_THREAD);
    }
}
