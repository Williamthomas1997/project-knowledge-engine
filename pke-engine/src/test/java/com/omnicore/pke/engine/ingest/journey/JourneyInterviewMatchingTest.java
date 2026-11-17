package com.omnicore.pke.engine.ingest.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The deterministic half of the journey interview: matching a step to APIs and reading the person's choice. */
class JourneyInterviewMatchingTest {

    private static final List<Map<String, Object>> APIS = List.of(
            api("g1", "POST", "/api/v2/international-transfers/validation"),
            api("s1", "POST", "/api/v2/international-transfers/validation"),
            api("s2", "POST", "/api/v3/international-transfers/execute"),
            api("s3", "GET", "/api/v3/international-transfers/execute"));

    @Test
    void exactPathsWinThenSuffixesThenContainmentAlwaysWithTheSameMethod() {
        assertThat(ids(step("POST", "/api/v2/international-transfers/validation"))).containsExactly("g1", "s1");
        assertThat(ids(step("POST", "/international-transfers/execute"))).containsExactly("s2");
        assertThat(ids(step("GET", "/international-transfers"))).containsExactly("s3");
        assertThat(ids(step("POST", "/international-transfers"))).containsExactly("g1", "s1", "s2");
        assertThat(ids(step("DELETE", "/api/v3/international-transfers/execute"))).isEmpty();
    }

    @Test
    void answersAreANumberAnIdOrNone() {
        List<String> candidates = List.of("g1", "s1");
        assertThat(JourneyInterviewService.choose("2", candidates)).isEqualTo("s1");
        assertThat(JourneyInterviewService.choose("option 1", candidates)).isEqualTo("g1");
        assertThat(JourneyInterviewService.choose("s1", candidates)).isEqualTo("s1");
        assertThat(JourneyInterviewService.choose("None", candidates)).isEqualTo("none");
        assertThat(JourneyInterviewService.choose("3", candidates)).isNull();
        assertThat(JourneyInterviewService.choose("the first one", candidates)).isNull();
    }

    private static List<Object> ids(Map<String, Object> step) {
        return JourneyInterviewService.candidates(step, APIS).stream().map(a -> a.get("id")).toList();
    }

    private static Map<String, Object> step(String method, String path) {
        return Map.of("order", 1, "name", "step", "method", method, "path", path);
    }

    private static Map<String, Object> api(String id, String method, String path) {
        return Map.of("id", id, "method", method, "path", path, "layer", "service", "service", "s");
    }
}
