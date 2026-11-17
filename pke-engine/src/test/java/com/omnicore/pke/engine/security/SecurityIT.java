package com.omnicore.pke.engine.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.support.KnowledgeIT;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** M6: API keys, roles on REST and actuator, the audit log, the key CLI, and no keys in logs or reports. */
@ExtendWith(OutputCaptureExtension.class)
class SecurityIT extends KnowledgeIT {

    /** OpenAI-style secret keys; PKE keys are {@link ApiKeyService#KEY_PATTERN}. */
    private static final Pattern OPENAI_KEY = Pattern.compile("sk-(proj-)?[A-Za-z0-9_-]{20,}");

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void callsWithoutAValidKeyAreRejected() throws Exception {
        assertThat(send(null, "GET", "/api/journeys", null).statusCode()).isEqualTo(401);
        assertThat(send(null, "POST", "/api/history/restore", "{\"seq\": 0}").statusCode()).isEqualTo(401);
        assertThat(send(null, "POST", "/mcp", "{}").statusCode()).isEqualTo(401);
        assertThat(send("pke_" + "a".repeat(43), "GET", "/api/journeys", null).statusCode()).isEqualTo(401);
        assertThat(send("not-a-key", "GET", "/api/journeys", null).statusCode()).isEqualTo(401);
        String key = apiKeys.create("temporary", List.of(Role.READER), "test").key();
        assertThat(send(key, "GET", "/api/journeys", null).statusCode()).isEqualTo(200);
        assertThat(sendWithHeader("X-API-Key", key, "/api/journeys").statusCode()).isEqualTo(200);
        apiKeys.revoke("temporary", "test");
        assertThat(send(key, "GET", "/api/journeys", null).statusCode()).isEqualTo(401);
        // keys are stored hashed: the key itself is nowhere in the graph
        assertThat(count("MATCH (k:ApiKey) WHERE any(p IN keys(k) WHERE toString(k[p]) = $key) RETURN count(k)",
                Map.of("key", key))).isZero();
    }

    @Test
    void rolesDecideWhatAKeyMayDo() throws Exception {
        String reader = apiKeys.create("reader", List.of(Role.READER), "test").key();
        String contributor = apiKeys.create("contributor", List.of(Role.CONTRIBUTOR), "test").key();
        String schemaAdmin = apiKeys.create("schema-admin", List.of(Role.SCHEMA_ADMIN), "test").key();

        assertThat(send(reader, "GET", "/api/entities", null).statusCode()).isEqualTo(200);
        assertThat(send(reader, "POST", "/api/retrieval/search", "{\"query\": \"transfer\"}").statusCode()).isEqualTo(200);
        assertThat(send(reader, "POST", "/api/snapshots", "{\"label\": \"x\"}").statusCode()).isEqualTo(403);
        assertThat(send(reader, "POST", "/api/questions/q/answer", "{\"option\": \"accept\"}").statusCode()).isEqualTo(403);

        assertThat(send(contributor, "POST", "/api/snapshots", "{\"label\": \"x\"}").statusCode()).isEqualTo(201);
        // M5 review: restore and migrations need SCHEMA_ADMIN
        assertThat(send(contributor, "POST", "/api/history/restore", "{\"seq\": 0, \"reason\": \"t\"}").statusCode())
                .isEqualTo(403);
        assertThat(send(contributor, "POST", "/api/schema/migrations", "{}").statusCode()).isEqualTo(403);
        assertThat(send(contributor, "POST", "/api/schema/entity-types", "{}").statusCode()).isEqualTo(403);

        assertThat(send(schemaAdmin, "POST", "/api/history/restore", "{\"seq\": 0, \"reason\": \"t\"}").statusCode())
                .isNotIn(401, 403);
        assertThat(send(schemaAdmin, "POST", "/api/schema/migrations", "{}").statusCode()).isNotIn(401, 403);
        assertThat(send(schemaAdmin, "POST", "/api/snapshots", "{\"label\": \"y\"}").statusCode()).isEqualTo(201);
    }

    @Test
    void actuatorShowsHealthDetailsOnlyToAuthenticatedCallers() throws Exception {
        HttpResponse<String> anonymous = send(null, "GET", "/actuator/health", null);
        assertThat(anonymous.statusCode()).isEqualTo(200);
        assertThat(anonymous.body()).contains("\"status\":\"UP\"").doesNotContain("components");
        HttpResponse<String> reader = send(apiKeys.create("ops", List.of(Role.READER), "test").key(), "GET",
                "/actuator/health", null);
        assertThat(reader.body()).contains("components");
        assertThat(send(null, "GET", "/actuator/info", null).statusCode()).isEqualTo(401);
    }

