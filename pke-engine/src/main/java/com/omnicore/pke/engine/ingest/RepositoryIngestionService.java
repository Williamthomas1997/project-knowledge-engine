package com.omnicore.pke.engine.ingest;

import com.omnicore.pke.engine.extract.ErrorConfigScanner;
import com.omnicore.pke.engine.extract.ExtractedKnowledge;
import com.omnicore.pke.engine.extract.java.JavaSourceExtractor;
import com.omnicore.pke.engine.graph.CodeQueryService;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.ScopeRetraction;
import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SourceView;
import com.omnicore.pke.engine.graph.SourceWriter;
import com.omnicore.pke.engine.graph.SourceWriter.CommitInput;
import com.omnicore.pke.engine.ingest.KnowledgeBatchWriter.Scoped;
import com.omnicore.pke.engine.ingest.KnowledgeBatchWriter.Stats;
import com.omnicore.pke.engine.ingest.journey.JourneyKnowledgeMapper;
import com.omnicore.pke.engine.ingest.journey.TestSuiteReader;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.source.GitRepository;
import com.omnicore.pke.spi.journey.JourneyContext;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import com.omnicore.pke.spi.journey.JourneySourceAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deterministic ingestion of one Git repository (no LLM). Reads committed content only (the commit is exported to
 * a temporary directory), so the working tree never influences results.
 * <ul>
 *   <li><b>full</b>: every Java file, every error-config script, every journey source;</li>
 *   <li><b>incremental</b>: since the last processed commit, changed Java files plus files whose relationships point
 *       into them (reverse dependencies); deleted and renamed-away files are retracted; renames keep {@code pke_id}.
 *       Journeys and suites are always re-read in full (cheap, and their meaning depends on many files).</li>
 * </ul>
 */
