package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.schema.DefinitionKind;
import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;
import com.omnicore.pke.engine.schema.SchemaService;
import com.omnicore.pke.engine.schema.SchemaService.DefinitionView;
import com.omnicore.pke.engine.schema.SchemaService.RegistrationResult;
import com.omnicore.pke.engine.schema.SchemaService.ValidationReport;
import com.omnicore.pke.engine.schema.SchemaService.VersionView;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Schema registry. Definitions are posted as YAML ({@code Content-Type: application/yaml}) or JSON, in the same
 * format as the files under {@code definitions/schema/}.
 */
@RestController
@RequestMapping("/api/schema")
class SchemaController {

    private static final String YAML = "application/yaml";

    private final SchemaService schemaService;

    SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping("/entity-types")
    List<DefinitionView> entityTypes() {
        return schemaService.list(DefinitionKind.ENTITY_TYPE);
    }

    @GetMapping("/entity-types/{name}")
    DefinitionView entityType(@PathVariable String name) {
        return schemaService.get(DefinitionKind.ENTITY_TYPE, name);
    }

    @GetMapping("/entity-types/{name}/versions")
    List<VersionView> entityTypeVersions(@PathVariable String name) {
        return schemaService.history(DefinitionKind.ENTITY_TYPE, name);
    }

    @PostMapping(value = "/entity-types", consumes = {YAML, "application/json", "text/yaml"})
    ResponseEntity<RegistrationResult> registerEntityType(@RequestBody String body,
                                                          @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                                          @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return created(schemaService.register(body, format(contentType), Actors.of(actor), DefinitionKind.ENTITY_TYPE));
    }

    @GetMapping("/relationship-types")
    List<DefinitionView> relationshipTypes() {
        return schemaService.list(DefinitionKind.RELATIONSHIP_TYPE);
    }

    @GetMapping("/relationship-types/{name}")
    DefinitionView relationshipType(@PathVariable String name) {
        return schemaService.get(DefinitionKind.RELATIONSHIP_TYPE, name);
    }

    @GetMapping("/relationship-types/{name}/versions")
    List<VersionView> relationshipTypeVersions(@PathVariable String name) {
        return schemaService.history(DefinitionKind.RELATIONSHIP_TYPE, name);
    }

    @PostMapping(value = "/relationship-types", consumes = {YAML, "application/json", "text/yaml"})
    ResponseEntity<RegistrationResult> registerRelationshipType(@RequestBody String body,
                                                                @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                                                @RequestHeader(value = Actors.HEADER, required = false) String actor) {
        return created(schemaService.register(body, format(contentType), Actors.of(actor), DefinitionKind.RELATIONSHIP_TYPE));
    }

    /** Dry run: validation, registry checks and compatibility with the current version. Writes nothing. */
    @PostMapping(value = "/validate", consumes = {YAML, "application/json", "text/yaml"})
    ValidationReport validate(@RequestBody String body, @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
        return schemaService.validate(body, format(contentType));
    }

    private static ResponseEntity<RegistrationResult> created(RegistrationResult result) {
        HttpStatus status = result.outcome() == SchemaService.Outcome.REGISTERED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    private static Format format(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json") ? Format.JSON : Format.YAML;
    }
}
