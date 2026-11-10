package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.graph.VectorCatalogStore;
import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.schema.DefinitionDocument;
import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.EntityTypeDefinition;
import com.omnicore.pke.engine.schema.PropertyDefinition;
import com.omnicore.pke.engine.schema.RelationshipTypeDefinition;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.CollectionRef;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Vector collections derived from the schema registry (ADR-007 §2): one content collection per
 * {@code embedding.indexFields} entry ({@code <Type>_<field>}), plus two system collections generated from the
 * registry: {@code CollectionCatalog} (what each collection holds and when to search it; searched first by the
 * router) and {@code GraphSchemaMap} (one record per entity and relationship type, for the Cypher fallback).
 */
@Service
public class Collections {

    public static final String CATALOG = "CollectionCatalog";
    public static final String SCHEMA_MAP = "GraphSchemaMap";
    private static final Logger log = LoggerFactory.getLogger(Collections.class);

    public record ContentCollection(String name, String type, String field, String description) {
    }

    private final SchemaRegistry schema;
    private final VectorCatalogStore store;
    private final EmbeddingGenerations generations;
    private final Vectors vectors;

    public Collections(SchemaRegistry schema, VectorCatalogStore store, EmbeddingGenerations generations, Vectors vectors) {
        this.schema = schema;
        this.store = store;
        this.generations = generations;
        this.vectors = vectors;
    }

    public List<ContentCollection> content() {
        List<ContentCollection> collections = new ArrayList<>();
        for (DefinitionDocument doc : schema.all(DefinitionKind.ENTITY_TYPE)) {
            EntityTypeDefinition type = (EntityTypeDefinition) doc.definition();
            if (type.embedding() == null) {
                continue;
            }
            for (String field : type.embedding().indexFields()) {
                String name = type.embedding().collection() != null && type.embedding().indexFields().size() == 1
                        ? type.embedding().collection() : type.name() + "_" + field;
                PropertyDefinition property = type.properties().get(field);
                String fieldDescription = property == null || property.description() == null ? field : property.description();
                collections.add(new ContentCollection(name, type.name(), field,
                        type.name() + " (" + type.description() + ") — field " + field + ": " + fieldDescription));
            }
        }
        return collections;
    }

    public Optional<ContentCollection> forField(String type, String field) {
        return content().stream().filter(c -> c.type().equals(type) && c.field().equals(field)).findFirst();
    }

    public CollectionRef ref(String name, Generation generation) {
        return new CollectionRef(name, generation.id(), generation.model(), generation.dimensions());
    }

    public static String recordId(String pkeId, String field, String generation) {
        return PkeIds.uuidV5(PkeIds.ENTITY_NAMESPACE, "vector|" + pkeId + "|" + field + "|" + generation).toString();
    }

    /** Collection nodes and the system collections' records, for every writable generation. Idempotent. */
    public synchronized void sync() {
        List<String> writable = generations.writable().stream().map(Generation::id).toList();
        List<ContentCollection> content = content();
        for (ContentCollection c : content) {
            store.syncCollection(c.name(), "content", c.type(), c.field(), c.description(), vectors.provider(), writable,
                    List.of(), List.of());
        }
        store.syncCollection(CATALOG, "system", null, null,
                "One record per collection: what it holds and when to search it. The retrieval router searches it first.",
                vectors.provider(), writable, content.stream().map(ContentCollection::name).toList(), List.of());
        List<String> types = schema.all(DefinitionKind.ENTITY_TYPE).stream().map(DefinitionDocument::name).toList();
        List<String> relTypes = schema.all(DefinitionKind.RELATIONSHIP_TYPE).stream().map(DefinitionDocument::name).toList();
        List<String> allTypes = new ArrayList<>(types);
        allTypes.addAll(relTypes);
        store.syncCollection(SCHEMA_MAP, "system", null, null,
                "One record per entity and relationship type: description, endpoints and query hints (the graph's map).",
                vectors.provider(), writable, List.of(), allTypes);
        for (Generation generation : generations.writable()) {
            syncSystemRecords(CATALOG, generation, catalogRecords(content, generation.id()));
            syncSystemRecords(SCHEMA_MAP, generation, schemaRecords(generation.id()));
        }
    }

