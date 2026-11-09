package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SectionView;
import com.omnicore.pke.engine.graph.SourceQueryService.SourceView;
import com.omnicore.pke.engine.graph.SourceQueryService.VersionView;
import com.omnicore.pke.engine.graph.SourceWriter;
import com.omnicore.pke.engine.graph.SourceWriter.SourceResult;
import com.omnicore.pke.engine.ingest.DocumentIngestionService;
import com.omnicore.pke.engine.ingest.DocumentIngestionService.DocumentResult;
import com.omnicore.pke.engine.ingest.RepositoryIngestionService;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Sources: document uploads, repository registration, versions, sections and section search. */
@RestController
@RequestMapping("/api/sources")
class SourceController {

    private final DocumentIngestionService documents;
    private final RepositoryIngestionService repositories;
    private final SourceQueryService queries;
    private final SourceWriter writer;

    SourceController(DocumentIngestionService documents, RepositoryIngestionService repositories, SourceQueryService queries,
                     SourceWriter writer) {
        this.documents = documents;
        this.repositories = repositories;
        this.queries = queries;
        this.writer = writer;
    }

    record RepositoryRequest(String name, String path, String serviceName) {
    }

    record RemoveRequest(String reason) {
    }

    /** Uploads a document version (multipart field {@code file}; optional {@code key} and {@code name}). */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentResult> upload(@RequestPart("file") MultipartFile file,
                                          @RequestParam(required = false) String key,
                                          @RequestParam(required = false) String name,
                                          @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        try {
            DocumentResult result = documents.ingest(file.getBytes(), file.getOriginalFilename(), key, name, Actors.of(actor));
            boolean created = result.source().outcome() == SourceWriter.Outcome.CREATED;
            return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Registers (or updates) a local Git repository: {@code {name, path, serviceName?}}. */
    @PostMapping("/repositories")
    ResponseEntity<SourceResult> register(@RequestBody RepositoryRequest request,
                                          @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        if (request == null) {
            throw ValidationFailedException.of("$", "body is required");
        }
        SourceResult result = repositories.register(request.name(), request.path(), request.serviceName(), Actors.of(actor));
        return ResponseEntity.status(result.outcome() == SourceWriter.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result);
    }

    @GetMapping
    List<SourceView> list(@RequestParam(required = false) String kind) {
        return queries.sources(kind);
    }

    @GetMapping("/{key}")
    SourceView source(@PathVariable String key) {
        return queries.byKey(key);
    }

    @GetMapping("/{key}/versions")
    List<VersionView> versions(@PathVariable String key) {
        return queries.versions(key);
    }

    /** Sections of the current version, or of {@code version} (a content hash). */
    @GetMapping("/{key}/sections")
    List<SectionView> sections(@PathVariable String key, @RequestParam(required = false) String version) {
        return queries.sections(key, version);
    }

    /** Marks a source as removed. Its versions, sections and evidence are kept for history. */
    @DeleteMapping("/{key}")
    SourceResult remove(@PathVariable String key, @RequestBody(required = false) RemoveRequest request,
                        @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return writer.remove(key, request == null ? null : request.reason(), Actors.of(actor));
    }

    /** Fulltext search over current document sections. */
    @GetMapping("/sections/search")
    List<SectionView> search(@RequestParam("q") String query, @RequestParam(defaultValue = "10") int limit) {
        if (query == null || query.isBlank()) {
            throw ValidationFailedException.of("/q", "is required");
        }
        return queries.searchSections(query, limit);
    }
}
