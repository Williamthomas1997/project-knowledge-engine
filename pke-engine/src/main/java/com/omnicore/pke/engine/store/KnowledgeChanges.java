package com.omnicore.pke.engine.store;

import com.omnicore.pke.engine.kernel.EpistemicStatus;
import java.util.List;
import java.util.Map;

/**
 * The write model accepted by {@link GraphStore}: one {@link ChangeSet} is applied in one transaction.
 * An entity or relationship write states the <em>full current</em> domain properties; properties left out are
 * removed. History keeps the previous values.
 */
public final class KnowledgeChanges {

    private KnowledgeChanges() {
    }

    /**
     * @param retractions scopes (e.g. source files) that were fully re-extracted by this change set: current
     *                    relationships evidenced only by such a scope and not written again here are invalidated
     * @param skipMissingEndpoints for extractors: a relationship whose endpoint entity does not exist is reported as
     *                    {@code SKIPPED} instead of failing the change set
     * @param entityInvalidations entities that stop being current (e.g. a superseded knowledge card); history is kept
     */
    public record ChangeSet(String operationId, List<EntityWrite> entities, List<RelationshipWrite> relationships,
                            List<RelationshipInvalidation> invalidations, List<ScopeRetraction> retractions,
                            Boolean skipMissingEndpoints, List<EntityInvalidation> entityInvalidations) {
        public ChangeSet {
            skipMissingEndpoints = Boolean.TRUE.equals(skipMissingEndpoints);
            entities = entities == null ? List.of() : List.copyOf(entities);
            relationships = relationships == null ? List.of() : List.copyOf(relationships);
            invalidations = invalidations == null ? List.of() : List.copyOf(invalidations);
            retractions = retractions == null ? List.of() : List.copyOf(retractions);
            entityInvalidations = entityInvalidations == null ? List.of() : List.copyOf(entityInvalidations);
        }

        public ChangeSet(String operationId, List<EntityWrite> entities, List<RelationshipWrite> relationships,
                         List<RelationshipInvalidation> invalidations, List<ScopeRetraction> retractions,
                         Boolean skipMissingEndpoints) {
            this(operationId, entities, relationships, invalidations, retractions, skipMissingEndpoints, List.of());
        }

        public ChangeSet(String operationId, List<EntityWrite> entities, List<RelationshipWrite> relationships,
                         List<RelationshipInvalidation> invalidations) {
            this(operationId, entities, relationships, invalidations, List.of(), false);
        }

        public static ChangeSet of(EntityWrite... entities) {
            return new ChangeSet(null, List.of(entities), List.of(), List.of());
        }

        public boolean isEmpty() {
            return entities.isEmpty() && relationships.isEmpty() && invalidations.isEmpty() && retractions.isEmpty()
                    && entityInvalidations.isEmpty();
        }
    }

