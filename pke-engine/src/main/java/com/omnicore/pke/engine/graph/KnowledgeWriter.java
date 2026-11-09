package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.graph.GraphTransactions.WriteContext;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ItemOutcome;
import com.omnicore.pke.engine.graph.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ScopeRetraction;
import com.omnicore.pke.engine.graph.PendingEvent.SubjectKind;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ChangeEventTypes;
import com.omnicore.pke.engine.kernel.CypherIdentifiers;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.kernel.ReservedNames;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.schema.EntityTypeDefinition;
import com.omnicore.pke.engine.schema.KnowledgeValidator;
import com.omnicore.pke.engine.schema.KnowledgeValidator.ValidatedEntity;
import com.omnicore.pke.engine.schema.KnowledgeValidator.ValidatedRelationship;
import com.omnicore.pke.engine.schema.RelationshipTypeDefinition;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

/**
 * The single writer of knowledge (ADR-009). Applies a {@link ChangeSet} in one transaction: entities, assertion
 * nodes and their native edges, evidence, versions and change events succeed or fail together.
 * <p>
 * Everything that can be checked without the database (types, labels, property names and values, natural keys,
 * evidence) is checked first; an invalid change set is rejected before a transaction is opened. Inside the
 * transaction, ids are first resolved through aliases (renamed entities keep their {@code pke_id}), then
 * relationship and evidence ids are derived from the canonical entity ids. Writing the same input again is a
 * no-op: no new versions, no events, no seq consumed.
 */
@Service
public class KnowledgeWriter {

    static final int MAX_EXCERPT_LENGTH = 2000;
    private static final Pattern EXPLICIT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$");

    private final GraphTransactions transactions;
    private final KnowledgeValidator validator;

    public KnowledgeWriter(GraphTransactions transactions, KnowledgeValidator validator) {
        this.transactions = transactions;
        this.validator = validator;
    }

    public ChangeResult apply(ChangeSet changes, String actor) {
        if (changes == null || changes.isEmpty()) {
            throw ValidationFailedException.of("$", "the change set is empty");
        }
        String operationId = changes.operationId() != null ? changes.operationId() : PkeIds.uuidV7().toString();
        Prepared prepared = prepare(changes);
        return transactions.write(actor == null ? "unknown" : actor, operationId, ctx -> {
            List<ItemOutcome> outcomes = new ArrayList<>();
            Set<String> touchedRelationships = new HashSet<>();
            prepared.entities().forEach(e -> outcomes.add(writeEntity(ctx, e)));
            for (PreparedRelationship r : prepared.relationships()) {
                ItemOutcome outcome = writeRelationship(ctx, r, Boolean.TRUE.equals(changes.skipMissingEndpoints()));
                touchedRelationships.add(outcome.id());
                outcomes.add(outcome);
            }
            prepared.invalidations().forEach(i -> outcomes.add(invalidate(ctx, i)));
            for (ScopeRetraction retraction : prepared.retractions()) {
                for (String id : retractable(ctx, retraction, touchedRelationships)) {
                    outcomes.add(invalidate(ctx, new PreparedInvalidation("/retractions", id, retraction.reason(), null, List.of())));
                }
            }
            List<Long> seqs = outcomes.stream().map(ItemOutcome::seq).filter(Objects::nonNull).toList();
            return new ChangeResult(operationId, outcomes, seqs.isEmpty() ? null : seqs.getFirst(),
                    seqs.isEmpty() ? null : seqs.getLast());
        });
    }

    public ChangeResult invalidate(RelationshipInvalidation invalidation, String actor) {
        return apply(new ChangeSet(null, List.of(), List.of(), List.of(invalidation)), actor);
    }

    // ---------------------------------------------------------------- validation before any Cypher runs

    private record Prepared(List<PreparedEntity> entities, List<PreparedRelationship> relationships,
                            List<PreparedInvalidation> invalidations, List<ScopeRetraction> retractions) {
    }

    /** Evidence properties without the subject-dependent id; {@code idKey} completes the id once the subject is known. */
    private record EvidenceTemplate(Map<String, Object> row, List<Object> idKey) {
    }

    private record PreparedEntity(String path, EntityTypeDefinition definition, String computedId, String renamedFromId,
                                  Map<String, Object> properties, String hash, EpistemicStatus epistemic,
                                  OffsetDateTime validAt, List<EvidenceTemplate> evidence) {
    }

    private record PreparedRelationship(String path, RelationshipTypeDefinition definition, String subjectId,
                                        String objectId, List<Object> discriminator, Map<String, Object> properties,
                                        String hash, EpistemicStatus epistemic, OffsetDateTime validAt,
                                        List<EvidenceTemplate> evidence) {
    }

    private record PreparedInvalidation(String path, String id, String reason, OffsetDateTime invalidAt,
                                        List<EvidenceTemplate> evidence) {
    }

