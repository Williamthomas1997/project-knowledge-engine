package com.omnicore.pke.engine.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Read-only access to a local Git repository (JGit). Analysis always reads committed content (never the working
 * tree), so a dirty checkout cannot change what the engine extracts for a commit.
 */
public final class GitRepository implements AutoCloseable {

    /** Files that changed between two commits; renames map old path to new path. */
    public record Changes(List<String> added, List<String> modified, List<String> deleted, Map<String, String> renamed) {

        public List<String> touched() {
            TreeSet<String> all = new TreeSet<>(added);
            all.addAll(modified);
            all.addAll(renamed.values());
            return List.copyOf(all);
        }
    }

    public record Commit(String sha, List<String> parents, Instant authoredAt, Changes changes) {
    }

    private final Repository repository;

    private GitRepository(Repository repository) {
        this.repository = repository;
    }

    public static GitRepository open(Path path) {
        try {
            Repository repository = new FileRepositoryBuilder().findGitDir(path.toFile()).setMustExist(true).build();
            if (repository.resolve("HEAD") == null) {
                repository.close();
                throw new IllegalArgumentException(path + " has no commits");
            }
            return new GitRepository(repository);
        } catch (IOException e) {
            throw new IllegalArgumentException(path + " is not a Git repository: " + e.getMessage(), e);
        }
    }

    public String head() {
        return resolve("HEAD").orElseThrow();
    }

    public Optional<String> resolve(String revision) {
        try {
            ObjectId id = repository.resolve(revision + "^{commit}");
            return Optional.ofNullable(id).map(ObjectId::getName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Commits reachable from {@code to} but not from {@code from} (all when from is null), oldest first. */
    public List<Commit> commits(String from, String to) {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.REVERSE, true);
            walk.markStart(walk.parseCommit(ObjectId.fromString(to)));
            if (from != null) {
                walk.markUninteresting(walk.parseCommit(ObjectId.fromString(from)));
            }
            List<Commit> commits = new ArrayList<>();
            for (RevCommit commit : walk) {
                String parent = commit.getParentCount() > 0 ? commit.getParent(0).getName() : null;
                List<String> parents = new ArrayList<>();
                for (RevCommit p : commit.getParents()) {
                    parents.add(p.getName());
                }
                commits.add(new Commit(commit.getName(), parents,
                        Instant.ofEpochSecond(commit.getCommitTime()), changes(parent, commit.getName())));
            }
            return commits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Changes between two commits with rename detection ({@code from == null} means "from the empty tree"). */
    public Changes changes(String from, String to) {
        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            List<DiffEntry> entries = formatter.scan(
                    from == null ? new EmptyTreeIterator() : treeParser(reader, from), treeParser(reader, to));
            List<String> added = new ArrayList<>();
            List<String> modified = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            Map<String, String> renamed = new LinkedHashMap<>();
            for (DiffEntry entry : entries) {
                switch (entry.getChangeType()) {
                    case ADD, COPY -> added.add(entry.getNewPath());
                    case MODIFY -> modified.add(entry.getNewPath());
                    case DELETE -> deleted.add(entry.getOldPath());
                    case RENAME -> renamed.put(entry.getOldPath(), entry.getNewPath());
                }
            }
            return new Changes(added, modified, deleted, renamed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the files of {@code commit} that match {@code include} and are at most {@code maxBytes} below
     * {@code target}. Returns their paths.
     */
    public List<String> export(String commit, Path target, Predicate<String> include, long maxBytes) {
        List<String> written = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(ObjectId.fromString(commit)).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (!include.test(path)) {
                    continue;
                }
                Path file = target.resolve(path).normalize();
                if (!file.startsWith(target) || repository.open(tree.getObjectId(0)).getSize() > maxBytes) {
                    continue;
                }
                Files.createDirectories(file.getParent());
                Files.write(file, repository.open(tree.getObjectId(0)).getBytes());
                written.add(path);
            }
            Collections.sort(written);
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Content of one file at a commit, if it exists there. */
    public Optional<String> read(String commit, String path) {
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = TreeWalk.forPath(repository, path, walk.parseCommit(ObjectId.fromString(commit)).getTree())) {
            if (tree == null) {
                return Optional.empty();
            }
            return Optional.of(new String(repository.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CanonicalTreeParser treeParser(ObjectReader reader, String commit) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, walk.parseCommit(ObjectId.fromString(commit)).getTree());
            return parser;
        }
    }

    @Override
    public void close() {
        repository.close();
    }
}
