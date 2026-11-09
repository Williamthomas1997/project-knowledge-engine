package com.omnicore.pke.engine.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;

/**
 * The synthetic corpus under {@code demo/}. Repositories are copied to a temporary directory and turned into real
 * Git repositories, so tests exercise JGit, commits, diffs and renames.
 */
public final class DemoCorpus {

    private DemoCorpus() {
    }

    public static Path root() {
        for (Path candidate : List.of(Path.of("..", "demo"), Path.of("demo"))) {
            if (Files.isDirectory(candidate.resolve("transfer-service"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("demo/ corpus not found from " + Path.of("").toAbsolutePath());
    }

    public static Path file(String relative) {
        return root().resolve(relative);
    }

    /** A Git repository with the content of {@code demo/<name>} as its first commit. */
    public static DemoRepo repository(String name) {
        try {
            Path dir = Files.createTempDirectory("pke-demo-" + name + "-");
            copy(root().resolve(name), dir);
            Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
            DemoRepo repo = new DemoRepo(dir, git);
            repo.commit("c1: initial import");
            return repo;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }

    public record DemoRepo(Path dir, Git git) {

        /** Overlays {@code demo/<changes>} (a {@code DELETE} file lists paths to remove in its folder) and commits. */
        public String applyAndCommit(String changes, String message) {
            Path overlay = root().resolve(changes);
            try (Stream<Path> paths = Files.walk(overlay)) {
                for (Path source : paths.filter(Files::isRegularFile).toList()) {
                    Path relative = overlay.relativize(source);
                    if (source.getFileName().toString().equals("DELETE")) {
                        for (String line : Files.readAllLines(source)) {
                            if (!line.isBlank()) {
                                Files.deleteIfExists(dir.resolve(relative).resolveSibling(line.strip()));
                            }
                        }
                        continue;
                    }
                    Path target = dir.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return commit(message);
        }

        public String head() {
            try {
                return git.getRepository().resolve("HEAD").getName();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String commit(String message) {
            try {
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                PersonIdent who = new PersonIdent("Demo", "demo@example.invalid");
                return git.commit().setMessage(message).setAuthor(who).setCommitter(who).call().getName();
            } catch (GitAPIException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path source : paths.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