    private Prepared prepare(ChangeSet changes) {
        List<Violation> violations = new ArrayList<>();
        List<PreparedEntity> entities = new ArrayList<>();
        Set<String> entityIds = new HashSet<>();
        for (int i = 0; i < changes.entities().size(); i++) {
            PreparedEntity entity = prepareEntity("/entities/" + i, changes.entities().get(i), violations);
            if (entity != null) {
                if (!entityIds.add(entity.computedId())) {
                    violations.add(new Violation(entity.path(), "entity " + entity.computedId() + " appears twice in the change set"));
                }
                entities.add(entity);
            }
        }
        List<PreparedRelationship> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();
        for (int i = 0; i < changes.relationships().size(); i++) {
            PreparedRelationship relationship = prepareRelationship("/relationships/" + i,
                    changes.relationships().get(i), violations);
            if (relationship != null) {
                String key = PkeIds.relationshipId(relationship.definition().name(), relationship.subjectId(),
                        relationship.objectId(), relationship.discriminator());
                if (!relationshipKeys.add(key)) {
                    violations.add(new Violation(relationship.path(), "the same relationship appears twice in the change set"));
                }
                relationships.add(relationship);
            }
        }
        List<PreparedInvalidation> invalidations = new ArrayList<>();
        for (int i = 0; i < changes.invalidations().size(); i++) {
            String path = "/invalidations/" + i;
            RelationshipInvalidation invalidation = changes.invalidations().get(i);
            if (isBlank(invalidation.relationshipId())) {
                violations.add(new Violation(path + "/relationshipId", "is required"));
                continue;
            }
            if (relationshipKeys.contains(invalidation.relationshipId())) {
                violations.add(new Violation(path, "the same change set also writes this relationship"));
            }
            invalidations.add(new PreparedInvalidation(path, invalidation.relationshipId(), invalidation.reason(),
                    dateTime(invalidation.invalidAt(), path + "/invalidAt", violations),
                    evidence(invalidation.evidence(), EpistemicStatus.OBSERVED, 0, path + "/evidence", violations)));
        }
        for (int i = 0; i < changes.retractions().size(); i++) {
            ScopeRetraction retraction = changes.retractions().get(i);
            String path = "/retractions/" + i;
            if (isBlank(retraction.sourceArtifactId()) || isBlank(retraction.scope()) || isBlank(retraction.extractionMethod())) {
                violations.add(new Violation(path, "sourceArtifactId, scope and extractionMethod are required"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Change set rejected", violations);
        }
        return new Prepared(entities, relationships, invalidations, changes.retractions());
    }

    private PreparedEntity prepareEntity(String path, EntityWrite write, List<Violation> violations) {
        ValidatedEntity validated = validator.entity(write.type(), write.properties(), path, violations);
        if (write.epistemic() == null) {
            violations.add(new Violation(path + "/epistemic", "is required"));
        }
        if (validated == null) {
            return null;
        }
        EntityTypeDefinition definition = validated.definition();
        String id = switch (definition.identityStrategy()) {
            case NATURAL_KEY -> {
                String computed = PkeIds.entityId(definition.name(),
                        validator.naturalKey(definition, validated.properties(), path + "/properties", violations));
                if (write.id() != null && !write.id().equals(computed)) {
                    violations.add(new Violation(path + "/id", "does not match the natural-key id " + computed));
                }
                yield computed;
            }
            case EXPLICIT -> {
                if (write.id() == null) {
                    violations.add(new Violation(path + "/id", definition.name() + " uses explicit ids: id is required"));
                }
                yield write.id();
            }
            case GENERATED -> write.id() != null ? write.id() : PkeIds.uuidV7().toString();
        };
        if (id != null && !EXPLICIT_ID.matcher(id).matches()) {
            violations.add(new Violation(path + "/id", "must match " + EXPLICIT_ID.pattern()));
        }
        String renamedFrom = null;
        if (write.renamedFrom() != null) {
            renamedFrom = resolve(write.renamedFrom(), path + "/renamedFrom", violations);
            if (write.renamedFrom().type() != null && !write.renamedFrom().type().equals(definition.name())) {
                violations.add(new Violation(path + "/renamedFrom/type", "a rename cannot change the entity type"));
            }
        }
        return new PreparedEntity(path, definition, id, renamedFrom, validated.properties(),
                CanonicalJson.sha256(validated.properties()), write.epistemic(),
                dateTime(write.validAt(), path + "/validAt", violations),
                evidence(write.evidence(), write.epistemic(), definition.evidenceMinItems(), path + "/evidence", violations));
    }

    private PreparedRelationship prepareRelationship(String path, RelationshipWrite write, List<Violation> violations) {
        ValidatedRelationship validated = validator.relationship(write.type(), write.properties(), path, violations);
        if (write.epistemic() == null) {
            violations.add(new Violation(path + "/epistemic", "is required"));
        }
        String subjectId = resolve(write.subject(), path + "/subject", violations);
        String objectId = resolve(write.object(), path + "/object", violations);
        if (validated == null || subjectId == null || objectId == null) {
            return null;
        }
        RelationshipTypeDefinition definition = validated.definition();
        validator.checkEndpoints(definition, write.subject().type(), write.object().type(), path, violations);
        return new PreparedRelationship(path, definition, subjectId, objectId,
                validator.discriminator(definition, validated.properties()), validated.properties(),
                CanonicalJson.sha256(validated.properties()), write.epistemic(),
                dateTime(write.validAt(), path + "/validAt", violations),
                evidence(write.evidence(), write.epistemic(), definition.evidenceMinItems(), path + "/evidence", violations));
    }

    private String resolve(EntityRef ref, String path, List<Violation> violations) {
        if (ref == null) {
            violations.add(new Violation(path, "is required"));
            return null;
        }
        if (ref.id() != null) {
            return ref.id();
        }
        EntityTypeDefinition definition = validator.entityType(ref.type(), path + "/type", violations);
        if (definition == null) {
            return null;
        }
        if (definition.identityStrategy() != EntityTypeDefinition.IdentityStrategy.NATURAL_KEY) {
            violations.add(new Violation(path, definition.name() + " has no natural key: refer to it by id"));
            return null;
        }
        Map<String, Object> key = ref.key() == null ? Map.of() : ref.key();
        key.keySet().stream().filter(k -> !definition.naturalKey().contains(k)).forEach(k ->
                violations.add(new Violation(path + "/key/" + k, "is not part of the natural key " + definition.naturalKey())));
        int before = violations.size();
        List<Object> values = validator.naturalKey(definition, key, path + "/key", violations);
        return violations.size() == before ? PkeIds.entityId(definition.name(), values) : null;
    }

    private List<EvidenceTemplate> evidence(List<EvidenceInput> inputs, EpistemicStatus defaultEpistemic, int minItems,
                                            String path, List<Violation> violations) {
        if (inputs.size() < minItems) {
            violations.add(new Violation(path, "at least " + minItems + " evidence item(s) required by the schema"));
        }
        List<EvidenceTemplate> evidence = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            EvidenceInput in = inputs.get(i);
            String itemPath = path + "/" + i;
            if (isBlank(in.sourceArtifactId())) {
                violations.add(new Violation(itemPath + "/sourceArtifactId", "is required"));
            }
            if (isBlank(in.extractionMethod())) {
                violations.add(new Violation(itemPath + "/extractionMethod", "is required"));
            }
            if (in.excerpt() != null && in.excerpt().length() > MAX_EXCERPT_LENGTH) {
                violations.add(new Violation(itemPath + "/excerpt", "is longer than " + MAX_EXCERPT_LENGTH
                        + " characters; reference the source location instead"));
            }
            if (in.confidence() != null && (in.confidence() < 0 || in.confidence() > 1)) {
                violations.add(new Violation(itemPath + "/confidence", "must be between 0 and 1"));
            }
            EpistemicStatus epistemic = in.epistemic() != null ? in.epistemic() : defaultEpistemic;
            if (epistemic == EpistemicStatus.INFERRED_AI && isBlank(in.modelId())) {
                violations.add(new Violation(itemPath + "/modelId", "is required for AI-inferred evidence"));
            }
            Map<String, Object> row = new HashMap<>();
            row.put("sourceArtifactId", in.sourceArtifactId());
            row.put("sourceVersion", in.sourceVersion());
            row.put("sourceVersionId", in.sourceArtifactId() != null && in.sourceVersion() != null
                    ? PkeIds.sourceVersionId(in.sourceArtifactId(), in.sourceVersion()) : null);
            row.put("scope", in.scope());
            row.put("location", in.location());
            row.put("contentHash", in.contentHash());
            row.put("extractionMethod", in.extractionMethod());
            row.put("pipelineVersion", in.pipelineVersion());
            row.put("skillVersion", in.skillVersion());
            row.put("modelProvider", in.modelProvider());
            row.put("modelId", in.modelId());
            row.put("excerpt", in.excerpt());
            row.put("verification", in.verification() != null ? in.verification() : "UNVERIFIED");
            row.put("confidenceReported", in.confidence());
            row.put("confidenceCalibrated", in.confidence() == null ? null : false);
            row.put("epistemic", epistemic == null ? null : epistemic.name());
            List<Object> idKey = in.scope() != null
                    ? Arrays.asList(in.sourceArtifactId(), "scope", in.scope(), in.extractionMethod())
                    : Arrays.asList(in.sourceArtifactId(), in.sourceVersion(), in.location(), in.extractionMethod(),
                    in.contentHash());
            evidence.add(new EvidenceTemplate(row, idKey));
        }
        return evidence;
    }

    private static OffsetDateTime dateTime(String value, String path, List<Violation> violations) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            violations.add(new Violation(path, "is not an ISO-8601 date-time with offset"));
            return null;
        }
    }