    private void syncSystemRecords(String collection, Generation generation, List<VectorRecord> records) {
        String hash = CanonicalJson.sha256(records.stream().map(r -> List.of(r.id(), r.text())).toList());
        if (hash.equals(store.collectionHash(collection, generation.id()).orElse(null))) {
            return;
        }
        CollectionRef ref = ref(collection, generation);
        var existing = vectors.allIds(ref);
        var wanted = records.stream().map(VectorRecord::id).collect(Collectors.toSet());
        vectors.upsert(ref, records);
        vectors.delete(ref, existing.stream().filter(id -> !wanted.contains(id)).toList());
        store.setCollectionHash(collection, generation.id(), hash);
        log.info("System collection {} ({}): {} records", collection, generation.id(), records.size());
    }

    private static List<VectorRecord> catalogRecords(List<ContentCollection> content, String generation) {
        return content.stream().map(c -> new VectorRecord(recordId("collection:" + c.name(), "catalog", generation),
                "Collection " + c.name() + ": " + c.description() + ". Search it for questions about "
                        + words(c.type()) + " " + words(c.field()) + ".",
                metadata("collection:" + c.name(), "VectorCollection", CATALOG, Map.of("target", c.name())))).toList();
    }

    private List<VectorRecord> schemaRecords(String generation) {
        List<VectorRecord> records = new ArrayList<>();
        List<RelationshipTypeDefinition> relationships = schema.all(DefinitionKind.RELATIONSHIP_TYPE).stream()
                .map(d -> (RelationshipTypeDefinition) d.definition()).toList();
        for (DefinitionDocument doc : schema.all(DefinitionKind.ENTITY_TYPE)) {
            EntityTypeDefinition type = (EntityTypeDefinition) doc.definition();
            String out = relationships.stream().filter(r -> r.sourceTypes().contains(type.name()) || r.sourceTypes().contains("*"))
                    .map(r -> r.name() + "->" + String.join("|", r.targetTypes())).collect(Collectors.joining(", "));
            String in = relationships.stream().filter(r -> r.targetTypes().contains(type.name()))
                    .map(r -> String.join("|", r.sourceTypes()) + "-" + r.name() + "->").collect(Collectors.joining(", "));
            records.add(new VectorRecord(recordId("schema:" + type.name(), "schema", generation),
                    "Entity type " + type.name() + " (label :" + type.name() + ", category " + type.category() + "): "
                            + type.description() + " Properties: " + String.join(", ", type.properties().keySet())
                            + ". Outgoing: " + out + ". Incoming: " + in + ".",
                    metadata("schema:" + type.name(), "EntityType", SCHEMA_MAP, Map.of("target", type.name()))));
        }
        for (RelationshipTypeDefinition r : relationships) {
            records.add(new VectorRecord(recordId("schema:" + r.name(), "schema", generation),
                    "Relationship type " + r.name() + " (category " + r.category() + "): " + r.description() + " From "
                            + String.join("|", r.sourceTypes()) + " to " + String.join("|", r.targetTypes())
                            + ". Properties: " + String.join(", ", r.properties().keySet()) + ". Query hints: " + r.queryHints() + ".",
                    metadata("schema:" + r.name(), "RelationshipType", SCHEMA_MAP, Map.of("target", r.name()))));
        }
        return records;
    }

    static Map<String, Object> metadata(String pkeId, String type, String collection, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(extra);
        m.put("pke_id", pkeId);
        m.put("type", type);
        m.put("collection", collection);
        m.put("status", "CURRENT");
        return m;
    }

    private static String words(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }
}