@Service
public class RepositoryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIngestionService.class);
    private static final Set<String> EXPORTED = Set.of(".java", ".sql", ".json", ".jmx", ".xml");

    private final SourceWriter sources;
    private final SourceQueryService sourceQueries;
    private final CodeQueryService codeQueries;
    private final KnowledgeBatchWriter batchWriter;
    private final List<JourneySourceAdapter> adapters;
    private final IngestionProperties properties;

    public RepositoryIngestionService(SourceWriter sources, SourceQueryService sourceQueries, CodeQueryService codeQueries,
                                      KnowledgeBatchWriter batchWriter, List<JourneySourceAdapter> adapters,
                                      IngestionProperties properties) {
        this.sources = sources;
        this.sourceQueries = sourceQueries;
        this.codeQueries = codeQueries;
        this.batchWriter = batchWriter;
        this.adapters = adapters;
        this.properties = properties;
    }

    /** What a repository run did; persisted on the run as JSON. */
    public record Report(String fromCommit, String toCommit, String mode, int filesExtracted, Stats stats,
                         List<String> unresolved, List<String> parseProblems, Map<String, Integer> journeys) {
    }

    public SourceWriter.SourceResult register(String name, String path, String serviceName, String actor) {
        if (name == null || !name.matches("^[a-z0-9][a-z0-9._-]{0,99}$")) {
            throw ValidationFailedException.of("/name", "must be a lower-case repository name, e.g. transfer-service");
        }
        Path dir = Path.of(path == null ? "" : path).toAbsolutePath().normalize();
        try (GitRepository git = GitRepository.open(dir)) {
            git.head();
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of("/path", e.getMessage());
        }
        return sources.registerRepository(key(name), name, dir.toString(), serviceName, actor);
    }

    public static String key(String name) {
        return "repo:" + name;
    }

    /** Runs one ingestion of the registered repository {@code key}. {@code from} null means full. */
    public Report ingest(String key, String from, String runId, String actor) {
        SourceView repo = sourceQueries.byKey(key);
        String name = (String) repo.properties().get("name");
        String serviceName = (String) repo.properties().get("serviceName");
        Path path = Path.of((String) repo.properties().get("path"));
        Path work = tempDir();
        try (GitRepository git = GitRepository.open(path)) {
            String head = git.head();
            boolean incremental = from != null && git.resolve(from).isPresent();
            if (incremental && from.equals(head)) {
                return new Report(from, head, "incremental", 0, Stats.empty(), List.of(), List.of(), Map.of());
            }
            recordCommits(git, key, incremental ? from : null, head, actor);
            List<String> exported = git.export(head, work, p -> EXPORTED.stream().anyMatch(p::endsWith)
                    && !p.contains("/target/") && !p.startsWith("target/"), properties.maxFileBytes());
            List<String> javaFiles = exported.stream().filter(p -> p.endsWith(".java")).toList();

            JavaSourceExtractor extractor = new JavaSourceExtractor(work, javaFiles, new JavaSourceExtractor.Settings(
                    name, key, head, serviceName, Pattern.compile(properties.errorCodePattern()),
                    Set.copyOf(properties.loggerNames())));

            Set<String> targets = new TreeSet<>();
            List<String> gone = new ArrayList<>();
            Map<String, String> renames = Map.of();
            GitRepository.Changes changes = null;
            if (incremental) {
                changes = git.changes(from, head);
                renames = changes.renamed();
                targets.addAll(changes.touched());
                gone.addAll(changes.deleted());
                gone.addAll(changes.renamed().keySet());
                List<String> changedJava = new ArrayList<>(targets.stream().filter(p -> p.endsWith(".java")).toList());
                gone.stream().filter(p -> p.endsWith(".java")).forEach(changedJava::add);
                targets.addAll(codeQueries.dependentScopes(name, key, changedJava));
            } else {
                targets.addAll(exported);
            }

            List<Scoped> scoped = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            int extracted = 0;
            for (String file : targets) {
                if (file.endsWith(".java") && extractor.files().contains(file)) {
                    ExtractedKnowledge k = extractor.extract(file);
                    scoped.add(new Scoped(k, key, JavaSourceExtractor.METHOD));
                    unresolved.addAll(k.unresolved());
                    extracted++;
                } else if (file.endsWith(".sql") && exported.contains(file)) {
                    ExtractedKnowledge k = new ErrorConfigScanner(properties.errorConfigTable(),
                            properties.errorConfigCodeColumn(), properties.errorConfigDescriptionColumn())
                            .scan(file, read(work, file), key, head);
                    scoped.add(new Scoped(k, key, ErrorConfigScanner.METHOD));
                    extracted++;
                }
            }
            if (incremental) {
                detectRenames(git, from, renames, changes.modified(), extractor, scoped, name);
            }

            Map<String, Integer> journeyCounts = new LinkedHashMap<>();
            List<String> journeyFiles = new ArrayList<>();
            scoped.addAll(journeys(work, exported, key, head, name, unresolved, journeyCounts, journeyFiles));

            List<ScopeRetraction> retractions = new ArrayList<>();
            for (String file : gone) {
                for (String method : List.of(JavaSourceExtractor.METHOD, ErrorConfigScanner.METHOD, "journey.postman",
                        "journey.java-journey", "journey.gatling", "journey.jmeter", TestSuiteReader.METHOD)) {
                    retractions.add(new ScopeRetraction(key, file, method, file + " was deleted or renamed"));
                }
            }
            Stats stats = batchWriter.write(scoped, retractions, runId, actor, properties.batchSize());
            unresolved.sort(Comparator.naturalOrder());
            log.info("Ingested {} at {}: {} files, outcomes {}, {} failures, {} unresolved", key, head.substring(0, 8),
                    extracted, stats.outcomes(), stats.failures().size(), unresolved.size());
            return new Report(incremental ? from : null, head, incremental ? "incremental" : "full", extracted, stats,
                    unresolved, extractor.parseProblems(), journeyCounts);
        } finally {
            delete(work);
        }
    }

    private void recordCommits(GitRepository git, String key, String from, String head, String actor) {
        List<GitRepository.Commit> commits;
        if (from == null) {
            commits = git.commits(null, head);
            commits = List.of(commits.getLast());
        } else {
            commits = git.commits(from, head);
            if (commits.size() > properties.maxCommits()) {
                commits = commits.subList(commits.size() - properties.maxCommits(), commits.size());
            }
        }
        List<CommitInput> inputs = commits.stream().map(c -> new CommitInput(c.sha(), c.parents(),
                c.authoredAt().toString(), from == null ? List.of() : c.changes().touched(), c.changes().renamed())).toList();
        sources.recordCommits(key, inputs, actor);
    }

    /**
     * Renamed files keep their types' and methods' identity (old fully qualified name to new); within a modified
     * file, a method whose signature changed but whose body is identical keeps its identity too (ADR-002).
     */
    private void detectRenames(GitRepository git, String from, Map<String, String> renames, List<String> modified,
                               JavaSourceExtractor current, List<Scoped> scoped, String repository) {
        Map<String, ExtractedKnowledge> byScope = new LinkedHashMap<>();
        scoped.forEach(s -> byScope.put(s.knowledge().scope(), s.knowledge()));
        renames.forEach((oldPath, newPath) -> {
            ExtractedKnowledge k = byScope.get(newPath);
            Optional<JavaSourceExtractor> previous = previousVersion(git, from, oldPath);
            if (k == null || previous.isEmpty()) {
                return;
            }
            List<String> oldTypes = previous.get().typesIn(oldPath);
            List<String> newTypes = current.typesIn(newPath);
            if (oldTypes.size() != newTypes.size()) {
                return;
            }
            Map<String, Map<String, String>> oldMethods = previous.get().methodHashes(oldPath);
            Map<String, Map<String, String>> newMethods = current.methodHashes(newPath);
            for (int i = 0; i < newTypes.size(); i++) {
                String oldFqcn = oldTypes.get(i);
                String newFqcn = newTypes.get(i);
                if (!oldFqcn.equals(newFqcn)) {
                    k.markRenamed("JavaClass", Map.of("repository", repository, "fqcn", newFqcn),
                            EntityRef.key("JavaClass", Map.of("repository", repository, "fqcn", oldFqcn)));
                }
                Map<String, String> before = oldMethods.getOrDefault(oldFqcn, Map.of());
                for (String signature : newMethods.getOrDefault(newFqcn, Map.of()).keySet()) {
                    if (before.containsKey(signature) && !oldFqcn.equals(newFqcn)) {
                        k.markRenamed("JavaMethod", Map.of("repository", repository, "fqcn", newFqcn, "signature", signature),
                                EntityRef.key("JavaMethod", Map.of("repository", repository, "fqcn", oldFqcn, "signature", signature)));
                    }
                }
                renamedMethods(k, repository, newFqcn, oldFqcn, before, newMethods.getOrDefault(newFqcn, Map.of()));
            }
        });
        for (String path : modified) {
            ExtractedKnowledge k = byScope.get(path);
            Optional<JavaSourceExtractor> previous = path.endsWith(".java") ? previousVersion(git, from, path) : Optional.empty();
            if (k == null || previous.isEmpty()) {
                continue;
            }
            Map<String, Map<String, String>> oldMethods = previous.get().methodHashes(path);
            current.methodHashes(path).forEach((fqcn, methods) ->
                    renamedMethods(k, repository, fqcn, fqcn, oldMethods.getOrDefault(fqcn, Map.of()), methods));
        }
    }

    /** Pairs a removed and an added signature when exactly one removed method has the added method's body. */
    private static void renamedMethods(ExtractedKnowledge k, String repository, String newFqcn, String oldFqcn,
                                       Map<String, String> before, Map<String, String> after) {
        Set<String> removed = new LinkedHashSet<>(before.keySet());
        removed.removeAll(after.keySet());
        Set<String> added = new LinkedHashSet<>(after.keySet());
        added.removeAll(before.keySet());
        for (String signature : added) {
            String hash = after.get(signature);
            List<String> sameBody = removed.stream().filter(r -> hash != null && hash.equals(before.get(r))).toList();
            if (sameBody.size() == 1) {
                k.markRenamed("JavaMethod", Map.of("repository", repository, "fqcn", newFqcn, "signature", signature),
                        EntityRef.key("JavaMethod", Map.of("repository", repository, "fqcn", oldFqcn, "signature", sameBody.getFirst())));
            }
        }
    }

    private Optional<JavaSourceExtractor> previousVersion(GitRepository git, String commit, String path) {
        Optional<String> content = git.read(commit, path);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        Path dir = tempDir();
        try {
            Path file = dir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content.get(), StandardCharsets.UTF_8);
            return Optional.of(new JavaSourceExtractor(dir, List.of(path), new JavaSourceExtractor.Settings("previous",
                    "previous", commit, null, Pattern.compile(properties.errorCodePattern()), Set.of())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(dir);
        }
    }

    private List<Scoped> journeys(Path work, List<String> exported, String key, String head, String repository,
                                  List<String> unresolved, Map<String, Integer> counts, List<String> journeyFiles) {
        JourneyContext context = new JourneyContext(work, properties.identityHeaders(),
                new LinkedHashSet<>(properties.serviceHeaders()), properties.javaJourneyPatterns());
        List<JourneyDefinition> journeys = new ArrayList<>();
        for (String file : exported) {
            for (JourneySourceAdapter adapter : adapters) {
                if (adapter.supports(context, Path.of(file))) {
                    try {
                        List<JourneyDefinition> read = adapter.read(context, Path.of(file));
                        journeys.addAll(read);
                        journeyFiles.add(file);
                        read.forEach(j -> counts.merge(j.sourceKind(), 1, Integer::sum));
                    } catch (RuntimeException e) {
                        unresolved.add(file + ": " + adapter.id() + " failed: " + e.getMessage());
                    }
                }
            }
        }
        List<Scoped> scoped = new ArrayList<>();
        JourneyKnowledgeMapper mapper = new JourneyKnowledgeMapper(journeys, properties.identityHeaders(),
                journeys.isEmpty() ? List.of() : codeQueries.gatewayApiProperties());
        Map<String, ExtractedKnowledge> byScope = new LinkedHashMap<>();
        for (JourneyDefinition journey : journeys) {
            ExtractedKnowledge k = mapper.map(journey, key, head);
            if (byScope.containsKey(journey.sourcePath() + "|" + journey.sourceKind())) {
                continue;
            }
            byScope.put(journey.sourcePath() + "|" + journey.sourceKind(), k);
            scoped.add(new Scoped(k, key, JourneyKnowledgeMapper.METHOD_PREFIX + journey.sourceKind()));
            unresolved.addAll(k.unresolved());
        }
        Set<String> seenSuites = new HashSet<>();
        for (String file : exported) {
            if (file.endsWith(".xml") && file.contains("src/test/resources")) {
                TestSuiteReader.read(work, file).filter(s -> seenSuites.add(s.name())).ifPresent(suite -> {
                    scoped.add(new Scoped(TestSuiteReader.map(suite, journeys, repository, key, head), key, TestSuiteReader.METHOD));
                    counts.merge("testng-suite", 1, Integer::sum);
                });
            }
        }
        return scoped;
    }

    private static String read(Path root, String file) {
        try {
            return Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                return Files.readString(root.resolve(file), StandardCharsets.ISO_8859_1);
            } catch (IOException again) {
                throw new UncheckedIOException(again);
            }
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("pke-ingest-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", dir, e.getMessage());
        }
    }
}
