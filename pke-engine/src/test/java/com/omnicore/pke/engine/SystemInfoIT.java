package com.omnicore.pke.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.KnowledgeIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Boots the full application without any AI provider key and calls the system info endpoint over HTTP. */
class SystemInfoIT extends KnowledgeIT {

    @Test
    void systemInfoReportsNeo4jAndNoModelsWithoutOpenAiKey() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/system/info")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.get("neo4jVersion").asString()).isNotBlank();
        assertThat(body.get("neo4jEdition").asString()).isNotBlank();
        assertThat(body.get("appVersion").asString()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(body.get("chatModelConfigured").asBoolean()).isFalse();
        assertThat(body.get("embeddingModelConfigured").asBoolean()).isFalse();
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
