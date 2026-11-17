package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.GraphIntegrityService;
import com.omnicore.pke.engine.graph.GraphIntegrityService.IntegrityReport;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EntityView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EventView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.Page;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.RelationshipView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.VersionView;
import com.omnicore.pke.engine.store.GraphStore;
import com.omnicore.pke.engine.store.Proposals;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge read and write endpoints. All writes go through {@link GraphStore}, or through {@link Proposals} when
 * {@code scope=proposal:<id>} (ADR-015). Reads return scope {@code main} unless {@code scope} or
 * {@code includeProposals=true} widens them.
 */
@RestController
@RequestMapping("/api")
class KnowledgeController {

    private final GraphStore writer;
    private final KnowledgeQueryService queries;
    private final GraphIntegrityService integrity;
    private final Proposals proposals;

    KnowledgeController(GraphStore writer, KnowledgeQueryService queries, GraphIntegrityService integrity,
                        Proposals proposals) {
        this.writer = writer;
        this.queries = queries;
        this.integrity = integrity;
        this.proposals = proposals;
    }

    /** Applies the change set to main, or records it in the proposal named by {@code scope}. */
    private ChangeResult write(ChangeSet changes, String scope, String actor) {
        String proposalId = proposalId(scope);
        return proposalId == null ? writer.apply(changes, Actors.of(actor))
                : proposals.record(proposalId, changes, Actors.of(actor));
    }

    static String proposalId(String scope) {
        try {
            return Proposals.proposalIdOf(scope);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of("scope", e.getMessage());
        }
    }

    private List<String> scopes(String scope, boolean includeProposals) {
        List<String> scopes = new ArrayList<>();
        String proposalId = proposalId(scope);
        if (proposalId != null) {
            scopes.add(Proposals.scopeOf(proposalId));
        }
        if (includeProposals) {
            queries.openProposalScopes().stream().filter(s -> !scopes.contains(s)).forEach(scopes::add);
        }
        return scopes;
    }

    /** Applies entities, relationships and invalidations in one transaction. */
    @PostMapping("/knowledge/changes")
    ResponseEntity<ChangeResult> apply(@RequestBody ChangeSet changes,
                                       @RequestParam(required = false) String scope,
                                       @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return respond(write(changes, scope, actor));
    }

    @PostMapping("/entities")
    ResponseEntity<ChangeResult> upsertEntity(@RequestBody EntityWrite entity,
                                              @RequestParam(required = false) String scope,
                                              @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return respond(write(new ChangeSet(null, List.of(entity), List.of(), List.of()), scope, actor));
    }

    @GetMapping("/entities")
    Page<EntityView> entities(@RequestParam(required = false) String type,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "50") int limit,
                              @RequestParam(required = false) String scope,
                              @RequestParam(defaultValue = "false") boolean includeProposals) {
        return queries.entities(type, offset, limit, scopes(scope, includeProposals));
    }

    @GetMapping("/entities/{id}")
    EntityView entity(@PathVariable String id, @RequestParam(required = false) String scope,
                      @RequestParam(defaultValue = "false") boolean includeProposals) {
        return queries.entity(id, scopes(scope, includeProposals));
    }

    @GetMapping("/entities/{id}/versions")
    List<VersionView> entityVersions(@PathVariable String id) {
        return queries.entityVersions(id);
    }

    @GetMapping("/entities/{id}/relationships")
    List<RelationshipView> entityRelationships(@PathVariable String id,
                                               @RequestParam(defaultValue = "false") boolean includeInvalid,
                                               @RequestParam(required = false) String scope,
                                               @RequestParam(defaultValue = "false") boolean includeProposals) {
        return queries.relationshipsOf(id, includeInvalid, scopes(scope, includeProposals));
    }

    @PostMapping("/relationships")
    ResponseEntity<ChangeResult> upsertRelationship(@RequestBody RelationshipWrite relationship,
                                                    @RequestParam(required = false) String scope,
                                                    @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return respond(write(new ChangeSet(null, List.of(), List.of(relationship), List.of()), scope, actor));
    }

    @GetMapping("/relationships/{id}")
    RelationshipView relationship(@PathVariable String id, @RequestParam(required = false) String scope,
                                  @RequestParam(defaultValue = "false") boolean includeProposals) {
        return queries.relationship(id, scopes(scope, includeProposals));
    }

    @GetMapping("/relationships/{id}/versions")
    List<VersionView> relationshipVersions(@PathVariable String id) {
        return queries.relationshipVersions(id);
    }

    /** Ends the relationship's current validity. The assertion, its evidence and its history are kept. */
    @PostMapping("/relationships/{id}/invalidate")
    ChangeResult invalidate(@PathVariable String id, @RequestBody(required = false) RelationshipInvalidation body,
                            @RequestParam(required = false) String scope,
                            @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        RelationshipInvalidation request = body == null
                ? new RelationshipInvalidation(id, null, null, null)
                : new RelationshipInvalidation(id, body.reason(), body.invalidAt(), body.evidence());
        String proposalId = proposalId(scope);
        return proposalId == null ? writer.invalidate(request, Actors.of(actor))
                : proposals.record(proposalId, new ChangeSet(null, List.of(), List.of(), List.of(request)), Actors.of(actor));
    }

    @GetMapping("/history/events")
    List<EventView> events(@RequestParam(defaultValue = "0") long afterSeq,
                           @RequestParam(defaultValue = "100") int limit,
                           @RequestParam(required = false) String subjectId,
                           @RequestParam(required = false) Long toSeq,
                           @RequestParam(required = false) String operationId,
                           @RequestParam(required = false) String commit,
                           @RequestParam(required = false) String source) {
        return queries.events(new KnowledgeQueryService.EventFilter(afterSeq, toSeq, limit, subjectId, operationId, commit,
                source));
    }

    @GetMapping("/integrity")
    IntegrityReport integrity() {
        return integrity.check();
    }

    private static ResponseEntity<ChangeResult> respond(ChangeResult result) {
        boolean created = result.items().stream()
                .anyMatch(i -> i.outcome() == Outcome.CREATED);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}
