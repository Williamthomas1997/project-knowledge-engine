package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.graph.GraphTransactions.WriteContext;
import com.omnicore.pke.engine.graph.KnowledgeWriter.Prepared;
import com.omnicore.pke.engine.graph.KnowledgeWriter.PreparedEntity;
import com.omnicore.pke.engine.graph.KnowledgeWriter.PreparedInvalidation;
import com.omnicore.pke.engine.graph.KnowledgeWriter.PreparedRelationship;
import com.omnicore.pke.engine.graph.PendingEvent.SubjectKind;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ChangeEventTypes;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.ItemOutcome;
import com.omnicore.pke.engine.store.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.store.Proposals;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proposals on Neo4j (ADR-015). Proposal content is stored as {@code :ProposalItem} nodes (the validated write as
 * JSON, the normalised properties and a version history), written through the single writer with change events.
 * Items never carry the {@code :Entity} or {@code :Assertion} label and have no native edges, so every existing
 * read (entities, relationships, retrieval, templates, context, integrity, vector sync) ignores them. Approval
 * replays the stored writes through {@link KnowledgeWriter} in the same transaction that records the decision.
 */
@Service
public class Neo4jProposalStore implements Proposals {

    static final String ENTITY = "ENTITY";
    static final String RELATIONSHIP = "RELATIONSHIP";
    static final String ENTITY_INVALIDATION = "ENTITY_INVALIDATION";
    static final String RELATIONSHIP_INVALIDATION = "RELATIONSHIP_INVALIDATION";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final GraphTransactions transactions;
    private final GraphReader reader;
    private final KnowledgeWriter writer;

    public Neo4jProposalStore(GraphTransactions transactions, GraphReader reader, KnowledgeWriter writer) {
        this.transactions = transactions;
        this.reader = reader;
        this.writer = writer;
    }

    static String itemId(String proposalId, String kind, String targetId) {
        return Proposals.scopeOf(proposalId) + ":" + kind + ":" + targetId;
    }

    // ---------------------------------------------------------------- proposals

