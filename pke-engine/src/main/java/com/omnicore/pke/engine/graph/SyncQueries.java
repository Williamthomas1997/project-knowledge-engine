package com.omnicore.pke.engine.graph;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Read queries behind {@code GET /api/sync/status} (MASTER_SPEC §19.4). */
@Service
public class SyncQueries {

    private final GraphReader reader;

    public SyncQueries(GraphReader reader) {
        this.reader = reader;
    }

    /** Repositories with their last successful run's commit; documents with their current version and last brd-ingest. */
    public List<Map<String, Object>> sources() {
        return reader.query("""
                MATCH (a:SourceArtifact) WHERE a.status = 'ACTIVE'
                OPTIONAL MATCH (a)-[:CURRENT_VERSION]->(v:SourceVersion)
                OPTIONAL MATCH (r:IngestionRun {kind: 'REPOSITORY', target: a.key}) WHERE r.status IN ['COMPLETED', 'PARTIAL']
                WITH a, v, r ORDER BY r.finishedAt DESC
                WITH a, v, collect(r)[0] AS run
                OPTIONAL MATCH (e:PipelineExecution {pipelineId: 'brd-ingest', status: 'COMPLETED'})
                  WHERE e.inputJson CONTAINS ('"' + a.key + '"')
                WITH a, v, run, e ORDER BY e.finishedAt DESC
                WITH a, v, run, collect(e)[0] AS execution
                RETURN a.key AS key, a.kind AS kind, a.path AS path, v.version AS currentVersion, v.recordedAt AS versionAt,
                       run.toVersion AS lastIngestedVersion, run.finishedAt AS lastIngestedAt,
                       execution.finishedAt AS lastPipelineAt
                ORDER BY key""", Map.of());
    }

    /** Current entities supported only by evidence from document versions that are no longer current. */
    public long entitiesWithOnlyObsoleteEvidence() {
        return ((Number) reader.single("""
                MATCH (n:Entity) WHERE coalesce(n.pke_status, 'CURRENT') = 'CURRENT'
                  AND EXISTS { (n)-[:SUPPORTED_BY]->(:Evidence)-[:FROM]->(:SourceVersion {kind: 'document'}) }
                  AND NOT EXISTS {
                    MATCH (n)-[:SUPPORTED_BY]->(:Evidence)-[:FROM]->(v:SourceVersion)
                    WHERE v.kind <> 'document' OR EXISTS { (:SourceArtifact)-[:CURRENT_VERSION]->(v) }
                  }
                  AND NOT EXISTS { (n)-[:SUPPORTED_BY]->(e:Evidence) WHERE e.sourceVersionId IS NULL }
                RETURN count(n) AS c""", Map.of()).orElseThrow().get("c")).longValue();
    }

    public List<Map<String, Object>> unfinishedPipelines() {
        return reader.query("""
                MATCH (e:PipelineExecution) WHERE e.status IN ['FAILED', 'PARTIAL', 'RUNNING', 'PENDING']
                RETURN e.pke_id AS id, e.pipelineId AS pipeline, e.status AS status, e.errorClass AS errorClass,
                       e.error AS error, e.createdAt AS createdAt ORDER BY e.createdAt DESC LIMIT 50""", Map.of());
    }

    public List<Map<String, Object>> unfinishedRuns() {
        return reader.query("""
                MATCH (r:IngestionRun) WHERE r.status IN ['FAILED', 'PARTIAL', 'RUNNING', 'PENDING']
                RETURN r.pke_id AS id, r.kind AS kind, r.target AS target, r.status AS status, r.error AS error,
                       r.createdAt AS createdAt ORDER BY r.createdAt DESC LIMIT 50""", Map.of());
    }

    public long openQuestions() {
        return ((Number) reader.single("MATCH (q:OpenQuestion {status: 'OPEN'}) RETURN count(q) AS c", Map.of())
                .orElseThrow().get("c")).longValue();
    }
}
