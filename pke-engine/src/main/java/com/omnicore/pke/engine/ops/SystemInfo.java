package com.omnicore.pke.engine.ops;

import java.util.List;

public record SystemInfo(
        String appVersion,
        String neo4jVersion,
        String neo4jEdition,
        List<String> activeProfiles,
        boolean chatModelConfigured,
        String chatModel,
        boolean embeddingModelConfigured,
        String embeddingModel,
        String vectorStore) {
}
