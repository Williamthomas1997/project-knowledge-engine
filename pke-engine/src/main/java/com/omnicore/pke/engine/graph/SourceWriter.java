package com.omnicore.pke.engine.graph;

import static com.omnicore.pke.engine.graph.KnowledgeWriter.params;

import com.omnicore.pke.engine.graph.GraphTransactions.WriteContext;
import com.omnicore.pke.engine.graph.PendingEvent.SubjectKind;
import com.omnicore.pke.engine.kernel.ChangeEventTypes;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

/**
 * Writes source artifacts and their versions (ADR-011): documents (version = content hash) with their sections,
 * repositories (version = Git commit), and removal. Same single write path and history as knowledge (ADR-009).
 * <pre>
 * (:SourceArtifact {key, kind, status})-[:HAS_VERSION]->(:SourceVersion {version})-[:HAS_SECTION]->(:Section)
 * (:SourceArtifact)-[:CURRENT_VERSION]->(:SourceVersion)
 * </pre>
 * Versions and sections are never deleted, so evidence that cites them stays resolvable.
 */
@Service
public class SourceWriter {

    private final GraphTransactions transactions;

    public SourceWriter(GraphTransactions transactions) {
        this.transactions = transactions;
    }

    public enum Outcome { CREATED, UPDATED, UNCHANGED, REPARSED, REINSTATED, REMOVED }

    public record SectionInput(int order, List<String> headingPath, String heading, int level, Integer page,
                               String text, String hash) {
    }

    public record DocumentVersionInput(String key, String name, String filename, String mediaType, String contentHash,
                                       String blobRef, long sizeBytes, String parserVersion, String parseStatus,
                                       List<SectionInput> sections) {
    }

    public record CommitInput(String sha, List<String> parents, String authoredAt, List<String> changedFiles,
                              Map<String, String> renames) {
    }

    public record SourceResult(String artifactId, String key, String versionId, String version, Outcome outcome, Long seq) {
    }

    public SourceResult registerRepository(String key, String name, String path, String serviceName, String actor) {
        return transactions.write(actor, PkeIds.uuidV7().toString(), ctx -> {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("name", name);
            config.put("path", path);
            config.put("serviceName", serviceName);
            Outcome outcome = upsertArtifact(ctx, key, "repository", config);
            Long seq = outcome == Outcome.UNCHANGED ? null : emit(ctx, key, ChangeEventTypes.SOURCE_REGISTERED,
                    Map.of("kind", "repository", "outcome", outcome.name()));
            return new SourceResult(PkeIds.sourceArtifactId(key), key, null, null, outcome, seq);
        });
    }

    /**
     * Records a version of an exported file the engine reads but does not parse into sections (e.g. the gateway route
     * export), so evidence citing it resolves: {@code kind: export}, version = content hash. Unchanged input writes
     * nothing; a new hash is a new current version.
     */
    public SourceResult recordExportVersion(String key, String name, String path, String contentHash, String actor) {
        return transactions.write(actor, PkeIds.uuidV7().toString(), ctx -> {
            String artifactId = PkeIds.sourceArtifactId(key);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("name", name);
            config.put("path", path);
            Outcome artifact = upsertArtifact(ctx, key, "export", config);
            String versionId = PkeIds.sourceVersionId(key, contentHash);
            boolean known = ctx.single("MATCH (v:SourceVersion {pke_id: $id}) RETURN v.pke_id AS id", Map.of("id", versionId)).isPresent();
            boolean current = ctx.single("MATCH (:SourceArtifact {pke_id: $a})-[:CURRENT_VERSION]->(v:SourceVersion {pke_id: $v}) RETURN v.pke_id AS id",
                    Map.of("a", artifactId, "v", versionId)).isPresent();
            if (known && current && artifact == Outcome.UNCHANGED) {
                return new SourceResult(artifactId, key, versionId, contentHash, Outcome.UNCHANGED, null);
            }
            if (!known) {
                ctx.run("""
                        MATCH (a:SourceArtifact {pke_id: $artifactId})
                        CREATE (a)-[:HAS_VERSION]->(:SourceVersion {pke_id: $id, artifactKey: $key, kind: 'export',
                                version: $version, contentHash: $version, recordedAt: $at})""",
                        Map.of("artifactId", artifactId, "id", versionId, "key", key, "version", contentHash, "at", ctx.now()));
            }
            pointCurrent(ctx, artifactId, versionId);
            Long seq = emit(ctx, key, ChangeEventTypes.EXPORT_VERSION_RECORDED,
                    Map.of("kind", "export", "version", contentHash));
            return new SourceResult(artifactId, key, versionId, contentHash, known ? Outcome.REINSTATED : Outcome.CREATED, seq);
        });
    }

