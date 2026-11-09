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
 * Everything that can be checked without the database (types, labels, property names and values, ids, evidence)
 * is checked first; an invalid change set is rejected before a transaction is opened. Writing the same input
 * again is a no-op: no new versions, no events, no seq consumed.
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
            prepared.entities().forEach(e -> outcomes.add(writeEntity(ctx, e)));
            prepared.relationships().forEach(r -> outcomes.add(writeRelationship(ctx, r)));
            prepared.invalidations().forEach(i -> outcomes.add(invalidate(ctx, i)));
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
                            List<PreparedInvalidation> invalidations) {
    }

    private record PreparedEntity(String path, EntityTypeDefinition definition, String id, Map<String, Object> properties,
                                  String hash, EpistemicStatus epistemic, OffsetDateTime validAt,
                                  List<Map<String, Object>> evidence) {
    }

    private record PreparedRelationship(String path, RelationshipTypeDefinition definition, String id, String subjectId,
                                        String objectId, Map<String, Object> properties, String hash,
                                        EpistemicStatus epistemic, OffsetDateTime validAt,
                                        List<Map<String, Object>> evidence) {
    }

    private record PreparedInvalidation(String path, String id, String reason, OffsetDateTime invalidAt,
                                        List<Map<String, Object>> evidence) {
    }

    private Prepared prepare(ChangeSet changes) {
        List<Violation> violations = new ArrayList<>();
        List<PreparedEntity> entities = new ArrayList<>();
        Set<String> entityIds = new HashSet<>();
        for (int i = 0; i < changes.entities().size(); i++) {
            PreparedEntity entity = prepareEntity("/entities/" + i, changes.entities().get(i), violations);
            if (entity != null) {
                if (!entityIds.add(entity.id())) {
                    violations.add(new Violation(entity.path(), "entity " + entity.id() + " appears twice in the change set"));
                }
                entities.add(entity);
            }
        }
        List<PreparedRelationship> relationships = new ArrayList<>();
        Set<String> relationshipIds = new HashSet<>();
        for (int i = 0; i < changes.relationships().size(); i++) {
            PreparedRelationship relationship = prepareRelationship("/relationships/" + i,
                    changes.relationships().get(i), violations);
            if (relationship != null) {
                if (!relationshipIds.add(relationship.id())) {
                    violations.add(new Violation(relationship.path(),
                            "relationship " + relationship.id() + " appears twice in the change set"));
                }
                relationships.add(relationship);
            }
        }
        List<PreparedInvalidation> invalidations = new ArrayList<>();
        for (int i = 0; i < changes.invalidations().size(); i++) {
            String path = "/invalidations/" + i;
            RelationshipInvalidation invalidation = changes.invalidations().get(i);
            if (invalidation.relationshipId() == null || invalidation.relationshipId().isBlank()) {
                violations.add(new Violation(path + "/relationshipId", "is required"));
                continue;
            }
            if (relationshipIds.contains(invalidation.relationshipId())) {
                violations.add(new Violation(path, "the same change set also writes this relationship"));
            }
            invalidations.add(new PreparedInvalidation(path, invalidation.relationshipId(), invalidation.reason(),
                    dateTime(invalidation.invalidAt(), path + "/invalidAt", violations),
                    evidence(invalidation.relationshipId(), invalidation.evidence(), EpistemicStatus.OBSERVED, 0,
                            path + "/evidence", violations)));
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Change set rejected", violations);
        }
        return new Prepared(entities, relationships, invalidations);
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
        EpistemicStatus epistemic = write.epistemic();
        List<Map<String, Object>> evidence = evidence(id, write.evidence(), epistemic, definition.evidenceMinItems(),
                path + "/evidence", violations);
        return new PreparedEntity(path, definition, id, validated.properties(), CanonicalJson.sha256(validated.properties()),
                epistemic, dateTime(write.validAt(), path + "/validAt", violations), evidence);
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
        String id = PkeIds.relationshipId(definition.name(), subjectId, objectId,
                validator.discriminator(definition, validated.properties()));
        List<Map<String, Object>> evidence = evidence(id, write.evidence(), write.epistemic(),
                definition.evidenceMinItems(), path + "/evidence", violations);
        return new PreparedRelationship(path, definition, id, subjectId, objectId, validated.properties(),
                CanonicalJson.sha256(validated.properties()), write.epistemic(),
                dateTime(write.validAt(), path + "/validAt", violations), evidence);
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

    private List<Map<String, Object>> evidence(String subjectId, List<EvidenceInput> inputs, EpistemicStatus defaultEpistemic,
                                               int minItems, String path, List<Violation> violations) {
        if (inputs.size() < minItems) {
            violations.add(new Violation(path, "at least " + minItems + " evidence item(s) required by the schema"));
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        Set<String> ids = new HashSet<>();
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
            if (subjectId == null) {
                continue;
            }
            String id = PkeIds.evidenceId(subjectId, Arrays.asList(in.sourceArtifactId(), in.sourceVersion(),
                    in.location(), in.extractionMethod(), in.contentHash()));
            if (!ids.add(id)) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("pke_id", id);
            row.put("subjectId", subjectId);
            row.put("sourceArtifactId", in.sourceArtifactId());
            row.put("sourceVersion", in.sourceVersion());
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
            evidence.add(row);
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

    // ---------------------------------------------------------------- entities

    private ItemOutcome writeEntity(WriteContext ctx, PreparedEntity e) {
        String label = CypherIdentifiers.label(e.definition().name());
        Record existing = ctx.single("""
                MATCH (n:Entity {pke_id: $id})
                RETURN n.pke_type AS type, n.pke_version AS version, n.pke_propsHash AS hash,
                       n.pke_epistemic AS epistemic, n.pke_validAt AS validAt, properties(n) AS props""",
                Map.of("id", e.id())).orElse(null);

        if (existing == null) {
            long seq = ctx.nextSeq();
            ctx.run("""
                    CREATE (n:Entity {pke_id: $id, pke_type: $type, pke_schemaVersion: $schemaVersion, pke_version: 1,
                                      pke_propsHash: $hash, pke_epistemic: $epistemic, pke_validAt: $validAt,
                                      pke_createdSeq: $seq, pke_updatedSeq: $seq})
                    SET n:$($label)
                    SET n += $props""", params("id", e.id(), "type", e.definition().name(), "label", label,
                    "schemaVersion", e.definition().version(), "hash", e.hash(), "epistemic", e.epistemic().name(),
                    "validAt", e.validAt(), "seq", seq, "props", e.properties()));
            ctx.reached(WriteStage.AFTER_ENTITY);
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.ENTITY, e.id(), e.evidence(), seq);
            writeEntityVersion(ctx, e, 1, seq, e.validAt());
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.ENTITY_CREATED, SubjectKind.ENTITY, e.id(),
                    e.definition().name(), 1L, details("hash", e.hash(), "schemaVersion", e.definition().version(),
                    "epistemic", e.epistemic().name(), "evidenceIds", evidenceIds)));
            return new ItemOutcome("ENTITY", e.id(), e.definition().name(), Outcome.CREATED, 1L, seq);
        }

        String existingType = existing.get("type").asString();
        if (!existingType.equals(e.definition().name())) {
            throw new ValidationFailedException("Change set rejected", List.of(new Violation(e.path() + "/type",
                    "entity " + e.id() + " already exists as " + existingType)));
        }
        long version = existing.get("version").asLong();
        Map<String, Object> oldProps = domainProperties(existing.get("props").asMap());
        Object existingValidAt = existing.get("validAt").asObject();
        boolean validAtChanged = e.validAt() != null && !sameInstant(e.validAt(), existingValidAt);
        boolean changed = !e.hash().equals(existing.get("hash").asString(null))
                || !e.epistemic().name().equals(existing.get("epistemic").asString(null))
                || validAtChanged;
        List<Map<String, Object>> newEvidence = newEvidence(ctx, e.evidence());

        if (!changed) {
            if (newEvidence.isEmpty()) {
                return new ItemOutcome("ENTITY", e.id(), existingType, Outcome.UNCHANGED, version, null);
            }
            long seq = ctx.nextSeq();
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.ENTITY, e.id(), newEvidence, seq);
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.EVIDENCE_ADDED, SubjectKind.ENTITY, e.id(), existingType,
                    version, details("evidenceIds", evidenceIds)));
            return new ItemOutcome("ENTITY", e.id(), existingType, Outcome.EVIDENCE_ADDED, version, seq);
        }

        long seq = ctx.nextSeq();
        long newVersion = version + 1;
        Map<String, Object> props = withRemovals(oldProps, e.properties());
        ctx.run("""
                MATCH (n:Entity {pke_id: $id})
                SET n += $props, n.pke_version = $version, n.pke_propsHash = $hash, n.pke_epistemic = $epistemic,
                    n.pke_validAt = coalesce($validAt, n.pke_validAt), n.pke_updatedSeq = $seq,
                    n.pke_schemaVersion = $schemaVersion""", params("id", e.id(), "props", props, "version", newVersion,
                "hash", e.hash(), "epistemic", e.epistemic().name(), "validAt", e.validAt(), "seq", seq,
                "schemaVersion", e.definition().version()));
        ctx.reached(WriteStage.AFTER_ENTITY);
        List<String> evidenceIds = writeEvidence(ctx, SubjectKind.ENTITY, e.id(), newEvidence, seq);
        writeEntityVersion(ctx, e, newVersion, seq, e.validAt() != null ? e.validAt() : existingValidAt);
        ctx.emit(new PendingEvent(seq, ChangeEventTypes.ENTITY_UPDATED, SubjectKind.ENTITY, e.id(), existingType,
                newVersion, details("fromVersion", version, "hash", e.hash(), "changedKeys", changedKeys(oldProps, e.properties()),
                "epistemic", e.epistemic().name(), "validAtChanged", validAtChanged, "evidenceIds", evidenceIds)));
        return new ItemOutcome("ENTITY", e.id(), existingType, Outcome.UPDATED, newVersion, seq);
    }

    private void writeEntityVersion(WriteContext ctx, PreparedEntity e, long version, long seq, Object validAt) {
        ctx.run("""
                MATCH (n:Entity {pke_id: $id})
                OPTIONAL MATCH (n)-[:HAS_VERSION]->(prev:EntityVersion {version: $previous})
                SET prev.validToSeq = $seq
                CREATE (n)-[:HAS_VERSION]->(:EntityVersion {pke_id: $versionId, entityId: $id, version: $version,
                        propsJson: $propsJson, propsHash: $hash, epistemic: $epistemic, validAt: $validAt,
                        schemaVersion: $schemaVersion, validFromSeq: $seq, recordedAt: $at})""",
                params("id", e.id(), "previous", version - 1, "seq", seq, "versionId", PkeIds.versionId(e.id(), version),
                        "version", version, "propsJson", CanonicalJson.write(e.properties()), "hash", e.hash(),
                        "epistemic", e.epistemic().name(), "validAt", validAt, "schemaVersion", e.definition().version(),
                        "at", ctx.now()));
        ctx.reached(WriteStage.AFTER_VERSION);
    }

    // ---------------------------------------------------------------- relationships

    private ItemOutcome writeRelationship(WriteContext ctx, PreparedRelationship r) {
        String type = CypherIdentifiers.relationshipType(r.definition().name());
        Record endpoints = ctx.single("""
                OPTIONAL MATCH (s:Entity {pke_id: $subjectId})
                OPTIONAL MATCH (o:Entity {pke_id: $objectId})
                RETURN s.pke_type AS subjectType, o.pke_type AS objectType""",
                Map.of("subjectId", r.subjectId(), "objectId", r.objectId())).orElseThrow();
        List<Violation> violations = new ArrayList<>();
        String subjectType = endpoints.get("subjectType").asString(null);
        String objectType = endpoints.get("objectType").asString(null);
        if (subjectType == null) {
            violations.add(new Violation(r.path() + "/subject", "entity " + r.subjectId() + " does not exist"));
        }
        if (objectType == null) {
            violations.add(new Violation(r.path() + "/object", "entity " + r.objectId() + " does not exist"));
        }
        validator.checkEndpoints(r.definition(), subjectType, objectType, r.path(), violations);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Change set rejected", violations);
        }

        Record existing = ctx.single("""
                MATCH (a:Assertion {pke_id: $id})
                RETURN a.pke_status AS status, a.pke_version AS version, a.pke_propsHash AS hash,
                       a.pke_epistemic AS epistemic, a.pke_validAt AS validAt, properties(a) AS props""",
                Map.of("id", r.id())).orElse(null);
        String category = r.definition().category();

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
                    CREATE (a)-[:SUBJECT]->(s), (a)-[:OBJECT]->(o)""", params("id", r.id(), "type", type,
                    "category", category, "schemaVersion", r.definition().version(), "subjectId", r.subjectId(),
                    "objectId", r.objectId(), "epistemic", r.epistemic().name(), "hash", r.hash(), "validAt", validAt,
                    "seq", seq, "props", r.properties()));
            ctx.reached(WriteStage.AFTER_ASSERTION);
            createEdge(ctx, r, type);
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, r.id(), r.evidence(), seq);
            writeAssertionVersion(ctx, r.id(), 1, seq, "CURRENT", r.properties(), r.hash(), r.epistemic().name(),
                    validAt, null, r.definition().version());
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.RELATIONSHIP_CREATED, SubjectKind.RELATIONSHIP, r.id(), type,
                    1L, details("subjectId", r.subjectId(), "objectId", r.objectId(), "hash", r.hash(),
                    "epistemic", r.epistemic().name(), "category", category, "evidenceIds", evidenceIds)));
            return new ItemOutcome("RELATIONSHIP", r.id(), type, Outcome.CREATED, 1L, seq);
        }

        long version = existing.get("version").asLong();
        boolean current = "CURRENT".equals(existing.get("status").asString());
        Object existingValidAt = existing.get("validAt").asObject();
        boolean validAtChanged = r.validAt() != null && !sameInstant(r.validAt(), existingValidAt);
        boolean changed = !r.hash().equals(existing.get("hash").asString(null))
                || !r.epistemic().name().equals(existing.get("epistemic").asString(null))
                || validAtChanged;
        List<Map<String, Object>> newEvidence = newEvidence(ctx, r.evidence());

        if (current && !changed) {
            if (newEvidence.isEmpty()) {
                return new ItemOutcome("RELATIONSHIP", r.id(), type, Outcome.UNCHANGED, version, null);
            }
            long seq = ctx.nextSeq();
            List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, r.id(), newEvidence, seq);
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.EVIDENCE_ADDED, SubjectKind.RELATIONSHIP, r.id(), type,
                    version, details("evidenceIds", evidenceIds)));
            return new ItemOutcome("RELATIONSHIP", r.id(), type, Outcome.EVIDENCE_ADDED, version, seq);
        }

        long seq = ctx.nextSeq();
        long newVersion = version + 1;
        Map<String, Object> oldProps = domainProperties(existing.get("props").asMap());
        Map<String, Object> props = withRemovals(oldProps, r.properties());
        Object validAt = r.validAt() != null ? r.validAt() : (current ? existingValidAt : ctx.now());
        updateAssertion(ctx, r.id(), props, newVersion, r.hash(), r.epistemic().name(), "CURRENT", validAt, null, null,
                seq, r.definition().version());
        ctx.reached(WriteStage.AFTER_ASSERTION);
        if (current) {
            int updated = ctx.single("""
                    MATCH (s:Entity {pke_id: $subjectId})-[r:$($type)]->(o:Entity {pke_id: $objectId})
                    WHERE r.pke_relId = $id
                    SET r += $props, r.pke_epistemic = $epistemic
                    RETURN count(r) AS updated""", params("subjectId", r.subjectId(), "objectId", r.objectId(),
                    "type", type, "id", r.id(), "props", props, "epistemic", r.epistemic().name()))
                    .orElseThrow().get("updated").asInt();
            if (updated != 1) {
                throw new IllegalStateException("Integrity violation: current assertion " + r.id() + " has " + updated
                        + " native edges; run GET /api/integrity");
            }
            ctx.reached(WriteStage.AFTER_EDGE);
        } else {
            createEdge(ctx, r, type);
        }
        List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, r.id(), newEvidence, seq);
        writeAssertionVersion(ctx, r.id(), newVersion, seq, "CURRENT", r.properties(), r.hash(), r.epistemic().name(),
                validAt, null, r.definition().version());
        String eventType = current ? ChangeEventTypes.RELATIONSHIP_UPDATED : ChangeEventTypes.RELATIONSHIP_REVALIDATED;
        ctx.emit(new PendingEvent(seq, eventType, SubjectKind.RELATIONSHIP, r.id(), type, newVersion,
                details("fromVersion", version, "hash", r.hash(), "changedKeys", changedKeys(oldProps, r.properties()),
                        "epistemic", r.epistemic().name(), "evidenceIds", evidenceIds)));
        return new ItemOutcome("RELATIONSHIP", r.id(), type, current ? Outcome.UPDATED : Outcome.REVALIDATED, newVersion, seq);
    }

    private void createEdge(WriteContext ctx, PreparedRelationship r, String type) {
        ctx.run("""
                MATCH (s:Entity {pke_id: $subjectId}), (o:Entity {pke_id: $objectId})
                CREATE (s)-[r:$($type)]->(o)
                SET r.pke_relId = $id, r.pke_epistemic = $epistemic, r.pke_category = $category
                SET r += $props""", params("subjectId", r.subjectId(), "objectId", r.objectId(), "type", type,
                "id", r.id(), "epistemic", r.epistemic().name(), "category", r.definition().category(),
                "props", r.properties()));
        ctx.reached(WriteStage.AFTER_EDGE);
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
        List<String> evidenceIds = writeEvidence(ctx, SubjectKind.RELATIONSHIP, inv.id(), newEvidence(ctx, inv.evidence()), seq);
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
                  CREATE (s)-[:SUPPORTED_BY]->(e)"""
                : """
                  MATCH (s:Assertion {pke_id: $subjectId})
                  UNWIND $evidence AS ev
                  CREATE (e:Evidence) SET e = ev
                  CREATE (s)-[:SUPPORTED_BY]->(e)""";
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
    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }
}
