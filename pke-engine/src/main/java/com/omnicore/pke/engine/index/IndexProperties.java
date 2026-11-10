package com.omnicore.pke.engine.index;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vector index settings ({@code pke.index.*}).
 *
 * @param workerEnabled  run the outbox worker on a schedule ({@code worker-interval})
 * @param batchSize      tasks per worker batch
 * @param awaitTimeout   how long an ingestion run or pipeline waits for its index tasks before ending PARTIAL
 * @param retryBackoff   first retry delay of a failed task (doubles per attempt, capped at 10 minutes)
 * @param maxAttempts    attempts before a task is FAILED (then {@code POST /api/sync/retry-failed})
 * @param project        the {@code project} payload of every vector record
 */
@ConfigurationProperties("pke.index")
public record IndexProperties(Boolean workerEnabled, Duration workerInterval, Integer batchSize, Duration awaitTimeout,
                              Duration retryBackoff, Integer maxAttempts, String project) {

    public IndexProperties {
        workerEnabled = workerEnabled == null || workerEnabled;
        workerInterval = workerInterval == null ? Duration.ofSeconds(1) : workerInterval;
        batchSize = batchSize == null ? 100 : batchSize;
        awaitTimeout = awaitTimeout == null ? Duration.ofSeconds(60) : awaitTimeout;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(2) : retryBackoff;
        maxAttempts = maxAttempts == null ? 8 : maxAttempts;
        project = project == null || project.isBlank() ? "default" : project;
    }
}