    /**
     * @param id       required for {@code explicit} identity, optional otherwise; must match the computed id for
     *                 {@code naturalKey} types
     * @param validAt  optional ISO-8601 business validity start
     * @param renamedFrom the entity's previous identity when a rename or move was detected: the existing entity
     *                    keeps its {@code pke_id} and gains an alias for the new key (ADR-002)
     */
    public record EntityWrite(String type, String id, Map<String, Object> properties, EpistemicStatus epistemic,
                              String validAt, List<EvidenceInput> evidence, EntityRef renamedFrom) {
        public EntityWrite {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public EntityWrite(String type, String id, Map<String, Object> properties, EpistemicStatus epistemic,
                           String validAt, List<EvidenceInput> evidence) {
            this(type, id, properties, epistemic, validAt, evidence, null);
        }
    }

    /** Points at an entity by id, or by type + natural key (which yields the same id without a lookup). */
    public record EntityRef(String id, String type, Map<String, Object> key) {
        public static EntityRef id(String id) {
            return new EntityRef(id, null, null);
        }

        public static EntityRef key(String type, Map<String, Object> key) {
            return new EntityRef(null, type, key);
        }
    }

    public record RelationshipWrite(String type, EntityRef subject, EntityRef object, Map<String, Object> properties,
                                    EpistemicStatus epistemic, String validAt, List<EvidenceInput> evidence) {
        public RelationshipWrite {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record RelationshipInvalidation(String relationshipId, String reason, String invalidAt,
                                           List<EvidenceInput> evidence) {
        public RelationshipInvalidation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Ends the current validity of an entity ({@code pke_status = INVALID}, business {@code invalidAt}). The entity,
     * its versions and evidence are kept; writing the entity again makes it current again ({@code REVALIDATED}).
     */
    public record EntityInvalidation(String entityId, String reason, String invalidAt, List<EvidenceInput> evidence) {
        public EntityInvalidation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Provenance of one fact (MASTER_SPEC §8). {@code confidence} is model-reported and stored as uncalibrated.
     * {@code epistemic} defaults to the epistemic status of the write it belongs to.
     * <p>
     * {@code scope} (e.g. a file path) marks evidence from an extractor that re-reads the whole scope on every run.
     * Scoped evidence is identified by (subject, artifact, scope, method), so seeing the same fact again at a new
     * commit adds nothing and the first observation is kept. Unscoped evidence is identified by (subject, artifact,
     * version, location, method, content hash).
     * <p>
     * {@code promptHash} is the SHA-256 of the exact prompt an AI skill was given. A {@code location} that is the
     * {@code pke_id} of a document {@code :Section} links the evidence to that section ({@code CITES}).
     */
    public record EvidenceInput(String sourceArtifactId, String sourceVersion, String location, String contentHash,
                                String extractionMethod, String pipelineVersion, String skillVersion,
                                String modelProvider, String modelId, String excerpt, String verification,
                                Double confidence, EpistemicStatus epistemic, String scope, String promptHash) {

        public EvidenceInput(String sourceArtifactId, String sourceVersion, String location, String contentHash,
                             String extractionMethod, String pipelineVersion, String skillVersion,
                             String modelProvider, String modelId, String excerpt, String verification,
                             Double confidence, EpistemicStatus epistemic, String scope) {
            this(sourceArtifactId, sourceVersion, location, contentHash, extractionMethod, pipelineVersion, skillVersion,
                    modelProvider, modelId, excerpt, verification, confidence, epistemic, scope, null);
        }

        public EvidenceInput(String sourceArtifactId, String sourceVersion, String location, String contentHash,
                             String extractionMethod, String pipelineVersion, String skillVersion,
                             String modelProvider, String modelId, String excerpt, String verification,
                             Double confidence, EpistemicStatus epistemic) {
            this(sourceArtifactId, sourceVersion, location, contentHash, extractionMethod, pipelineVersion, skillVersion,
                    modelProvider, modelId, excerpt, verification, confidence, epistemic, null, null);
        }

        /** Scoped deterministic evidence: {@code scope} (usually a file) is re-extracted as a whole. */
        public static EvidenceInput scoped(String sourceArtifactId, String sourceVersion, String scope, String location,
                                           String extractionMethod, String excerpt) {
            return new EvidenceInput(sourceArtifactId, sourceVersion, location, null, extractionMethod, null, null,
                    null, null, excerpt, null, null, EpistemicStatus.EXTRACTED_DETERMINISTIC, scope);
        }

        /** Evidence from a deterministic extractor at a source location. */
        public static EvidenceInput deterministic(String sourceArtifactId, String sourceVersion, String location,
                                                  String extractionMethod) {
            return new EvidenceInput(sourceArtifactId, sourceVersion, location, null, extractionMethod, null, null,
                    null, null, null, null, null, EpistemicStatus.EXTRACTED_DETERMINISTIC);
        }
    }

    /** {@code scope} of {@code sourceArtifactId} was fully re-extracted by {@code extractionMethod}. */
    public record ScopeRetraction(String sourceArtifactId, String scope, String extractionMethod, String reason) {
    }

    public enum Outcome { CREATED, UPDATED, RENAMED, UNCHANGED, EVIDENCE_ADDED, INVALIDATED, REVALIDATED, SKIPPED }

    public record ItemOutcome(String kind, String id, String type, Outcome outcome, Long version, Long seq) {
    }

    public record ChangeResult(String operationId, List<ItemOutcome> items, Long firstSeq, Long lastSeq) {

        public int eventCount() {
            return firstSeq == null ? 0 : (int) (lastSeq - firstSeq + 1);
        }
    }
}