    @Override
    public ProposalView create(NewProposal proposal, String actor) {
        String id = proposal.id() == null || proposal.id().isBlank() ? PkeIds.uuidV7().toString() : proposal.id();
        if (!ID.matcher(id).matches()) {
            throw ValidationFailedException.of("/id", "must match " + ID.pattern());
        }
        if (proposal.title() == null || proposal.title().isBlank()) {
            throw ValidationFailedException.of("/title", "is required");
        }
        transactions.write(actor, null, ctx -> {
            if (ctx.single("MATCH (p:Proposal {pke_id: $id}) RETURN p.pke_id AS id", Map.of("id", id)).isPresent()) {
                throw new ConflictException("Proposal " + id + " already exists");
            }
            long seq = ctx.nextSeq();
            Map<String, Object> props = new HashMap<>();
            props.put("pke_id", id);
            props.put("pke_scope", Proposals.scopeOf(id));
            props.put("title", proposal.title());
            props.put("description", proposal.description());
            props.put("sourceRun", proposal.sourceRun());
            props.put("status", "OPEN");
            props.put("createdBy", ctx.actor());
            props.put("createdAt", ctx.now());
            props.put("createdSeq", seq);
            ctx.run("CREATE (p:Proposal) SET p = $props", Map.of("props", props));
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.PROPOSAL_CREATED, SubjectKind.PROPOSAL, id, "Proposal", null,
                    details("scope", Proposals.scopeOf(id), "sourceRun", proposal.sourceRun())));
            return null;
        });
        return get(id);
    }

    @Override
    public ProposalView get(String id) {
        return reader.single("""
                        MATCH (p:Proposal {pke_id: $id})
                        OPTIONAL MATCH (p)-[:HAS_ITEM]->(i:ProposalItem)
                        RETURN properties(p) AS p, count(i) AS items""", Map.of("id", id))
                .map(r -> view(map(r.get("p")), ((Number) r.get("items")).longValue()))
                .orElseThrow(() -> new NotFoundException("Proposal " + id + " does not exist"));
    }

    @Override
    public List<ProposalView> list(String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        return reader.query("""
                        MATCH (p:Proposal) WHERE $status IS NULL OR p.status = $status
                        OPTIONAL MATCH (p)-[:HAS_ITEM]->(i:ProposalItem)
                        RETURN properties(p) AS p, count(i) AS items ORDER BY p.createdSeq DESC""", params)
                .stream().map(r -> view(map(r.get("p")), ((Number) r.get("items")).longValue())).toList();
    }

    // ---------------------------------------------------------------- writes in proposal scope

    private record Item(String kind, String targetId, String type, String subjectId, String objectId,
                        Map<String, Object> properties, String propsHash, String epistemic, String payloadJson) {
    }

    @Override
    public ChangeResult record(String proposalId, ChangeSet changes, String actor) {
        List<Violation> violations = new ArrayList<>();
        if (!changes.retractions().isEmpty()) {
            violations.add(new Violation("/retractions", "scope retractions are not allowed in a proposal"));
        }
        if (Boolean.TRUE.equals(changes.skipMissingEndpoints())) {
            violations.add(new Violation("/skipMissingEndpoints", "is not allowed in a proposal"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Change set rejected", violations);
        }
        Prepared prepared = writer.validate(changes);
        List<Item> items = items(prepared, changes);
        String operationId = changes.operationId() != null ? changes.operationId() : PkeIds.uuidV7().toString();

        return transactions.write(actor == null ? "unknown" : actor, operationId, ctx -> {
            requireOpen(ctx, proposalId);
            checkTargets(ctx, proposalId, items);
            List<ItemOutcome> outcomes = new ArrayList<>();
            for (Item item : items) {
                outcomes.add(writeItem(ctx, proposalId, item));
            }
            List<Long> seqs = outcomes.stream().map(ItemOutcome::seq).filter(Objects::nonNull).toList();
            return new ChangeResult(operationId, outcomes, seqs.isEmpty() ? null : seqs.getFirst(),
                    seqs.isEmpty() ? null : seqs.getLast());
        });
    }

    private static List<Item> items(Prepared p, ChangeSet changes) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < p.entities().size(); i++) {
            PreparedEntity e = p.entities().get(i);
            items.add(new Item(ENTITY, e.computedId(), e.definition().name(), null, null, e.properties(), e.hash(),
                    e.epistemic().name(), JSON.writeValueAsString(changes.entities().get(i))));
        }
        for (int i = 0; i < p.relationships().size(); i++) {
            PreparedRelationship r = p.relationships().get(i);
            String id = PkeIds.relationshipId(r.definition().name(), r.subjectId(), r.objectId(), r.discriminator());
            items.add(new Item(RELATIONSHIP, id, r.definition().name(), r.subjectId(), r.objectId(), r.properties(),
                    r.hash(), r.epistemic().name(), JSON.writeValueAsString(changes.relationships().get(i))));
        }
        for (int i = 0; i < p.invalidations().size(); i++) {
            PreparedInvalidation inv = p.invalidations().get(i);
            items.add(new Item(RELATIONSHIP_INVALIDATION, inv.id(), null, null, null, Map.of(), null, null,
                    JSON.writeValueAsString(changes.invalidations().get(i))));
        }
        for (int i = 0; i < p.entityInvalidations().size(); i++) {
            PreparedInvalidation inv = p.entityInvalidations().get(i);
            items.add(new Item(ENTITY_INVALIDATION, inv.id(), null, null, null, Map.of(), null, null,
                    JSON.writeValueAsString(changes.entityInvalidations().get(i))));
        }
        return items;
    }

    /** Relationship endpoints must exist in main or in this proposal; invalidation targets must exist in main. */
    private static void checkTargets(WriteContext ctx, String proposalId, List<Item> items) {
        Set<String> proposedEntities = new HashSet<>();
        items.stream().filter(i -> ENTITY.equals(i.kind())).forEach(i -> proposedEntities.add(i.targetId()));
        List<Violation> violations = new ArrayList<>();
        for (Item item : items) {
            switch (item.kind()) {
                case RELATIONSHIP -> {
                    for (String endpoint : List.of(item.subjectId(), item.objectId())) {
                        if (!proposedEntities.contains(endpoint) && !entityExists(ctx, proposalId, endpoint)) {
                            violations.add(new Violation("/relationships", "entity " + endpoint
                                    + " exists neither in main nor in proposal " + proposalId));
                        }
                    }
                }
                case ENTITY_INVALIDATION -> {
                    if (ctx.single("MATCH (n:Entity {pke_id: $id}) RETURN n.pke_id AS id", Map.of("id", item.targetId())).isEmpty()) {
                        violations.add(new Violation("/entityInvalidations", "entity " + item.targetId() + " does not exist in main"));
                    }
                }
                case RELATIONSHIP_INVALIDATION -> {
                    if (ctx.single("MATCH (a:Assertion {pke_id: $id}) RETURN a.pke_id AS id", Map.of("id", item.targetId())).isEmpty()) {
                        violations.add(new Violation("/invalidations", "relationship " + item.targetId() + " does not exist in main"));
                    }
                }
                default -> {
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Change set rejected", violations);
        }
    }

    private static boolean entityExists(WriteContext ctx, String proposalId, String id) {
        return ctx.single("""
                OPTIONAL MATCH (n:Entity {pke_id: $id})
                OPTIONAL MATCH (i:ProposalItem {pke_id: $itemId}) WHERE i.pke_status = 'CURRENT'
                RETURN n IS NOT NULL OR i IS NOT NULL AS found""",
                Map.of("id", id, "itemId", itemId(proposalId, ENTITY, id))).orElseThrow().get("found").asBoolean();
    }

    private static ItemOutcome writeItem(WriteContext ctx, String proposalId, Item item) {
        String id = itemId(proposalId, item.kind(), item.targetId());
        String contentHash = CanonicalJson.sha256(Map.of("payload", item.payloadJson()));
        String propsJson = CanonicalJson.write(item.properties());
        Record existing = ctx.single("""
                MATCH (i:ProposalItem {pke_id: $id})
                RETURN i.pke_version AS version, i.contentHash AS hash""", Map.of("id", id)).orElse(null);
        String outcomeKind = item.kind();
        if (existing != null && contentHash.equals(existing.get("hash").asString(null))) {
            return new ItemOutcome(outcomeKind, item.targetId(), item.type(), Outcome.UNCHANGED,
                    existing.get("version").asLong(), null);
        }
        long seq = ctx.nextSeq();
        long version = existing == null ? 1 : existing.get("version").asLong() + 1;
        Map<String, Object> props = new HashMap<>();
        props.put("pke_id", id);
        props.put("pke_scope", Proposals.scopeOf(proposalId));
        props.put("proposalId", proposalId);
        props.put("kind", item.kind());
        props.put("targetId", item.targetId());
        props.put("pke_type", item.type());
        props.put("subjectId", item.subjectId());
        props.put("objectId", item.objectId());
        props.put("propsJson", propsJson);
        props.put("pke_propsHash", item.propsHash());
        props.put("pke_epistemic", item.epistemic());
        props.put("payloadJson", item.payloadJson());
        props.put("contentHash", contentHash);
        props.put("pke_version", version);
        props.put("pke_status", "CURRENT");
        props.put("pke_updatedSeq", seq);
        props.put("recordedBy", ctx.actor());
        if (existing == null) {
            props.put("pke_createdSeq", seq);
            ctx.run("""
                    MATCH (p:Proposal {pke_id: $proposalId})
                    CREATE (p)-[:HAS_ITEM]->(i:ProposalItem) SET i = $props""",
                    Map.of("proposalId", proposalId, "props", props));
        } else {
            ctx.run("""
                    MATCH (i:ProposalItem {pke_id: $id})
                    OPTIONAL MATCH (i)-[:HAS_VERSION]->(prev:ProposalItemVersion {version: $previous})
                    SET prev.validToSeq = $seq
                    SET i += $props""", Map.of("id", id, "props", props, "previous", version - 1, "seq", seq));
        }
        ctx.run("""
                MATCH (i:ProposalItem {pke_id: $id})
                CREATE (i)-[:HAS_VERSION]->(:ProposalItemVersion {pke_id: $versionId, itemId: $id, version: $version,
                        propsJson: $propsJson, payloadJson: $payloadJson, validFromSeq: $seq, recordedAt: $at})""",
                Map.of("id", id, "versionId", PkeIds.versionId(id, version), "version", version, "propsJson", propsJson,
                        "payloadJson", item.payloadJson(), "seq", seq, "at", ctx.now()));
        ctx.emit(new PendingEvent(seq, existing == null ? ChangeEventTypes.PROPOSAL_ITEM_RECORDED
                : ChangeEventTypes.PROPOSAL_ITEM_UPDATED, SubjectKind.PROPOSAL_ITEM, id,
                item.type() == null ? item.kind() : item.type(), version,
                details("proposalId", proposalId, "kind", item.kind(), "targetId", item.targetId(),
                        "hash", item.propsHash())));
        return new ItemOutcome(outcomeKind, item.targetId(), item.type(),
                existing == null ? Outcome.CREATED : Outcome.UPDATED, version, seq);
    }

    // ---------------------------------------------------------------- diff

    @Override
    public Diff diff(String proposalId) {
        ProposalView proposal = get(proposalId);
        List<DiffItem> entities = new ArrayList<>();
        List<DiffItem> relationships = new ArrayList<>();
        for (Map<String, Object> row : reader.query("""
                MATCH (:Proposal {pke_id: $id})-[:HAS_ITEM]->(i:ProposalItem)
                OPTIONAL MATCH (n:Entity {pke_id: i.targetId}) WHERE i.kind IN ['ENTITY', 'ENTITY_INVALIDATION']
                OPTIONAL MATCH (a:Assertion {pke_id: i.targetId}) WHERE i.kind IN ['RELATIONSHIP', 'RELATIONSHIP_INVALIDATION']
                RETURN properties(i) AS i, properties(n) AS n, properties(a) AS a
                ORDER BY i.kind, i.pke_type, i.targetId""", Map.of("id", proposalId))) {
            Map<String, Object> i = map(row.get("i"));
            String kind = (String) i.get("kind");
            boolean entityKind = kind.startsWith(ENTITY);
            Map<String, Object> main = entityKind ? map(row.get("n")) : map(row.get("a"));
            String change;
            if (kind.endsWith("INVALIDATION")) {
                change = "REMOVED";
            } else if (main.isEmpty()) {
                change = "ADDED";
            } else {
                boolean same = Objects.equals(main.get("pke_propsHash"), i.get("pke_propsHash"))
                        && !"INVALID".equals(main.get("pke_status"));
                change = same ? "UNCHANGED" : "CHANGED";
            }
            String type = i.get("pke_type") != null ? (String) i.get("pke_type") : (String) main.get("pke_type");
            DiffItem item = new DiffItem(kind, (String) i.get("targetId"), type, change, (String) i.get("pke_status"),
                    ((Number) i.get("pke_version")).longValue(),
                    (String) (i.get("subjectId") != null ? i.get("subjectId") : main.get("pke_subjectId")),
                    (String) (i.get("objectId") != null ? i.get("objectId") : main.get("pke_objectId")),
                    json((String) i.get("propsJson")), main.isEmpty() ? null : KnowledgeWriter.domainProperties(main));
            (entityKind ? entities : relationships).add(item);
        }
        return new Diff(proposal, section(entities), section(relationships));
    }

    private static DiffSection section(List<DiffItem> items) {
        return new DiffSection(
                items.stream().filter(i -> "ADDED".equals(i.change())).toList(),
                items.stream().filter(i -> "CHANGED".equals(i.change())).toList(),
                items.stream().filter(i -> "REMOVED".equals(i.change())).toList(),
                items.stream().filter(i -> "UNCHANGED".equals(i.change())).toList());
    }

    // ---------------------------------------------------------------- decisions

    @Override
    public Decision approve(String proposalId, String approver, String comment) {
        String actor = approver == null ? "unknown" : approver;
        ProposalView before = get(proposalId);
        if (!"OPEN".equals(before.status())) {
            throw new ConflictException("Proposal " + proposalId + " is " + before.status());
        }
        List<EntityWrite> entities = new ArrayList<>();
        List<RelationshipWrite> relationships = new ArrayList<>();
        List<RelationshipInvalidation> invalidations = new ArrayList<>();
        List<EntityInvalidation> entityInvalidations = new ArrayList<>();
        for (Map<String, Object> row : reader.query("""
                MATCH (:Proposal {pke_id: $id})-[:HAS_ITEM]->(i:ProposalItem {pke_status: 'CURRENT'})
                RETURN i.kind AS kind, i.payloadJson AS payload ORDER BY i.pke_createdSeq""", Map.of("id", proposalId))) {
            String payload = (String) row.get("payload");
            switch ((String) row.get("kind")) {
                case ENTITY -> entities.add(JSON.readValue(payload, EntityWrite.class));
                case RELATIONSHIP -> relationships.add(JSON.readValue(payload, RelationshipWrite.class));
                case RELATIONSHIP_INVALIDATION -> invalidations.add(JSON.readValue(payload, RelationshipInvalidation.class));
                case ENTITY_INVALIDATION -> entityInvalidations.add(JSON.readValue(payload, EntityInvalidation.class));
                default -> throw new IllegalStateException("Unknown proposal item kind " + row.get("kind"));
            }
        }
        String operationId = Proposals.scopeOf(proposalId) + ":approve";
        ChangeSet replay = new ChangeSet(operationId, entities, relationships, invalidations, List.of(), false,
                entityInvalidations);
        Prepared prepared = replay.isEmpty() ? null : writer.validate(replay);

        ChangeResult applied = transactions.write(actor, operationId, ctx -> {
            requireOpen(ctx, proposalId);
            ctx.tag("proposalId", proposalId);
            ctx.tag("approvedBy", actor);
            ChangeResult result = prepared == null ? new ChangeResult(operationId, List.of(), null, null)
                    : writer.applyPrepared(ctx, prepared, replay, operationId);
            long seq = ctx.nextSeq();
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "APPROVED");
            fields.put("decidedBy", actor);
            fields.put("decidedAt", ctx.now());
            fields.put("reason", comment);
            fields.put("appliedFirstSeq", result.firstSeq());
            fields.put("appliedLastSeq", result.lastSeq());
            fields.put("decidedSeq", seq);
            ctx.run("""
                    MATCH (p:Proposal {pke_id: $id}) SET p += $fields
                    WITH p
                    OPTIONAL MATCH (p)-[:HAS_ITEM]->(i:ProposalItem {pke_status: 'CURRENT'})
                    SET i.pke_status = 'PROMOTED', i.pke_updatedSeq = $seq""",
                    Map.of("id", proposalId, "fields", fields, "seq", seq));
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.PROPOSAL_APPROVED, SubjectKind.PROPOSAL, proposalId,
                    "Proposal", null, details("itemCount", entities.size() + relationships.size()
                    + invalidations.size() + entityInvalidations.size(),
                    "appliedFirstSeq", result.firstSeq(), "appliedLastSeq", result.lastSeq())));
            return result;
        });
        return new Decision(get(proposalId), applied);
    }

    @Override
    public ProposalView reject(String proposalId, String actor, String reason) {
        String by = actor == null ? "unknown" : actor;
        transactions.write(by, Proposals.scopeOf(proposalId) + ":reject", ctx -> {
            requireOpen(ctx, proposalId);
            long seq = ctx.nextSeq();
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "REJECTED");
            fields.put("decidedBy", by);
            fields.put("decidedAt", ctx.now());
            fields.put("reason", reason);
            fields.put("decidedSeq", seq);
            ctx.run("""
                    MATCH (p:Proposal {pke_id: $id}) SET p += $fields
                    WITH p
                    OPTIONAL MATCH (p)-[:HAS_ITEM]->(i:ProposalItem {pke_status: 'CURRENT'})
                    SET i.pke_status = 'REJECTED', i.pke_updatedSeq = $seq""",
                    Map.of("id", proposalId, "fields", fields, "seq", seq));
            ctx.emit(new PendingEvent(seq, ChangeEventTypes.PROPOSAL_REJECTED, SubjectKind.PROPOSAL, proposalId,
                    "Proposal", null, details("proposalId", proposalId)));
            return null;
        });
        return get(proposalId);
    }

    // ---------------------------------------------------------------- helpers

    private static void requireOpen(WriteContext ctx, String proposalId) {
        String status = ctx.single("MATCH (p:Proposal {pke_id: $id}) RETURN p.status AS status", Map.of("id", proposalId))
                .map(r -> r.get("status").asString())
                .orElseThrow(() -> new NotFoundException("Proposal " + proposalId + " does not exist"));
        if (!"OPEN".equals(status)) {
            throw new ConflictException("Proposal " + proposalId + " is " + status + "; only OPEN proposals accept changes");
        }
    }

    private static ProposalView view(Map<String, Object> p, long items) {
        return new ProposalView((String) p.get("pke_id"), (String) p.get("pke_scope"), (String) p.get("title"),
                (String) p.get("description"), (String) p.get("sourceRun"), (String) p.get("status"),
                (String) p.get("createdBy"), p.get("createdAt"), (String) p.get("decidedBy"), p.get("decidedAt"),
                (String) p.get("reason"), items, longOrNull(p.get("appliedFirstSeq")), longOrNull(p.get("appliedLastSeq")));
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String json) {
        return json == null ? Map.of() : JSON.readValue(json, TreeMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