    @Test
    void mutatingCallsAreAuditedIncludingRefusedOnes() throws Exception {
        String reader = apiKeys.create("auditor", List.of(Role.READER), "test").key();
        send(reader, "POST", "/api/snapshots", "{\"label\": \"refused\"}");
        send(null, "POST", "/api/snapshots", "{\"label\": \"anonymous\"}");
        send(adminKey, "POST", "/api/snapshots", "{\"label\": \"accepted\"}");
        send(reader, "GET", "/api/journeys", null);

        assertThat(rows("MATCH (a:AuditEvent {channel: 'REST'}) RETURN a.actor AS actor, a.action AS action, "
                + "a.outcome AS outcome ORDER BY a.at, a.pke_id", Map.of())).containsExactly(
                Map.of("actor", "key:auditor", "action", "POST /api/snapshots", "outcome", "403"),
                Map.of("actor", "anonymous", "action", "POST /api/snapshots", "outcome", "401"),
                Map.of("actor", "key:test-admin", "action", "POST /api/snapshots", "outcome", "201"));
    }

    @Test
    void theKeyCliCreatesListsAndRevokesKeys() {
        ApiKeyCli cli = new ApiKeyCli(apiKeys, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(cli.execute(List.of("create", "kgp", "CONTRIBUTOR"), print(out), print(err))).isZero();
        String key = out.toString(StandardCharsets.UTF_8).strip();
        assertThat(key).matches(ApiKeyService.KEY_PATTERN);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("shown once").doesNotContain(key);
        assertThat(apiKeys.authenticate(key)).hasValueSatisfying(c -> {
            assertThat(c.name()).isEqualTo("kgp");
            assertThat(c.roles()).containsExactlyInAnyOrder(Role.READER, Role.CONTRIBUTOR);
        });

        out.reset();
        assertThat(cli.execute(List.of("list"), print(out), print(err))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("kgp\tCONTRIBUTOR\t" + key.substring(0, 8))
                .doesNotContain(key);
        assertThat(cli.execute(List.of("revoke", "kgp"), print(out), print(err))).isZero();
        assertThat(apiKeys.authenticate(key)).isEmpty();
        assertThat(cli.execute(List.of("create", "kgp", "ROOT"), print(out), print(err))).isEqualTo(1);
        assertThat(cli.execute(List.of("create"), print(out), print(err))).isEqualTo(2);
    }

    @Test
    void keysNeverAppearInLogsOrReports(CapturedOutput output) throws Exception {
        String key = apiKeys.create("leak-check", List.of(Role.CONTRIBUTOR), "test").key();
        send(key, "POST", "/api/snapshots", "{\"label\": \"leak check\"}");
        send(key, "GET", "/api/entities/does-not-exist", null);
        send(key, "POST", "/api/questions/missing/answer", "{\"option\": \"accept\"}");

        assertThat(output.getAll()).doesNotContain(key, adminKey);
        assertThat(ApiKeyService.KEY_PATTERN.matcher(output.getAll()).find()).as("a PKE key in the log").isFalse();
        assertThat(OPENAI_KEY.matcher(output.getAll()).find()).as("an OpenAI key in the log").isFalse();

        // the repository's docs, reports, skills and definitions, and the reports of earlier test classes in this run
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> scanned = new ArrayList<>();
        for (String dir : List.of("docs", "claude-skills", "definitions", "pke-engine/target/surefire-reports",
                "pke-engine/target/failsafe-reports")) {
            Path base = root.resolve(dir);
            if (Files.isDirectory(base)) {
                try (Stream<Path> files = Files.walk(base)) {
                    files.filter(Files::isRegularFile).filter(f -> f.toString().matches(".*\\.(md|txt|xml|yaml|yml|json)$"))
                            .forEach(scanned::add);
                }
            }
        }
        scanned.add(root.resolve("README.md"));
        assertThat(scanned).hasSizeGreaterThan(20);
        for (Path file : scanned) {
            String text = read(file);
            assertThat(ApiKeyService.KEY_PATTERN.matcher(text).find()).as("PKE key in %s", file).isFalse();
            assertThat(OPENAI_KEY.matcher(text).find()).as("OpenAI key in %s", file).isFalse();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new String(readBytes(file), StandardCharsets.ISO_8859_1);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static PrintStream print(ByteArrayOutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(String key, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendWithHeader(String header, String value, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header(header, value)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
