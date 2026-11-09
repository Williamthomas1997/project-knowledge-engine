package com.omnicore.pke.engine.ai;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI settings ({@code pke.ai.*}).
 *
 * @param mode     {@code auto} (default: the Spring AI chat model when one is configured, otherwise none),
 *                 {@code none}, {@code replay} (recorded fixtures only; used by tests) or {@code record} (replay what
 *                 exists, call the real model for the rest and save the answers as fixtures)
 * @param provider provider name written to the cost ledger and to evidence for the Spring AI model
 * @param replay   where fixtures are read from, and where requests without a fixture are dumped
 * @param record   where new fixtures are written in {@code record} mode
 * @param pricing  model id → price per million tokens (USD); calls of models without a price get no cost estimate
 */
@ConfigurationProperties("pke.ai")
public record AiProperties(Mode mode, String provider, Replay replay, Record record, Map<String, Price> pricing) {

    public enum Mode { AUTO, NONE, REPLAY, RECORD }

    public record Replay(String fixtures, String missingDir) {
        public Replay {
            fixtures = fixtures == null || fixtures.isBlank() ? "classpath:ai-fixtures" : fixtures;
            missingDir = missingDir == null || missingDir.isBlank() ? "target/ai-fixtures-missing" : missingDir;
        }
    }

    public record Record(String dir, boolean overwrite) {
    }

    public record Price(double inputPerMillion, double outputPerMillion) {
    }

    public AiProperties {
        mode = mode == null ? Mode.AUTO : mode;
        provider = provider == null || provider.isBlank() ? "openai" : provider;
        replay = replay == null ? new Replay(null, null) : replay;
        record = record == null ? new Record(null, false) : record;
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }
}
