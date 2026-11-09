package com.omnicore.pke.spi.journey;

import com.omnicore.pke.spi.PkePlugin;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads user journeys ("orchestration of APIs") from one kind of source: Postman collections, Java journey tests,
 * Gatling simulations, JMeter plans, or (later) a prompt interview. Adapters are deterministic and never guess:
 * anything they cannot resolve is reported in {@link JourneyDefinition#unresolved()}.
 * <p>
 * Adapters must not return values from the source (tokens, sample bodies, customer data): only names, structure,
 * order and gateway header codes.
 */
public interface JourneySourceAdapter extends PkePlugin {

    /** True if this adapter reads {@code file} (a path relative to {@link JourneyContext#root()}). */
    boolean supports(JourneyContext context, Path file);

    /** Journeys found in {@code file}. Empty if the file holds none. */
    List<JourneyDefinition> read(JourneyContext context, Path file);
}
