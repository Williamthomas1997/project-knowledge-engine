package com.omnicore.pke.engine.pipeline;

import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SourceView;
import com.omnicore.pke.engine.source.GitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The current version of a source, for idempotency keys: a document's current content hash, or a repository's
 * HEAD commit (read from Git, so new commits make a new key before they are ingested).
 */
@Component
public class SourceVersionResolver {

    private final SourceQueryService sources;

    public SourceVersionResolver(SourceQueryService sources) {
        this.sources = sources;
    }

    public String currentVersion(String sourceKey) {
        Optional<SourceView> source = sources.findByKey(sourceKey);
        if (source.isEmpty()) {
            return "missing";
        }
        Object path = source.get().properties().get("path");
        if ("repository".equals(source.get().kind()) && path != null && Files.isDirectory(Path.of(path.toString()))) {
            try (GitRepository git = GitRepository.open(Path.of(path.toString()))) {
                return git.head();
            } catch (RuntimeException e) {
                return "unreadable";
            }
        }
        String version = source.get().currentVersion();
        return version == null ? source.get().status() : version;
    }
}
