package com.omnicore.pke.engine.ingest.journey;

import com.omnicore.pke.engine.extract.ApiOperations;
import com.omnicore.pke.engine.extract.ExtractedKnowledge;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import com.omnicore.pke.spi.journey.JourneyDefinition.Feed;
import com.omnicore.pke.spi.journey.JourneyDefinition.Step;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns journeys into knowledge: {@code UserJourney -[:HAS_STEP {order}]-> JourneyStep -[:CALLS_API]-> ApiOperation}
 * and {@code JourneyStep -[:FEEDS {field}]-> JourneyStep}. Journeys from different sources with the same key are the
 * same journey; each source's step names are kept as evidence excerpts. Gateway API properties are merged over
 * every source of the run first, so sources never overwrite each other.
 */
public final class JourneyKnowledgeMapper {

    public static final String METHOD_PREFIX = "journey.";

    private final List<String> identityHeaders;
    private final Map<String, Map<String, Object>> apis = new LinkedHashMap<>();

    public JourneyKnowledgeMapper(List<JourneyDefinition> journeys, List<String> identityHeaders) {
        this(journeys, identityHeaders, List.of());
    }

    /**
     * @param stored gateway APIs already in the graph (possibly observed by another repository); observations are
     *               merged into them, so repositories describing the same API never overwrite each other
     */
    public JourneyKnowledgeMapper(List<JourneyDefinition> journeys, List<String> identityHeaders,
                                  List<Map<String, Object>> stored) {
        this.identityHeaders = identityHeaders;
        Set<String> used = new HashSet<>();
        for (JourneyDefinition journey : journeys) {
            for (Step step : journey.steps()) {
                if (step.api() != null) {
                    used.add(apiKey(ApiOperations.gatewayProperties(step.api(), identityHeaders)));
                }
            }
        }
        for (Map<String, Object> props : stored) {
            if (used.contains(apiKey(props))) {
                apis.put(apiKey(props), new LinkedHashMap<>(props));
            }
        }
        for (JourneyDefinition journey : journeys) {
            for (Step step : journey.steps()) {
                if (step.api() != null) {
                    Map<String, Object> props = ApiOperations.gatewayProperties(step.api(), identityHeaders);
                    apis.merge(apiKey(props), props, ApiOperations::merge);
                }
            }
        }
    }

    public ExtractedKnowledge map(JourneyDefinition journey, String artifactKey, String commit) {
        ExtractedKnowledge k = new ExtractedKnowledge(journey.sourcePath());
        String method = METHOD_PREFIX + journey.sourceKind();
        EvidenceInput journeyEvidence = EvidenceInput.scoped(artifactKey, commit, journey.sourcePath(), journey.sourcePath(),
                method, journey.name());
        EntityRef journeyRef = k.entity("UserJourney", Map.of("journeyKey", journey.key(), "name", humanise(journey.key())),
                List.of("journeyKey"), journeyEvidence);
        Map<Integer, EntityRef> steps = new HashMap<>();
        for (Step step : journey.steps()) {
            EvidenceInput evidence = EvidenceInput.scoped(artifactKey, commit, journey.sourcePath(), step.location(), method,
                    step.label());
            EntityRef stepRef = k.entity("JourneyStep", Map.of("journeyKey", journey.key(), "order", (long) step.order()),
                    List.of("journeyKey", "order"), evidence);
            steps.put(step.order(), stepRef);
            k.relate("HAS_STEP", journeyRef, stepRef, Map.of("order", (long) step.order()), evidence, null);
            if (step.api() == null) {
                k.unresolved(journey.sourcePath() + " step " + step.order() + " (" + step.label() + "): API not resolved");
                continue;
            }
            Map<String, Object> props = apis.get(apiKey(ApiOperations.gatewayProperties(step.api(), identityHeaders)));
            EntityRef api = k.entity("ApiOperation", props, ApiOperations.NATURAL_KEY, evidence);
            k.relate("CALLS_API", stepRef, api, Map.of(), evidence, null);
        }
        for (Feed feed : journey.feeds()) {
            EntityRef from = steps.get(feed.fromOrder());
            EntityRef to = steps.get(feed.toOrder());
            if (from != null && to != null) {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("field", feed.field());
                props.put("variable", feed.variable());
                k.relate("FEEDS", from, to, props, EvidenceInput.scoped(artifactKey, commit, journey.sourcePath(),
                        journey.sourcePath() + " step " + feed.toOrder(), method, feed.variable()), null);
            }
        }
        journey.unresolved().forEach(u -> k.unresolved(journey.sourcePath() + " " + u.location() + ": " + u.reason()));
        return k;
    }

    private static String apiKey(Map<String, Object> props) {
        return props.get("httpMethod") + " " + props.get("pathTemplate") + " " + props.get("gatewayKey");
    }

    static String humanise(String key) {
        return Arrays.stream(key.split("-")).filter(s -> !s.isEmpty())
                .map(s -> s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1)).collect(Collectors.joining(" "));
    }

    /** Ordered list of the journeys' API keys, for reports. */
    public List<String> apiKeys() {
        return new ArrayList<>(apis.keySet());
    }
}
