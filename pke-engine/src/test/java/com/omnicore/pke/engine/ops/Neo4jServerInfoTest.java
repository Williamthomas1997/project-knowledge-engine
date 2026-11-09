package com.omnicore.pke.engine.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Neo4jServerInfoTest {

    @ParameterizedTest
    @CsvSource({
            "5.26.0, true",
            "5.26.12, true",
            "5.27.1, true",
            "2025.01.0, true",
            "2026.10.1, true",
            "5.25.1, false",
            "5.13.0, false",
            "4.4.30, false",
            "garbage, false"
    })
    void supportsOnlyNeo4j526OrNewer(String version, boolean supported) {
        assertThat(new Neo4jServerInfo(version, "community").isSupported()).isEqualTo(supported);
    }
}
