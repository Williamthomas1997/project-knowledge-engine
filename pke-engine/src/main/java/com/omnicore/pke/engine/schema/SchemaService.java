package com.omnicore.pke.engine.schema;

import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.schema.DefinitionSource.SourceFile;
import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;
import com.omnicore.pke.engine.schema.SchemaDefinitionStore.StoredDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application service for the schema registry: validation, registration (REST and Git definitions) and lookups.
 * Promotion rules: a name + version is immutable; a new version must be higher than the current one and
 * backwards compatible ({@link SchemaCompatibility}); relationship endpoints must be registered entity types.
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private final SchemaDefinitionParser parser;
    private final SchemaRegistry registry;
    private final SchemaDefinitionStore store;
    private final DefinitionSource source;

    public SchemaService(SchemaDefinitionParser parser, SchemaRegistry registry, SchemaDefinitionStore store,
                         DefinitionSource source) {
        this.parser = parser;
        this.registry = registry;
        this.store = store;
        this.source = source;
    }

    public enum Outcome { REGISTERED, UNCHANGED, SKIPPED_OLDER }

    public record RegistrationResult(String kind, String name, String version, Outcome outcome, Long seq) {
    }

    public record BootstrapReport(List<RegistrationResult> results) {
        public long count(Outcome outcome) {
            return results.stream().filter(r -> r.outcome() == outcome).count();
        }
    }

    public record ValidationReport(boolean valid, String kind, String name, String version, String hash,
                                   List<Violation> violations, List<String> breakingChanges, String currentVersion) {
    }

    public record DefinitionView(String kind, String name, String version, String status, String hash,
                                 Map<String, Object> definition) {
    }

    public record VersionView(String version, String status, String hash, long registeredSeq, boolean current) {
    }

    /** Loads the current definitions from Neo4j, then registers any new ones from the Git definitions folder. */
    public synchronized BootstrapReport bootstrap() {
        reload();
        List<DefinitionDocument> documents = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (SourceFile file : source.schemaFiles()) {
            try {
                documents.add(parser.parse(file.text(), file.format()));
            } catch (ValidationFailedException e) {
                failures.add(file.location() + ": " + e.getMessage());
            }
        }
        documents.sort(Comparator.comparing((DefinitionDocument d) -> d.kind().ordinal()).thenComparing(DefinitionDocument::name));
        List<RegistrationResult> results = new ArrayList<>();
        for (DefinitionDocument document : documents) {
            try {
                results.add(registerDocument(document, "bootstrap", true));
            } catch (ValidationFailedException | ConflictException e) {
                failures.add(document.key() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Schema definitions could not be registered:\n  " + String.join("\n  ", failures));
        }
        BootstrapReport report = new BootstrapReport(results);
        log.info("Schema bootstrap: {} registered, {} unchanged, {} older than current",
                report.count(Outcome.REGISTERED), report.count(Outcome.UNCHANGED), report.count(Outcome.SKIPPED_OLDER));
        return report;
    }

    /** Replaces the in-memory registry with the current definitions stored in Neo4j. */
    public synchronized void reload() {
        registry.clear();
        for (StoredDefinition stored : store.loadCurrent()) {
            registry.install(parser.parse(stored.definitionJson(), Format.JSON));
        }
    }

    public ValidationReport validate(String text, Format format) {
        DefinitionDocument document;
        try {
            document = parser.parse(text, format);
        } catch (ValidationFailedException e) {
            return new ValidationReport(false, null, null, null, null, e.violations(), List.of(), null);
        }
        List<Violation> violations = registryViolations(document);
        Optional<DefinitionDocument> current = registry.current(document.kind(), document.name());
        List<String> breaking = current
                .map(c -> SchemaCompatibility.breakingChanges(c.definition(), document.definition()))
                .orElse(List.of());
        return new ValidationReport(violations.isEmpty() && breaking.isEmpty(), document.kind().json(), document.name(),
                document.version(), document.hash(), violations, breaking, current.map(DefinitionDocument::version).orElse(null));
    }

    public synchronized RegistrationResult register(String text, Format format, String actor) {
        return registerDocument(parser.parse(text, format), actor, false);
    }

    /** Registers a definition whose kind must match {@code expected} (used by kind-specific REST endpoints). */
    public synchronized RegistrationResult register(String text, Format format, String actor, DefinitionKind expected) {
        DefinitionDocument document = parser.parse(text, format);
        if (document.kind() != expected) {
            throw ValidationFailedException.of("/kind", "expected " + expected.json() + " but got " + document.kind().json());
        }
        return registerDocument(document, actor, false);
    }

    public List<DefinitionView> list(DefinitionKind kind) {
        return registry.all(kind).stream().map(SchemaService::view).toList();
    }

    public DefinitionView get(DefinitionKind kind, String name) {
        return registry.current(kind, name).map(SchemaService::view)
                .orElseThrow(() -> new NotFoundException(kind.json() + " '" + name + "' is not registered"));
    }

    public List<VersionView> history(DefinitionKind kind, String name) {
        List<VersionView> versions = store.history(kind, name).stream()
                .map(s -> new VersionView(s.version(), s.status(), s.hash(), s.registeredSeq(), s.current()))
                .toList();
        if (versions.isEmpty()) {
            throw new NotFoundException(kind.json() + " '" + name + "' is not registered");
        }
        return versions;
    }

    private RegistrationResult registerDocument(DefinitionDocument document, String actor, boolean fromGit) {
        List<Violation> violations = registryViolations(document);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Invalid " + document.kind().json() + " definition", violations);
        }
        Optional<DefinitionDocument> current = registry.current(document.kind(), document.name());
        if (current.isPresent()) {
            DefinitionDocument existing = current.get();
            int cmp = SchemaCompatibility.compareVersions(document.version(), existing.version());
            if (cmp == 0) {
                if (existing.hash().equals(document.hash())) {
                    return result(document, Outcome.UNCHANGED, null);
                }
                throw new ConflictException(document.key() + " is already registered with different content. "
                        + "Versions are immutable: bump the version.");
            }
            if (cmp < 0) {
                if (fromGit) {
                    log.info("{} is older than the current version {}; keeping the current one", document.key(), existing.version());
                    return result(document, Outcome.SKIPPED_OLDER, null);
                }
                throw new ConflictException(document.key() + " is older than the current version " + existing.version());
            }
            List<String> breaking = SchemaCompatibility.breakingChanges(existing.definition(), document.definition());
            if (!breaking.isEmpty()) {
                throw new ConflictException(document.key() + " is not backwards compatible with " + existing.version()
                        + " and needs a migration, which is not supported yet: " + String.join("; ", breaking));
            }
        }
        SchemaDefinitionStore.Registration registration = store.register(document, actor);
        registry.install(document);
        return result(document, registration.registered() ? Outcome.REGISTERED : Outcome.UNCHANGED, registration.seq());
    }

    private List<Violation> registryViolations(DefinitionDocument document) {
        List<Violation> violations = new ArrayList<>();
        if (document.definition() instanceof RelationshipTypeDefinition rel) {
            checkEndpoints(rel.sourceTypes(), "/sourceTypes", violations);
            checkEndpoints(rel.targetTypes(), "/targetTypes", violations);
        }
        return violations;
    }

    private void checkEndpoints(List<String> types, String path, List<Violation> violations) {
        for (String type : types) {
            if (!RelationshipTypeDefinition.ANY_TYPE.equals(type) && registry.entityType(type).isEmpty()) {
                violations.add(new Violation(path, "'" + type + "' is not a registered entity type"));
            }
        }
    }

    private static RegistrationResult result(DefinitionDocument document, Outcome outcome, Long seq) {
        return new RegistrationResult(document.kind().json(), document.name(), document.version(), outcome, seq);
    }

    private static DefinitionView view(DefinitionDocument document) {
        return new DefinitionView(document.kind().json(), document.name(), document.version(),
                document.definition().status().name(), document.hash(), document.raw());
    }
}
