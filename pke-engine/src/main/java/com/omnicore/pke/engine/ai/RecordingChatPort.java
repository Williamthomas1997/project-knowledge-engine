package com.omnicore.pke.engine.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * {@code record} mode ({@code mvn -Precord-ai verify} with {@code OPENAI_API_KEY}): replays existing fixtures and
 * calls the real model for the rest, writing each answer as a new fixture. Use it with synthetic data only.
 */
final class RecordingChatPort implements ChatPort {

    private final ReplayChatPort replay;
    private final ChatPort live;
    private final Path dir;
    private final boolean overwrite;

    RecordingChatPort(ReplayChatPort replay, ChatPort live, Path dir, boolean overwrite) {
        this.replay = replay;
        this.live = live;
        this.dir = dir;
        this.overwrite = overwrite;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return "record(" + live.describe() + ")";
    }

    @Override
    public ChatResult call(ChatRequest request) {
        if (!overwrite) {
            Optional<JsonNode> existing = replay.find(request);
            if (existing.isPresent()) {
                return replay.call(request);
            }
        }
        ChatResult result = live.call(request);
        write(request, result);
        return result;
    }

    private void write(ChatRequest request, ChatResult result) {
        Path file = dir.resolve(request.skillId()).resolve(request.inputHash() + ".json");
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("skill", request.skillId());
        fixture.put("skillVersion", request.skillVersion());
        fixture.put("inputHash", request.inputHash());
        fixture.put("provider", result.provider());
        fixture.put("model", result.model());
        fixture.put("recordedWith", "record-ai " + OffsetDateTime.now(ZoneOffset.UTC));
        fixture.put("tokensIn", result.tokensIn());
        fixture.put("tokensOut", result.tokensOut());
        fixture.put("input", request.input());
        Object response;
        try {
            response = StructuredOutput.tree(result.content());
        } catch (StructuredOutput.StructuredOutputException e) {
            response = result.content();
        }
        fixture.put("response", response);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ReplayChatPort.JSON.writeValueAsString(fixture), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write fixture " + file, e);
        }
    }
}
