package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.EpistemicStatus;
import java.util.List;
import java.util.Map;

/**
 * The write model accepted by {@link KnowledgeWriter}: one {@link ChangeSet} is applied in one transaction.
 * An entity or relationship write states the <em>full current</em> domain properties; properties left out are
 * removed. History keeps the previous values.
 */
public final class KnowledgeChanges {

    private KnowledgeChanges() {
    }

    public record ChangeSet(String operationId, List<EntityWrite> entities, List<RelationshipWrite> relationships,
                            List<RelationshipInvalidation> invalidations) {
        public ChangeSet {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relationships = relationships == null ? List.of() : List.copyOf(relationships);
            invalidations = invalidations == null ? List.of() : List.copyOf(invalidations);
        }

        public static ChangeSet of(EntityWrite... entities) {
            return new ChangeSet(null, List.of(entities), List.of(), List.of());
        }

        public boolean isEmpty() {
            return entities.isEmpty() && relationships.isEmpty() && invalidations.isEmpty();
        }
    }

    /**
     * @param id       required for {@code explicit} identity, optional otherwise; must match the computed id for
     *                 {@code naturalKey} types
     * @param validAt  optional ISO-8601 business validity start
     */
    public record EntityWrite(String type, String id, Map<String, Object> properties, EpistemicStatus epistemic,
                              String validAt, List<EvidenceInput> evidence) {
        public EntityWrite {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
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
     * Provenance of one fact (MASTER_SPEC §8). {@code confidence} is model-reported and stored as uncalibrated.
     * {@code epistemic} defaults to the epistemic status of the write it belongs to.
     */
    public record EvidenceInput(String sourceArtifactId, String sourceVersion, String location, String contentHash,
                                String extractionMethod, String pipelineVersion, String skillVersion,
                                String modelProvider, String modelId, String excerpt, String verification,
                                Double confidence, EpistemicStatus epistemic) {

        /** Evidence from a deterministic extractor at a source location. */
        public static EvidenceInput deterministic(String sourceArtifactId, String sourceVersion, String location,
                                                  String extractionMethod) {
            return new EvidenceInput(sourceArtifactId, sourceVersion, location, null, extractionMethod, null, null,
                    null, null, null, null, null, EpistemicStatus.EXTRACTED_DETERMINISTIC);
        }
    }

    public enum Outcome { CREATED, UPDATED, UNCHANGED, EVIDENCE_ADDED, INVALIDATED, REVALIDATED }

    public record ItemOutcome(String kind, String id, String type, Outcome outcome, Long version, Long seq) {
    }

    public record ChangeResult(String operationId, List<ItemOutcome> items, Long firstSeq, Long lastSeq) {

        public int eventCount() {
            return firstSeq == null ? 0 : (int) (lastSeq - firstSeq + 1);
        }
    }
}
