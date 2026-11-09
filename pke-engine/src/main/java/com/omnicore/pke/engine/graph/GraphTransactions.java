package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.PkeIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one place where knowledge write transactions are opened (ADR-009). Every write transaction:
 * <ol>
 *   <li>locks the {@code :Sequence} node first, which serialises knowledge writes, so {@code seq} values are
 *       gap-free and strictly follow commit order;</li>
 *   <li>runs the caller's work, which allocates a {@code seq} per change event;</li>
 *   <li>writes all {@code :ChangeEvent}s and advances the sequence, all in the same transaction.</li>
 * </ol>
 * If anything fails, Neo4j rolls back the graph changes, the versions, the events and the sequence together.
 */
@Component
public class GraphTransactions {

    static final String SEQUENCE_NAME = "knowledge";

    private final GraphSessions sessions;
    private final List<WriteFaultInjector> faultInjectors;
    private final Counter transactions;
    private final Counter events;

    GraphTransactions(GraphSessions sessions, ObjectProvider<WriteFaultInjector> faultInjectors,
                      ObjectProvider<MeterRegistry> meterRegistry) {
        this.sessions = sessions;
        this.faultInjectors = faultInjectors.orderedStream().toList();
        MeterRegistry registry = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        this.transactions = Counter.builder("pke.graph.write.transactions")
                .description("Knowledge write transactions opened").register(registry);
        this.events = Counter.builder("pke.graph.change.events")
                .description("Change events committed").register(registry);
    }

    /** Runs {@code work} in one write transaction. The driver retries the whole unit on transient errors. */
    <T> T write(String actor, String operationId, Function<WriteContext, T> work) {
        transactions.increment();
        try (Session session = sessions.write()) {
            WriteOutcome<T> outcome = session.executeWrite(tx -> {
                WriteContext context = new WriteContext(tx, actor, operationId, faultInjectors);
                T result = work.apply(context);
                context.commitEvents();
                return new WriteOutcome<>(result, context.events.size());
            });
            events.increment(outcome.eventCount());
            return outcome.result();
        }
    }

    private record WriteOutcome<T>(T result, int eventCount) {
    }

    /** State of one write transaction attempt. Created fresh for every retry. */
    static final class WriteContext {

        private final TransactionContext tx;
        private final String actor;
        private final String operationId;
        private final List<WriteFaultInjector> faultInjectors;
        private final OffsetDateTime now;
        private final long startSeq;
        private long seq;
        private final List<PendingEvent> events = new ArrayList<>();

        private WriteContext(TransactionContext tx, String actor, String operationId, List<WriteFaultInjector> faultInjectors) {
            this.tx = tx;
            this.actor = actor;
            this.operationId = operationId;
            this.faultInjectors = faultInjectors;
            // Taking the write lock on the sequence node first serialises knowledge writes (plan risk R4).
            this.startSeq = tx.run("""
                            MERGE (s:Sequence {name: $name})
                              ON CREATE SET s.value = 0
                            SET s.lockedBy = $token
                            RETURN s.value AS value""",
                    Map.of("name", SEQUENCE_NAME, "token", PkeIds.uuidV7().toString())).single().get("value").asLong();
            this.seq = startSeq;
            // Read the clock after the lock, so event times follow seq order.
            this.now = OffsetDateTime.now(ZoneOffset.UTC);
        }

        List<Record> run(String cypher, Map<String, Object> parameters) {
            return tx.run(cypher, parameters).list();
        }

        Optional<Record> single(String cypher, Map<String, Object> parameters) {
            List<Record> records = run(cypher, parameters);
            if (records.size() > 1) {
                throw new IllegalStateException("Expected at most one row but got " + records.size());
            }
            return records.stream().findFirst();
        }

        long nextSeq() {
            return ++seq;
        }

        void emit(PendingEvent event) {
            events.add(event);
        }

        void reached(WriteStage stage) {
            faultInjectors.forEach(injector -> injector.reached(stage));
        }

        OffsetDateTime now() {
            return now;
        }

        String actor() {
            return actor;
        }

        String operationId() {
            return operationId;
        }

        private void commitEvents() {
            reached(WriteStage.BEFORE_EVENTS);
            if (events.isEmpty()) {
                return;
            }
            if (events.size() != seq - startSeq) {
                throw new IllegalStateException("Allocated " + (seq - startSeq) + " seq values but recorded "
                        + events.size() + " events: every allocated seq must have exactly one event");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PendingEvent event : events) {
                Map<String, Object> row = new HashMap<>();
                row.put("seq", event.seq());
                row.put("type", event.type());
                row.put("at", now);
                row.put("actor", actor);
                row.put("operationId", operationId);
                row.put("subjectKind", event.subjectKind().name());
                row.put("subjectId", event.subjectId());
                row.put("subjectType", event.subjectType());
                row.put("version", event.version());
                row.put("details", CanonicalJson.write(event.details()));
                rows.add(row);
            }
            tx.run("UNWIND $events AS e CREATE (ev:ChangeEvent) SET ev = e", Map.of("events", rows)).consume();
            link(rows, PendingEvent.SubjectKind.ENTITY, """
                    UNWIND $rows AS row
                    MATCH (ev:ChangeEvent {seq: row.seq}), (s:Entity {pke_id: row.subjectId})
                    CREATE (ev)-[:ABOUT]->(s)""");
            link(rows, PendingEvent.SubjectKind.RELATIONSHIP, """
                    UNWIND $rows AS row
                    MATCH (ev:ChangeEvent {seq: row.seq}), (s:Assertion {pke_id: row.subjectId})
                    CREATE (ev)-[:ABOUT]->(s)""");
            link(rows, PendingEvent.SubjectKind.SCHEMA, """
                    UNWIND $rows AS row
                    MATCH (ev:ChangeEvent {seq: row.seq}), (s:SchemaDefinition {pke_id: row.subjectId})
                    CREATE (ev)-[:ABOUT]->(s)""");
            link(rows, PendingEvent.SubjectKind.SOURCE, """
                    UNWIND $rows AS row
                    MATCH (ev:ChangeEvent {seq: row.seq}), (s:SourceArtifact {pke_id: row.subjectId})
                    CREATE (ev)-[:ABOUT]->(s)""");
            tx.run("MATCH (s:Sequence {name: $name}) SET s.value = $value",
                    Map.of("name", SEQUENCE_NAME, "value", seq)).consume();
        }

        private void link(List<Map<String, Object>> rows, PendingEvent.SubjectKind kind, String cypher) {
            List<Map<String, Object>> subset = rows.stream().filter(r -> kind.name().equals(r.get("subjectKind"))).toList();
            if (!subset.isEmpty()) {
                tx.run(cypher, Map.of("rows", subset)).consume();
            }
        }
    }
}