    /** Adds a document version (or reparses / reinstates an existing one). Unchanged input writes nothing. */
    public SourceResult addDocumentVersion(DocumentVersionInput in, String actor) {
        return transactions.write(actor, PkeIds.uuidV7().toString(), ctx -> {
            String artifactId = PkeIds.sourceArtifactId(in.key());
            String versionId = PkeIds.sourceVersionId(in.key(), in.contentHash());
            Outcome artifactOutcome = upsertArtifact(ctx, in.key(), "document",
                    Map.of("name", in.name(), "mediaType", in.mediaType()));
            if (artifactOutcome == Outcome.CREATED) {
                emit(ctx, in.key(), ChangeEventTypes.SOURCE_REGISTERED, Map.of("kind", "document"));
            }
            Record version = ctx.single("""
                    MATCH (v:SourceVersion {pke_id: $id})
                    OPTIONAL MATCH (a:SourceArtifact {pke_id: $artifactId})-[:CURRENT_VERSION]->(current)
                    RETURN v.parserVersion AS parserVersion, current.pke_id AS currentId""",
                    Map.of("id", versionId, "artifactId", artifactId)).orElse(null);

            if (version == null) {
                Long seq = emit(ctx, in.key(), ChangeEventTypes.DOCUMENT_VERSION_ADDED, Map.of("version", in.contentHash(),
                        "sections", in.sections().size(), "parserVersion", in.parserVersion()));
                ctx.run("""
                        MATCH (a:SourceArtifact {pke_id: $artifactId})
                        CREATE (a)-[:HAS_VERSION]->(v:SourceVersion {pke_id: $id, artifactKey: $key, kind: 'document',
                                version: $hash, contentHash: $hash, blobRef: $blobRef, filename: $filename,
                                mediaType: $mediaType, sizeBytes: $size, parserVersion: $parserVersion,
                                parseStatus: $parseStatus, sectionCount: $sectionCount, recordedSeq: $seq,
                                recordedAt: $at})""", params("artifactId", artifactId, "id", versionId, "key", in.key(),
                        "hash", in.contentHash(), "blobRef", in.blobRef(), "filename", in.filename(),
                        "mediaType", in.mediaType(), "size", in.sizeBytes(), "parserVersion", in.parserVersion(),
                        "parseStatus", in.parseStatus(), "sectionCount", in.sections().size(), "seq", seq, "at", ctx.now()));
                writeSections(ctx, in, versionId);
                pointCurrent(ctx, artifactId, versionId);
                return new SourceResult(artifactId, in.key(), versionId, in.contentHash(), Outcome.CREATED, seq);
            }
            if (!in.parserVersion().equals(version.get("parserVersion").asString(null))) {
                Long seq = emit(ctx, in.key(), ChangeEventTypes.SECTIONS_REPARSED, Map.of("version", in.contentHash(),
                        "parserVersion", in.parserVersion(), "sections", in.sections().size()));
                ctx.run("MATCH (:SourceVersion {pke_id: $id})-[:HAS_SECTION]->(s:Section) DETACH DELETE s",
                        Map.of("id", versionId));
                ctx.run("""
                        MATCH (v:SourceVersion {pke_id: $id})
                        SET v.parserVersion = $parserVersion, v.parseStatus = $parseStatus, v.sectionCount = $count""",
                        params("id", versionId, "parserVersion", in.parserVersion(), "parseStatus", in.parseStatus(),
                                "count", in.sections().size()));
                writeSections(ctx, in, versionId);
                pointCurrent(ctx, artifactId, versionId);
                return new SourceResult(artifactId, in.key(), versionId, in.contentHash(), Outcome.REPARSED, seq);
            }
            boolean current = versionId.equals(version.get("currentId").asString(null));
            if (current && artifactOutcome == Outcome.UNCHANGED) {
                return new SourceResult(artifactId, in.key(), versionId, in.contentHash(), Outcome.UNCHANGED, null);
            }
            Long seq = emit(ctx, in.key(), ChangeEventTypes.DOCUMENT_VERSION_ADDED,
                    Map.of("version", in.contentHash(), "reinstated", true));
            pointCurrent(ctx, artifactId, versionId);
            return new SourceResult(artifactId, in.key(), versionId, in.contentHash(), Outcome.REINSTATED, seq);
        });
    }

