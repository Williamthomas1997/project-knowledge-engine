package com.omnicore.pke.engine.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers from recorded fixtures: {@code <fixtures>/<skill>/<inputHash>.json}. No network, no key.
 * <p>
 * Fixture format: {@code {skill, skillVersion, inputHash, provider, model, recordedWith, tokensIn, tokensOut,
 * input, response}}; {@code response} is the JSON object the model returned. A request without a fixture fails
 * (not transient), and the request is written to {@code missingDir} so a fixture can be recorded or reviewed.
 */
class ReplayChatPort implements ChatPort {

    private static final Logger log = LoggerFactory.getLogger(ReplayChatPort.class);
    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final ResourceLoader resources;
    private final String fixtures;
    private final Path missingDir;

    ReplayChatPort(ResourceLoader resources, String fixtures, Path missingDir) {
        this.resources = resources;
        this.fixtures = fixtures.endsWith("/") ? fixtures : fixtures + "/";
        this.missingDir = missingDir;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return "replay";
    }

    @Override
    public ChatResult call(ChatRequest request) {
        JsonNode fixture = find(request).orElseThrow(() -> {
            Path dump = dumpMissing(request);
            return new AiCallException("No replay fixture for skill " + request.skillId() + " input " + request.inputHash()
                    + "; the request was written to " + dump, false, null);
        });
        String recordedVersion = fixture.path("skillVersion").asString(null);
        if (recordedVersion != null && !recordedVersion.equals(request.skillVersion())) {
            throw new AiCallException("Replay fixture " + request.skillId() + "/" + request.inputHash()
                    + " was recorded for skill version " + recordedVersion + ", not " + request.skillVersion()
                    + ": record it again", false, null);
        }
        JsonNode response = fixture.get("response");
        String content = response == null ? "" : response.isString() ? response.asString() : JSON.writeValueAsString(response);
        return new ChatResult(content, fixture.path("provider").asString("fixture"),
                fixture.path("model").asString("unknown"), fixture.path("tokensIn").asInt(0),
                fixture.path("tokensOut").asInt(0), 0, true);
    }

    Optional<JsonNode> find(ChatRequest request) {
        Resource resource = resources.getResource(fixtures + request.skillId() + "/" + request.inputHash() + ".json");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read replay fixture " + resource, e);
        }
    }

    private Path dumpMissing(ChatRequest request) {
        Path file = missingDir.resolve(request.skillId()).resolve(request.inputHash() + ".request.json");
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> dump = new LinkedHashMap<>();
            dump.put("skill", request.skillId());
            dump.put("skillVersion", request.skillVersion());
            dump.put("inputHash", request.inputHash());
            dump.put("input", request.input());
            dump.put("system", request.system());
            dump.put("user", request.user());
            Files.writeString(file, JSON.writeValueAsString(dump), StandardCharsets.UTF_8);
            log.info("No replay fixture for skill {} input {}", request.skillId(), request.inputHash());
        } catch (IOException e) {
            log.warn("Could not write the missing-fixture request: {}", e.getMessage());
        }
        return file;
    }
}
