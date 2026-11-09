package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.GraphIntegrityService;
import com.omnicore.pke.engine.graph.GraphIntegrityService.IntegrityReport;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.graph.KnowledgeQueryService;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EntityView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.EventView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.Page;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.RelationshipView;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.VersionView;
import com.omnicore.pke.engine.graph.KnowledgeWriter;
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

/** Knowledge read and write endpoints. All writes go through {@link KnowledgeWriter}. */
@RestController
@RequestMapping("/api")
class KnowledgeController {

    private final KnowledgeWriter writer;
    private final KnowledgeQueryService queries;
    private final GraphIntegrityService integrity;

    KnowledgeController(KnowledgeWriter writer, KnowledgeQueryService queries, GraphIntegrityService integrity) {
        this.writer = writer;
        this.queries = queries;
        this.integrity = integrity;
    }

    /** Applies entities, relationships and invalidations in one transaction. */
    @PostMapping("/knowledge/changes")
    ResponseEntity<ChangeResult> apply(@RequestBody ChangeSet changes,
                                       @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return respond(writer.apply(changes, Actors.of(actor)));
    }

    @PostMapping("/entities")
    ResponseEntity<ChangeResult> upsertEntity(@RequestBody EntityWrite entity,
                                              @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return respond(writer.apply(new ChangeSet(null, List.of(entity), List.of(), List.of()), Actors.of(actor)));
    }

    @GetMapping("/entities")
    Page<EntityView> entities(@RequestParam(required = false) String type,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "50") int limit) {
        return queries.entities(type, offset, limit);
    }

    @GetMapping("/entities/{id}")
    EntityView entity(@PathVariable String id) {
        return queries.entity(id);
    }

    @GetMapping("/entities/{id}/versions")
    List<VersionView> entityVersions(@PathVariable String id) {
        return queries.entityVersions(id);
    }

    @GetMapping("/entities/{id}/relationships")
    List<RelationshipView> entityRelationships(@PathVariable String id,
                                               @RequestParam(defaultValue = "false") boolean includeInvalid) {
        return queries.relationshipsOf(id, includeInvalid);
    }

    @PostMapping("/relationships")
    ResponseEntity<ChangeResult> upsertRelationship(@RequestBody RelationshipWrite relationship,
                                                    @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return respond(writer.apply(new ChangeSet(null, List.of(), List.of(relationship), List.of()), Actors.of(actor)));
    }

    @GetMapping("/relationships/{id}")
    RelationshipView relationship(@PathVariable String id) {
        return queries.relationship(id);
    }

    @GetMapping("/relationships/{id}/versions")
    List<VersionView> relationshipVersions(@PathVariable String id) {
        return queries.relationshipVersions(id);
    }

    /** Ends the relationship's current validity. The assertion, its evidence and its history are kept. */
    @PostMapping("/relationships/{id}/invalidate")
    ChangeResult invalidate(@PathVariable String id, @RequestBody(required = false) RelationshipInvalidation body,
                            @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        RelationshipInvalidation request = body == null
                ? new RelationshipInvalidation(id, null, null, null)
                : new RelationshipInvalidation(id, body.reason(), body.invalidAt(), body.evidence());
        return writer.invalidate(request, Actors.of(actor));
    }

    @GetMapping("/history/events")
    List<EventView> events(@RequestParam(defaultValue = "0") long afterSeq,
                           @RequestParam(defaultValue = "100") int limit,
                           @RequestParam(required = false) String subjectId) {
        return queries.events(afterSeq, limit, subjectId);
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