    /** Records commits of a repository (oldest first) and points the repository at the last one. */
    public SourceResult recordCommits(String key, List<CommitInput> commits, String actor) {
        if (commits.isEmpty()) {
            throw new IllegalArgumentException("no commits to record");
        }
        return transactions.write(actor, PkeIds.uuidV7().toString(), ctx -> {
            String artifactId = PkeIds.sourceArtifactId(key);
            Record artifact = ctx.single("""
                    MATCH (a:SourceArtifact {pke_id: $id})
                    OPTIONAL MATCH (a)-[:CURRENT_VERSION]->(current)
                    RETURN current.version AS current""", Map.of("id", artifactId))
                    .orElseThrow(() -> new NotFoundException("Source " + key + " is not registered"));
            List<String> known = ctx.run("""
                    UNWIND $ids AS id MATCH (v:SourceVersion {pke_id: id}) RETURN v.version AS sha""",
                    Map.of("ids", commits.stream().map(c -> PkeIds.sourceVersionId(key, c.sha())).toList()))
                    .stream().map(r -> r.get("sha").asString()).toList();
            List<CommitInput> fresh = commits.stream().filter(c -> !known.contains(c.sha())).toList();
            String head = commits.getLast().sha();
            if (fresh.isEmpty() && head.equals(artifact.get("current").asString(null))) {
                return new SourceResult(artifactId, key, PkeIds.sourceVersionId(key, head), head, Outcome.UNCHANGED, null);
            }
            Long seq = emit(ctx, key, ChangeEventTypes.COMMITS_RECORDED, Map.of("commits",
                    fresh.stream().map(CommitInput::sha).toList(), "head", head));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CommitInput c : fresh) {
                Map<String, Object> row = new HashMap<>();
                row.put("pke_id", PkeIds.sourceVersionId(key, c.sha()));
                row.put("artifactKey", key);
                row.put("kind", "commit");
                row.put("version", c.sha());
                row.put("parents", c.parents());
                row.put("authoredAt", c.authoredAt());
                row.put("changedFiles", c.changedFiles());
                row.put("renames", c.renames().entrySet().stream().map(e -> e.getKey() + " -> " + e.getValue()).toList());
                row.put("recordedSeq", seq);
                row.put("recordedAt", ctx.now());
                rows.add(row);
            }
            ctx.run("""
                    MATCH (a:SourceArtifact {pke_id: $artifactId})
                    UNWIND $rows AS row
                    CREATE (a)-[:HAS_VERSION]->(v:SourceVersion) SET v = row""", Map.of("artifactId", artifactId, "rows", rows));
            pointCurrent(ctx, artifactId, PkeIds.sourceVersionId(key, head));
            return new SourceResult(artifactId, key, PkeIds.sourceVersionId(key, head), head, Outcome.UPDATED, seq);
        });
    }

    /** Marks a source as removed: it has no current version any more; versions, sections and evidence are kept. */
    public SourceResult remove(String key, String reason, String actor) {
        return transactions.write(actor, PkeIds.uuidV7().toString(), ctx -> {
            String artifactId = PkeIds.sourceArtifactId(key);
            Record artifact = ctx.single("MATCH (a:SourceArtifact {pke_id: $id}) RETURN a.status AS status",
                    Map.of("id", artifactId)).orElseThrow(() -> new NotFoundException("Source " + key + " is not registered"));
            if ("REMOVED".equals(artifact.get("status").asString(null))) {
                return new SourceResult(artifactId, key, null, null, Outcome.UNCHANGED, null);
            }
            Long seq = emit(ctx, key, ChangeEventTypes.SOURCE_REMOVED, reason == null ? Map.of() : Map.of("reason", reason));
            ctx.run("""
                    MATCH (a:SourceArtifact {pke_id: $id})
                    SET a.status = 'REMOVED', a.removedSeq = $seq
                    WITH a
                    OPTIONAL MATCH (a)-[c:CURRENT_VERSION]->()
                    DELETE c""", Map.of("id", artifactId, "seq", seq));
            return new SourceResult(artifactId, key, null, null, Outcome.REMOVED, seq);
        });
    }

    private Outcome upsertArtifact(WriteContext ctx, String key, String kind, Map<String, Object> config) {
        String id = PkeIds.sourceArtifactId(key);
        Record existing = ctx.single("MATCH (a:SourceArtifact {pke_id: $id}) RETURN properties(a) AS props, a.status AS status",
                Map.of("id", id)).orElse(null);
        if (existing == null) {
            Map<String, Object> props = new HashMap<>(config);
            props.put("pke_id", id);
            props.put("key", key);
            props.put("kind", kind);
            props.put("status", "ACTIVE");
            props.put("createdAt", ctx.now());
            ctx.run("CREATE (a:SourceArtifact) SET a = $props", Map.of("props", props));
            return Outcome.CREATED;
        }
        Map<String, Object> current = existing.get("props").asMap();
        boolean changed = !"ACTIVE".equals(existing.get("status").asString(null))
                || config.entrySet().stream().anyMatch(e -> !java.util.Objects.equals(current.get(e.getKey()), e.getValue()));
        if (!changed) {
            return Outcome.UNCHANGED;
        }
        Map<String, Object> props = new HashMap<>(config);
        props.put("status", "ACTIVE");
        ctx.run("MATCH (a:SourceArtifact {pke_id: $id}) SET a += $props", Map.of("id", id, "props", props));
        return Outcome.UPDATED;
    }

    private static void pointCurrent(WriteContext ctx, String artifactId, String versionId) {
        ctx.run("""
                MATCH (a:SourceArtifact {pke_id: $artifactId}), (v:SourceVersion {pke_id: $versionId})
                OPTIONAL MATCH (a)-[c:CURRENT_VERSION]->()
                DELETE c
                WITH DISTINCT a, v
                CREATE (a)-[:CURRENT_VERSION]->(v)
                SET a.currentVersion = v.version, a.status = 'ACTIVE'""",
                Map.of("artifactId", artifactId, "versionId", versionId));
    }

    private static void writeSections(WriteContext ctx, DocumentVersionInput in, String versionId) {
        if (in.sections().isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = in.sections().stream().map(s -> {
            Map<String, Object> row = new HashMap<>();
            row.put("pke_id", PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, versionId + "#" + s.order()).toString());
            row.put("artifactKey", in.key());
            row.put("version", in.contentHash());
            row.put("order", s.order());
            row.put("headingPath", s.headingPath());
            row.put("heading", s.heading());
            row.put("level", s.level());
            row.put("page", s.page());
            row.put("text", s.text());
            row.put("hash", s.hash());
            row.put("parserVersion", in.parserVersion());
            return row;
        }).toList();
        ctx.run("""
                MATCH (v:SourceVersion {pke_id: $versionId})
                UNWIND $rows AS row
                CREATE (v)-[:HAS_SECTION]->(s:Section) SET s = row""", Map.of("versionId", versionId, "rows", rows));
    }

    private static Long emit(WriteContext ctx, String key, String type, Map<String, Object> details) {
        long seq = ctx.nextSeq();
        ctx.emit(new PendingEvent(seq, type, SubjectKind.SOURCE, PkeIds.sourceArtifactId(key), key, null, details));
        return seq;
    }
}