    // ---------------------------------------------------------------- identity inside the transaction

    /** The canonical {@code pke_id} for a computed id: itself, or the entity an alias points to. */
    private static String canonical(WriteContext ctx, String id) {
        return ctx.single("MATCH (a:Alias {pke_id: $id}) RETURN a.entityId AS target", Map.of("id", id))
                .map(r -> r.get("target").asString()).orElse(id);
    }

    private static List<Map<String, Object>> evidenceRows(List<EvidenceTemplate> templates, String subjectId) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (EvidenceTemplate template : templates) {
            String id = PkeIds.evidenceId(subjectId, template.idKey());
            Map<String, Object> row = new HashMap<>(template.row());
            row.put("pke_id", id);
            row.put("subjectId", subjectId);
            rows.putIfAbsent(id, row);
        }
        return new ArrayList<>(rows.values());
    }

    // ---------------------------------------------------------------- entities

    private Record loadEntity(WriteContext ctx, String id) {
        return ctx.single("""
                MATCH (n:Entity {pke_id: $id})
                RETURN n.pke_type AS type, n.pke_version AS version, n.pke_propsHash AS hash,
                       n.pke_epistemic AS epistemic, n.pke_validAt AS validAt, properties(n) AS props""",
                Map.of("id", id)).orElse(null);
    }

    private ItemOutcome writeEntity(WriteContext ctx, PreparedEntity e) {
        String label = CypherIdentifiers.label(e.definition().name());
        String id = canonical(ctx, e.computedId());
        Record existing = loadEntity(ctx, id);
        boolean rename = false;
        if (existing == null && e.renamedFromId() != null) {
            String previousId = canonical(ctx, e.renamedFromId());
            Record previous = loadEntity(ctx, previousId);
            if (previous != null && previous.get("type").asString().equals(e.definition().name())) {
                id = previousId;
                existing = previous;
                rename = true;
            }
        }

        if (existing == null) {
            long seq = ctx.nextSeq();
            ctx.run("""
                    CREATE (n:Entity {pke_id: $id, pke_type: $type, pke_schemaVersion: $schemaVersion, pke_version: 1,
                                      pke_propsHash: $hash, pke_epistemic: $epistemic, pke_validAt: $validAt,
                                      pke_createdSeq: $seq, pke_updatedSeq: $seq})
                    SET n:$($label)
                    SET n += $props""", params("id", id, "type", e.definition().name(), "label", label,
                    "schemaVersion", e.definition().version(), "hash", e.hash(), "epistemic", e.epistemic().name(),
                    "validAt", e.validAt(), "seq", seq, "props", e.properties()));
            ctx.reached(WriteStage.AFTER_ENTITY);
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.ENTITY, id, evidenceRows(e.evidence(), id), seq);
            writeEntityVersion(ctx, id, e, 1, seq, e.validAt());
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.ENTITY_CREATED, SubjectKind.ENTITY, id,
                    e.definition().name(), 1L, details("hash", e.hash(), "schemaVersion", e.definition().version(),
                    "epistemic", e.epistemic().name(), "evidenceIds", evidenceIds)));
            return new ItemOutcome("ENTITY", id, e.definition().name(), Outcome.CREATED, 1L, seq);
        }

        String existingType = existing.get("type").asString();
        if (!existingType.equals(e.definition().name())) {
            throw new ValidationFailedException("Change set rejected", List.of(new Violation(e.path() + "/type",
                    "entity " + id + " already exists as " + existingType)));
        }
        long version = existing.get("version").asLong();
        Map<String, Object> oldProps = domainProperties(existing.get("props").asMap());
        Object existingValidAt = existing.get("validAt").asObject();
        boolean validAtChanged = e.validAt() != null && !sameInstant(e.validAt(), existingValidAt);
        boolean changed = rename
                || !e.hash().equals(existing.get("hash").asString(null))
                || !e.epistemic().name().equals(existing.get("epistemic").asString(null))
                || validAtChanged;
        List<Map<String, Object>> newEvidence = newEvidence(ctx, evidenceRows(e.evidence(), id));

        if (!changed) {
            if (newEvidence.isEmpty()) {
                return new ItemOutcome("ENTITY", id, existingType, Outcome.UNCHANGED, version, null);
            }
            long seq = ctx.nextSeq();
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.ENTITY, id, newEvidence, seq);
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.EVIDENCE_ADDED, SubjectKind.ENTITY, id, existingType,
                    version, details("evidenceIds", evidenceIds)));
            return new ItemOutcome("ENTITY", id, existingType, Outcome.EVIDENCE_ADDED, version, seq);
        }

        long seq = ctx.nextSeq();
        long newVersion = version + 1;
        Map<String, Object> props = withRemovals(oldProps, e.properties());
        ctx.run("""
                MATCH (n:Entity {pke_id: $id})
                SET n += $props, n.pke_version = $version, n.pke_propsHash = $hash, n.pke_epistemic = $epistemic,
                    n.pke_validAt = coalesce($validAt, n.pke_validAt), n.pke_updatedSeq = $seq,
                    n.pke_schemaVersion = $schemaVersion""", params("id", id, "props", props, "version", newVersion,
                "hash", e.hash(), "epistemic", e.epistemic().name(), "validAt", e.validAt(), "seq", seq,
                "schemaVersion", e.definition().version()));
        if (rename) {
            ctx.run("""
                    MATCH (n:Entity {pke_id: $id})
                    MERGE (a:Alias {pke_id: $alias})
                      ON CREATE SET a.entityId = $id, a.entityType = $type, a.createdSeq = $seq
                    MERGE (n)-[:HAS_ALIAS]->(a)""", Map.of("id", id, "alias", e.computedId(), "type", existingType, "seq", seq));
        }
        ctx.reached(WriteStage.AFTER_ENTITY);
        List<String> evidenceIds = writeEvidence(ctx, SubjectKind.ENTITY, id, newEvidence, seq);
        writeEntityVersion(ctx, id, e, newVersion, seq, e.validAt() != null ? e.validAt() : existingValidAt);
        ctx.emit(new PendingEvent(seq, rename ? ChangeEventTypes.ENTITY_RENAMED : ChangeEventTypes.ENTITY_UPDATED,
                SubjectKind.ENTITY, id, existingType, newVersion, details("fromVersion", version, "hash", e.hash(),
                "changedKeys", changedKeys(oldProps, e.properties()), "epistemic", e.epistemic().name(),
                "validAtChanged", validAtChanged, "aliasId", rename ? e.computedId() : null, "evidenceIds", evidenceIds)));
        return new ItemOutcome("ENTITY", id, existingType, rename ? Outcome.RENAMED : Outcome.UPDATED, newVersion, seq);
    }

    private void writeEntityVersion(WriteContext ctx, String id, PreparedEntity e, long version, long seq, Object validAt) {
        ctx.run("""
                MATCH (n:Entity {pke_id: $id})
                OPTIONAL MATCH (n)-[:HAS_VERSION]->(prev:EntityVersion {version: $previous})
                SET prev.validToSeq = $seq
                CREATE (n)-[:HAS_VERSION]->(:EntityVersion {pke_id: $versionId, entityId: $id, version: $version,
                        propsJson: $propsJson, propsHash: $hash, epistemic: $epistemic, validAt: $validAt,
                        schemaVersion: $schemaVersion, validFromSeq: $seq, recordedAt: $at})""",
                params("id", id, "previous", version - 1, "seq", seq, "versionId", PkeIds.versionId(id, version),
                        "version", version, "propsJson", CanonicalJson.write(e.properties()), "hash", e.hash(),
                        "epistemic", e.epistemic().name(), "validAt", validAt, "schemaVersion", e.definition().version(),
                        "at", ctx.now()));
        ctx.reached(WriteStage.AFTER_VERSION);
    }

    // ---------------------------------------------------------------- relationships

    private ItemOutcome writeRelationship(WriteContext ctx, PreparedRelationship r, boolean skipMissingEndpoints) {
        String type = CypherIdentifiers.relationshipType(r.definition().name());
        String subjectId = canonical(ctx, r.subjectId());
        String objectId = canonical(ctx, r.objectId());
        String id = PkeIds.relationshipId(type, subjectId, objectId, r.discriminator());
        Record endpoints = ctx.single("""
                OPTIONAL MATCH (s:Entity {pke_id: $subjectId})
                OPTIONAL MATCH (o:Entity {pke_id: $objectId})
                RETURN s.pke_type AS subjectType, o.pke_type AS objectType""",
                Map.of("subjectId", subjectId, "objectId", objectId)).orElseThrow();
        List<Violation> violations = new ArrayList<>();
        String subjectType = endpoints.get("subjectType").asString(null);
        String objectType = endpoints.get("objectType").asString(null);
        if (skipMissingEndpoints && (subjectType == null || objectType == null)) {
            return new ItemOutcome("RELATIONSHIP", id, type, Outcome.SKIPPED, null, null);
        }
        if (subjectType == null) {
            violations.add(new Violation(r.path() + "/subject", "entity " + subjectId + " does not exist"));
        }
        if (objectType == null) {
            violations.add(new Violation(r.path() + "/object", "entity " + objectId + " does not exist"));
        }
        validator.checkEndpoints(r.definition(), subjectType, objectType, r.path(), violations);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Change set rejected", violations);
        }

        Record existing = ctx.single("""
                MATCH (a:Assertion {pke_id: $id})
                RETURN a.pke_status AS status, a.pke_version AS version, a.pke_propsHash AS hash,
                       a.pke_epistemic AS epistemic, a.pke_validAt AS validAt, properties(a) AS props""",
                Map.of("id", id)).orElse(null);
        String category = r.definition().category();
        List<Map<String, Object>> evidence = evidenceRows(r.evidence(), id);

        if (existing == null) {
            long seq = ctx.nextSeq();
            OffsetDateTime validAt = r.validAt() != null ? r.validAt() : ctx.now();
            ctx.run("""
                    MATCH (s:Entity {pke_id: $subjectId}), (o:Entity {pke_id: $objectId})
                    CREATE (a:Assertion {pke_id: $id, pke_type: $type, pke_category: $category,
                                         pke_schemaVersion: $schemaVersion, pke_subjectId: $subjectId,
                                         pke_objectId: $objectId, pke_status: 'CURRENT', pke_epistemic: $epistemic,
                                         pke_version: 1, pke_propsHash: $hash, pke_validAt: $validAt,
                                         pke_createdSeq: $seq, pke_updatedSeq: $seq})
                    SET a += $props
                    CREATE (a)-[:SUBJECT]->(s), (a)-[:OBJECT]->(o)""", params("id", id, "type", type,
                    "category", category, "schemaVersion", r.definition().version(), "subjectId", subjectId,
                    "objectId", objectId, "epistemic", r.epistemic().name(), "hash", r.hash(), "validAt", validAt,
                    "seq", seq, "props", r.properties()));
            ctx.reached(WriteStage.AFTER_ASSERTION);
            createEdge(ctx, r, id, subjectId, objectId, type);
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, id, evidence, seq);
            writeAssertionVersion(ctx, id, 1, seq, "CURRENT", r.properties(), r.hash(), r.epistemic().name(),
                    validAt, null, r.definition().version());
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.RELATIONSHIP_CREATED, SubjectKind.RELATIONSHIP, id, type,
                    1L, details("subjectId", subjectId, "objectId", objectId, "hash", r.hash(),
                    "epistemic", r.epistemic().name(), "category", category, "evidenceIds", evidenceIds)));
            return new ItemOutcome("RELATIONSHIP", id, type, Outcome.CREATED, 1L, seq);
        }

        long version = existing.get("version").asLong();
        boolean current = "CURRENT".equals(existing.get("status").asString());
        Object existingValidAt = existing.get("validAt").asObject();
        boolean validAtChanged = r.validAt() != null && !sameInstant(r.validAt(), existingValidAt);
        boolean changed = !r.hash().equals(existing.get("hash").asString(null))
                || !r.epistemic().name().equals(existing.get("epistemic").asString(null))
                || validAtChanged;
        List<Map<String, Object>> newEvidence = newEvidence(ctx, evidence);

        if (current && !changed) {
            if (newEvidence.isEmpty()) {
                return new ItemOutcome("RELATIONSHIP", id, type, Outcome.UNCHANGED, version, null);
            }
            long seq = ctx.nextSeq();
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, id, newEvidence, seq);
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.EVIDENCE_ADDED, SubjectKind.RELATIONSHIP, id, type,
                    version, details("evidenceIds", evidenceIds)));
            return new ItemOutcome("RELATIONSHIP", id, type, Outcome.EVIDENCE_ADDED, version, seq);
        }

        long seq = ctx.nextSeq();
        long newVersion = version + 1;
        Map<String, Object> oldProps = domainProperties(existing.get("props").asMap());
        Map<String, Object> props = withRemovals(oldProps, r.properties());
        Object validAt = r.validAt() != null ? r.validAt() : (current ? existingValidAt : ctx.now());
        updateAssertion(ctx, id, props, newVersion, r.hash(), r.epistemic().name(), "CURRENT", validAt, null, null,
                seq, r.definition().version());
        ctx.reached(WriteStage.AFTER_ASSERTION);
        if (current) {
            int updated = ctx.single("""
                    MATCH (s:Entity {pke_id: $subjectId})-[r:$($type)]->(o:Entity {pke_id: $objectId})
                    WHERE r.pke_relId = $id
                    SET r += $props, r.pke_epistemic = $epistemic
                    RETURN count(r) AS updated""", params("subjectId", subjectId, "objectId", objectId,
                    "type", type, "id", id, "props", props, "epistemic", r.epistemic().name()))
                    .orElseThrow().get("updated").asInt();
            if (updated != 1) {
                throw new IllegalStateException("Integrity violation: current assertion " + id + " has " + updated
                        + " native edges; run GET /api/integrity");
            }
            ctx.reached(WriteStage.AFTER_EDGE);
        } else {
            createEdge(ctx, r, id, subjectId, objectId, type);
        }
        List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, id, newEvidence, seq);
        writeAssertionVersion(ctx, id, newVersion, seq, "CURRENT", r.properties(), r.hash(), r.epistemic().name(),
                validAt, null, r.definition().version());
        String eventType = current ? ChangeEventTypes.RELATIONSHIP_UPDATED : ChangeEventTypes.RELATIONSHIP_REVALIDATED;
        ctx.emit(new PendingEvent(seq, eventType, SubjectKind.RELATIONSHIP, id, type, newVersion,
                details("fromVersion", version, "hash", r.hash(), "changedKeys", changedKeys(oldProps, r.properties()),
                        "epistemic", r.epistemic().name(), "evidenceIds", evidenceIds)));
        return new ItemOutcome("RELATIONSHIP", id, type, current ? Outcome.UPDATED : Outcome.REVALIDATED, newVersion, seq);
    }

    private void createEdge(WriteContext ctx, PreparedRelationship r, String id, String subjectId, String objectId,
                            String type) {
        ctx.run("""
                MATCH (s:Entity {pke_id: $subjectId}), (o:Entity {pke_id: $objectId})
                CREATE (s)-[r:$($type)]->(o)
                SET r.pke_relId = $id, r.pke_epistemic = $epistemic, r.pke_category = $category
                SET r += $props""", params("subjectId", subjectId, "objectId", objectId, "type", type,
                "id", id, "epistemic", r.epistemic().name(), "category", r.definition().category(),
                "props", r.properties()));
        ctx.reached(WriteStage.AFTER_EDGE);
    }

    /**
     * Current assertions supported by the retracted scope that this change set did not write again, and that no
     * other scope (or unscoped evidence) still supports.
     */
    private static List<String> retractable(WriteContext ctx, ScopeRetraction retraction, Set<String> touched) {
        return ctx.run("""
                MATCH (a:Assertion {pke_status: 'CURRENT'})-[:SUPPORTED_BY]->(:Evidence {sourceArtifactId: $artifact,
                                                                                    scope: $scope, extractionMethod: $method})
                WHERE NOT a.pke_id IN $touched
                  AND NOT EXISTS {
                    MATCH (a)-[:SUPPORTED_BY]->(o:Evidence)
                    WHERE o.scope IS NULL OR o.scope <> $scope OR o.sourceArtifactId <> $artifact
                  }
                RETURN DISTINCT a.pke_id AS id ORDER BY id""",
                Map.of("artifact", retraction.sourceArtifactId(), "scope", retraction.scope(),
                        "method", retraction.extractionMethod(), "touched", List.copyOf(touched)))
                .stream().map(r -> r.get("id").asString()).toList();
    }

    private ItemOutcome invalidate(WriteContext ctx, PreparedInvalidation inv) {
        Record existing = ctx.single("""
                MATCH (a:Assertion {pke_id: $id})
                RETURN a.pke_type AS type, a.pke_status AS status, a.pke_version AS version, a.pke_propsHash AS hash,
                       a.pke_epistemic AS epistemic, a.pke_validAt AS validAt, a.pke_subjectId AS subjectId,
                       a.pke_objectId AS objectId, a.pke_schemaVersion AS schemaVersion, properties(a) AS props""",
                Map.of("id", inv.id())).orElseThrow(() -> new NotFoundException("Relationship " + inv.id() + " does not exist"));
        String type = CypherIdentifiers.relationshipType(existing.get("type").asString());
        long version = existing.get("version").asLong();
        if (!"CURRENT".equals(existing.get("status").asString())) {
            return new ItemOutcome("RELATIONSHIP", inv.id(), type, Outcome.UNCHANGED, version, null);
        }
        long seq = ctx.nextSeq();
        long newVersion = version + 1;
        OffsetDateTime invalidAt = inv.invalidAt() != null ? inv.invalidAt() : ctx.now();
        String hash = existing.get("hash").asString();
        String epistemic = existing.get("epistemic").asString();
        Object validAt = existing.get("validAt").asObject();
        String schemaVersion = existing.get("schemaVersion").asString(null);
        updateAssertion(ctx, inv.id(), Map.of(), newVersion, hash, epistemic, "INVALID", validAt, invalidAt, inv.reason(),
                seq, schemaVersion);
        ctx.reached(WriteStage.AFTER_ASSERTION);
        int deleted = ctx.single("""
                MATCH (s:Entity {pke_id: $subjectId})-[r:$($type)]->(o:Entity {pke_id: $objectId})
                WHERE r.pke_relId = $id
                DELETE r
                RETURN count(*) AS deleted""", params("subjectId", existing.get("subjectId").asString(),
                "objectId", existing.get("objectId").asString(), "type", type, "id", inv.id()))
                .orElseThrow().get("deleted").asInt();
        if (deleted != 1) {
            throw new IllegalStateException("Integrity violation: current assertion " + inv.id() + " had " + deleted
                    + " native edges; run GET /api/integrity");
        }
        ctx.reached(WriteStage.AFTER_EDGE);
        List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, inv.id(),
                newEvidence(ctx, evidenceRows(inv.evidence(), inv.id())), seq);
        writeAssertionVersion(ctx, inv.id(), newVersion, seq, "INVALID", domainProperties(existing.get("props").asMap()),
                hash, epistemic, validAt, invalidAt, schemaVersion);
        ctx.emit(new PendingEvent(seq, ChangeEventTypes.RELATIONSHIP_INVALIDATED, SubjectKind.RELATIONSHIP, inv.id(), type,
                newVersion, details("fromVersion", version, "reason", inv.reason(), "evidenceIds", evidenceIds)));
        return new ItemOutcome("RELATIONSHIP", inv.id(), type, Outcome.INVALIDATED, newVersion, seq);
    }

    private void updateAssertion(WriteContext ctx, String id, Map<String, Object> props, long version, String hash,
                                 String epistemic, String status, Object validAt, Object invalidAt, String reason,
                                 long seq, String schemaVersion) {
        ctx.run("""
                MATCH (a:Assertion {pke_id: $id})
                SET a += $props, a.pke_version = $version, a.pke_propsHash = $hash, a.pke_epistemic = $epistemic,
                    a.pke_status = $status, a.pke_validAt = $validAt, a.pke_invalidAt = $invalidAt,
                    a.pke_invalidationReason = $reason, a.pke_updatedSeq = $seq, a.pke_schemaVersion = $schemaVersion""",
                params("id", id, "props", props, "version", version, "hash", hash, "epistemic", epistemic,
                        "status", status, "validAt", validAt, "invalidAt", invalidAt, "reason", reason, "seq", seq,
                        "schemaVersion", schemaVersion));
    }

    private void writeAssertionVersion(WriteContext ctx, String id, long version, long seq, String status,
                                       Map<String, Object> props, String hash, String epistemic, Object validAt,
                                       Object invalidAt, String schemaVersion) {
        ctx.run("""
                MATCH (a:Assertion {pke_id: $id})
                OPTIONAL MATCH (a)-[:HAS_VERSION]->(prev:AssertionVersion {version: $previous})
                SET prev.validToSeq = $seq
                CREATE (a)-[:HAS_VERSION]->(:AssertionVersion {pke_id: $versionId, assertionId: $id, version: $version,
                        status: $status, propsJson: $propsJson, propsHash: $hash, epistemic: $epistemic,
                        validAt: $validAt, invalidAt: $invalidAt, schemaVersion: $schemaVersion,
                        validFromSeq: $seq, recordedAt: $at})""",
                params("id", id, "previous", version - 1, "seq", seq, "versionId", PkeIds.versionId(id, version),
                        "version", version, "status", status, "propsJson", CanonicalJson.write(props), "hash", hash,
                        "epistemic", epistemic, "validAt", validAt, "invalidAt", invalidAt, "schemaVersion", schemaVersion,
                        "at", ctx.now()));
        ctx.reached(WriteStage.AFTER_VERSION);
    }

    // ---------------------------------------------------------------- evidence

    private List<Map<String, Object>> newEvidence(WriteContext ctx, List<Map<String, Object>> evidence) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        Set<String> existing = new HashSet<>();
        ctx.run("UNWIND $ids AS id MATCH (e:Evidence {pke_id: id}) RETURN e.pke_id AS id",
                Map.of("ids", evidence.stream().map(e -> e.get("pke_id")).toList()))
                .forEach(r -> existing.add(r.get("id").asString()));
        return evidence.stream().filter(e -> !existing.contains(e.get("pke_id"))).toList();
    }

    /** Writes evidence for a subject and links each item to its {@code :SourceVersion} when that version is known. */
    private List<String> writeEvidence(WriteContext ctx, SubjectKind kind, String subjectId,
                                       List<Map<String, Object>> evidence, long seq) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = evidence.stream().map(e -> {
            Map<String, Object> row = new HashMap<>(e);
            row.put("recordedSeq", seq);
            row.put("recordedAt", ctx.now());
            return row;
        }).toList();
        String cypher = kind == SubjectKind.ENTITY
                ? """
                  MATCH (s:Entity {pke_id: $subjectId})
                  UNWIND $evidence AS ev
                  CREATE (e:Evidence) SET e = ev
                  CREATE (s)-[:SUPPORTED_BY]->(e)
                  WITH e
                  OPTIONAL MATCH (sv:SourceVersion {pke_id: e.sourceVersionId})
                  FOREACH (_ IN CASE WHEN sv IS NULL THEN [] ELSE [1] END | CREATE (e)-[:FROM]->(sv))"""
                : """
                  MATCH (s:Assertion {pke_id: $subjectId})
                  UNWIND $evidence AS ev
                  CREATE (e:Evidence) SET e = ev
                  CREATE (s)-[:SUPPORTED_BY]->(e)
                  WITH e
                  OPTIONAL MATCH (sv:SourceVersion {pke_id: e.sourceVersionId})
                  FOREACH (_ IN CASE WHEN sv IS NULL THEN [] ELSE [1] END | CREATE (e)-[:FROM]->(sv))""";
        ctx.run(cypher, Map.of("subjectId", subjectId, "evidence", rows));
        ctx.reached(WriteStage.AFTER_EVIDENCE);
        return rows.stream().map(r -> (String) r.get("pke_id")).toList();
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> domainProperties(Map<String, Object> all) {
        Map<String, Object> domain = new TreeMap<>();
        all.forEach((k, v) -> {
            if (!ReservedNames.isSystemProperty(k)) {
                domain.put(k, v);
            }
        });
        return domain;
    }

    /** New properties plus explicit nulls for keys that disappeared, so {@code SET n += $props} removes them. */
    private static Map<String, Object> withRemovals(Map<String, Object> oldProps, Map<String, Object> newProps) {
        Map<String, Object> props = new HashMap<>(newProps);
        oldProps.keySet().stream().filter(k -> !newProps.containsKey(k)).forEach(k -> props.put(k, null));
        return props;
    }

    private static List<String> changedKeys(Map<String, Object> oldProps, Map<String, Object> newProps) {
        Set<String> keys = new TreeSet<>(oldProps.keySet());
        keys.addAll(newProps.keySet());
        return keys.stream().filter(k -> !Objects.equals(oldProps.get(k), newProps.get(k))).toList();
    }

    private static boolean sameInstant(OffsetDateTime requested, Object stored) {
        if (stored instanceof java.time.ZonedDateTime z) {
            return z.toInstant().equals(requested.toInstant());
        }
        if (stored instanceof OffsetDateTime o) {
            return o.toInstant().equals(requested.toInstant());
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                details.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return details;
    }

    /** Like {@code Map.of}, but allows null values (sent to Neo4j as null). */
    static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }
}
