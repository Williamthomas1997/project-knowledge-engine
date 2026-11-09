package com.omnicore.pke.engine.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.KnowledgeIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end over HTTP: schema registration, knowledge writes, reads, history and integrity. */
class RestApiIT extends KnowledgeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-PKE-Actor", "rest-it");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    @Test
    void registerTypesWriteKnowledgeAndReadItBack() throws Exception {
        var control = send("POST", "/api/schema/entity-types", "application/yaml", """
                kind: EntityType
                name: RegulatoryControl
                version: 1.0.0
                description: A control required by a regulator
                properties:
                  controlId: { type: string, required: true }
                  title: { type: string, required: true }
                identity: { strategy: naturalKey, naturalKey: [controlId] }
                """);
        assertThat(control.statusCode()).isEqualTo(201);
        var relationshipType = """
                {"kind": "RelationshipType", "name": "REGULATORY_DEPENDENCY", "version": "1.0.0",
                 "description": "Indicates a dependency on a regulatory control", "category": "business",
                 "sourceTypes": ["BusinessFeature"], "targetTypes": ["RegulatoryControl"],
                 "properties": {"rationale": {"type": "text"}}}""";
        assertThat(send("POST", "/api/schema/relationship-types", "application/json", relationshipType).statusCode())
                .isEqualTo(201);
        var again = send("POST", "/api/schema/relationship-types", "application/json", relationshipType);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).get("outcome").asString()).isEqualTo("UNCHANGED");

        var changes = send("POST", "/api/knowledge/changes", "application/json", """
                {"entities": [
                   {"type": "BusinessFeature", "properties": {"featureKey": "intl-transfer", "name": "International transfer"},
                    "epistemic": "OBSERVED"},
                   {"type": "RegulatoryControl", "properties": {"controlId": "QCB-AML-7", "title": "Screen beneficiaries"},
                    "epistemic": "OBSERVED"}],
                 "relationships": [
                   {"type": "REGULATORY_DEPENDENCY",
                    "subject": {"type": "BusinessFeature", "key": {"featureKey": "intl-transfer"}},
                    "object": {"type": "RegulatoryControl", "key": {"controlId": "QCB-AML-7"}},
                    "properties": {"rationale": "Transfers abroad must be screened"}, "epistemic": "OBSERVED",
                    "evidence": [{"sourceArtifactId": "doc:policy", "sourceVersion": "2026-1", "location": "§4",
                                  "extractionMethod": "manual"}]}]}""");
        assertThat(changes.statusCode()).isEqualTo(201);
        JsonNode items = json(changes).get("items");
        assertThat(items).hasSize(3);
        String featureId = items.get(0).get("id").asString();
        String relationshipId = items.get(2).get("id").asString();

        var feature = json(send("GET", "/api/entities/" + featureId, null, null));
        assertThat(feature.get("type").asString()).isEqualTo("BusinessFeature");
        assertThat(feature.get("properties").get("name").asString()).isEqualTo("International transfer");

        var relationships = json(send("GET", "/api/entities/" + featureId + "/relationships", null, null));
        assertThat(relationships).hasSize(1);
        assertThat(relationships.get(0).get("type").asString()).isEqualTo("REGULATORY_DEPENDENCY");

        var invalidated = send("POST", "/api/relationships/" + relationshipId + "/invalidate", "application/json",
                "{\"reason\": \"control retired\"}");
        assertThat(invalidated.statusCode()).isEqualTo(200);
        assertThat(json(send("GET", "/api/entities/" + featureId + "/relationships", null, null))).isEmpty();
        assertThat(json(send("GET", "/api/entities/" + featureId + "/relationships?includeInvalid=true", null, null)))
                .hasSize(1);
        assertThat(json(send("GET", "/api/relationships/" + relationshipId + "/versions", null, null))).hasSize(2);

        var events = json(send("GET", "/api/history/events?subjectId=" + relationshipId, null, null));
        assertThat(events).extracting(e -> e.get("type").asString())
                .containsExactly("RELATIONSHIP_CREATED", "RELATIONSHIP_INVALIDATED");
        assertThat(events.get(0).get("actor").asString()).isEqualTo("api:rest-it");

        assertThat(json(send("GET", "/api/integrity", null, null)).get("violations")).isEmpty();
        assertThat(json(send("GET", "/api/entities?type=BusinessFeature", null, null)).get("total").asLong()).isEqualTo(1);
    }

    @Test
    void invalidInputIsReportedWithEveryViolation() throws Exception {
        var response = send("POST", "/api/entities", "application/json", """
                {"type": "JavaMethod", "properties": {"repository": "demo", "colour": "red", "lineStart": "ten"},
                 "epistemic": "EXTRACTED_DETERMINISTIC"}""");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("violations")).extracting(v -> v.get("path").asString()).contains(
                "/entities/0/properties/colour", "/entities/0/properties/lineStart",
                "/entities/0/properties/fqcn", "/entities/0/properties/signature");

        var badDefinition = send("POST", "/api/schema/validate", "application/yaml", "kind: EntityType\nname: x\n");
        assertThat(badDefinition.statusCode()).isEqualTo(200);
        assertThat(json(badDefinition).get("valid").asBoolean()).isFalse();
    }

    @Test
    void missingThingsAre404AndConflictsAre409() throws Exception {
        assertThat(send("GET", "/api/entities/nope", null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/schema/entity-types/Nope", null, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/relationships/nope/invalidate", "application/json", "{}").statusCode()).isEqualTo(404);

        var narrowed = send("POST", "/api/schema/relationship-types", "application/yaml", """
                kind: RelationshipType
                name: CALLS
                version: 1.0.0
                description: changed text, same version
                category: technical
                sourceTypes: [JavaMethod]
                targetTypes: [JavaMethod]
                """);
        assertThat(narrowed.statusCode()).isEqualTo(409);
    }
}
