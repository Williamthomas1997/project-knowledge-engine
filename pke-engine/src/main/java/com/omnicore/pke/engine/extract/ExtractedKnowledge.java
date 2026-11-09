package com.omnicore.pke.engine.extract;

import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Candidate knowledge from one scope (usually a file), before it is written: entities, relationships, and what
 * could not be resolved. Duplicate entities and relationships within the scope are merged (relationship
 * properties are combined by {@link #relate}'s merge rule).
 */
public final class ExtractedKnowledge {

    private final String scope;
    private final Map<String, EntityWrite> entities = new LinkedHashMap<>();
    private final Map<String, RelationshipWrite> relationships = new LinkedHashMap<>();
    private final List<String> unresolved = new ArrayList<>();

    public ExtractedKnowledge(String scope) {
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }

    public List<EntityWrite> entities() {
        return List.copyOf(entities.values());
    }

    public List<RelationshipWrite> relationships() {
        return List.copyOf(relationships.values());
    }

    public List<String> unresolved() {
        return List.copyOf(unresolved);
    }

    /** Adds an entity; if the same key was already added, its evidence is merged and the first properties kept. */
    public EntityRef entity(String type, Map<String, Object> properties, List<String> naturalKey, EvidenceInput evidence) {
        Map<String, Object> key = new LinkedHashMap<>();
        naturalKey.forEach(k -> key.put(k, properties.get(k)));
        String id = type + CanonicalJson.write(key);
        EntityWrite existing = entities.get(id);
        if (existing == null) {
            entities.put(id, new EntityWrite(type, null, Map.copyOf(withoutNulls(properties)),
                    EpistemicStatus.EXTRACTED_DETERMINISTIC, null, List.of(evidence)));
        } else if (existing.evidence().stream().noneMatch(e -> e.equals(evidence))) {
            List<EvidenceInput> merged = new ArrayList<>(existing.evidence());
            merged.add(evidence);
            entities.put(id, new EntityWrite(type, null, existing.properties(), existing.epistemic(), null, merged,
                    existing.renamedFrom()));
        }
        return EntityRef.key(type, key);
    }

    /** Replaces the entity with the same key with a version that records a rename. */
    public void markRenamed(String type, Map<String, Object> key, EntityRef from) {
        String id = type + CanonicalJson.write(key);
        EntityWrite existing = entities.get(id);
        if (existing != null) {
            entities.put(id, new EntityWrite(type, null, existing.properties(), existing.epistemic(), null,
                    existing.evidence(), from));
        }
    }

    /**
     * Adds a relationship. A repeated relationship (same type, endpoints and properties other than {@code count})
     * increments {@code countProperty} when given, otherwise it is ignored.
     */
    public void relate(String type, EntityRef subject, EntityRef object, Map<String, Object> properties,
                       EvidenceInput evidence, String countProperty) {
        Map<String, Object> props = new LinkedHashMap<>(withoutNulls(properties));
        Map<String, Object> identity = new LinkedHashMap<>(props);
        if (countProperty != null) {
            identity.remove(countProperty);
        }
        String id = type + CanonicalJson.write(java.util.Arrays.asList(subject.id(), subject.type(), subject.key(),
                object.id(), object.type(), object.key(), identity));
        RelationshipWrite existing = relationships.get(id);
        if (existing != null) {
            if (countProperty != null) {
                Map<String, Object> merged = new LinkedHashMap<>(existing.properties());
                merged.put(countProperty, ((Number) merged.getOrDefault(countProperty, 1L)).longValue() + 1);
                relationships.put(id, new RelationshipWrite(type, subject, object, merged, existing.epistemic(), null,
                        existing.evidence()));
            }
            return;
        }
        if (countProperty != null) {
            props.putIfAbsent(countProperty, 1L);
        }
        relationships.put(id, new RelationshipWrite(type, subject, object, props, EpistemicStatus.EXTRACTED_DETERMINISTIC,
                null, List.of(evidence)));
    }

    public void unresolved(String item) {
        if (!unresolved.contains(item)) {
            unresolved.add(item);
        }
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((k, v) -> {
            if (v != null && !(v instanceof List<?> l && l.isEmpty())) {
                result.put(k, v);
            }
        });
        return result;
    }
}
