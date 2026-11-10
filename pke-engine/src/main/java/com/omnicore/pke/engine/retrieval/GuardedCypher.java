package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.graph.GraphReader;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs Cypher the engine did not write itself (LLM fallback) or templates, behind every guard of ADR-007 §7:
 * static checks ({@link CypherGuard}), {@code EXPLAIN} must succeed, a read-only transaction (the database rejects
 * writes) with a server-side timeout, and the query and result size are logged.
 */
@Service
public class GuardedCypher {

    private static final Logger log = LoggerFactory.getLogger(GuardedCypher.class);

    public record Execution(String cypher, List<Map<String, Object>> rows, long millis) {
    }

    private final GraphReader reader;
    private final GraphVocabulary vocabulary;
    private final Duration timeout;
    private final int maxRows;

    public GuardedCypher(GraphReader reader, GraphVocabulary vocabulary,
                         @Value("${pke.retrieval.cypher-timeout:PT5S}") Duration timeout,
                         @Value("${pke.retrieval.cypher-max-rows:200}") int maxRows) {
        this.reader = reader;
        this.vocabulary = vocabulary;
        this.timeout = timeout;
        this.maxRows = maxRows;
    }

    public CypherGuard.Result check(String cypher) {
        return vocabulary.guard(maxRows).check(cypher);
    }

    /** All guards, then execution. Rejections are {@link ValidationFailedException}s listing every reason. */
    public Execution run(String cypher, Map<String, Object> parameters, String purpose) {
        return run(cypher, parameters, purpose, timeout);
    }

    public Execution run(String cypher, Map<String, Object> parameters, String purpose, Duration timeout) {
        CypherGuard.Result result = check(cypher);
        if (!result.allowed()) {
            log.warn("Rejected {} Cypher: {}", purpose, result.violations());
            throw new ValidationFailedException("Cypher rejected by the guard", result.violations());
        }
        return execute(result.cypher(), parameters, purpose, timeout);
    }

    /**
     * EXPLAIN, then a read-only, time-limited execution. Package-visible so tests can prove the read-only and timeout
     * guards hold even for a query the static guard would have rejected.
     */
    Execution execute(String cypher, Map<String, Object> parameters, String purpose, Duration timeout) {
        try {
            reader.query("EXPLAIN " + cypher, parameters);
        } catch (RuntimeException e) {
            log.warn("Rejected {} Cypher: EXPLAIN failed: {}", purpose, e.getMessage());
            throw new ValidationFailedException("Cypher rejected by the guard",
                    List.of(new Violation("$", "EXPLAIN failed: " + e.getMessage())));
        }
        long started = System.nanoTime();
        try {
            List<Map<String, Object>> rows = reader.query(cypher, parameters, timeout);
            long millis = (System.nanoTime() - started) / 1_000_000;
            log.info("{} Cypher returned {} rows in {} ms: {}", purpose, rows.size(), millis, cypher.replaceAll("\\s+", " "));
            return new Execution(cypher, rows, millis);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            boolean timedOut = message.contains("timeout") || message.contains("TimedOut") || message.contains("terminated");
            log.warn("{} Cypher failed{}: {}", purpose, timedOut ? " (timeout " + timeout + ")" : "", message);
            throw new ValidationFailedException("Cypher execution failed", List.of(new Violation("$",
                    (timedOut ? "timed out after " + timeout + ": " : "") + message)));
        }
    }
}
