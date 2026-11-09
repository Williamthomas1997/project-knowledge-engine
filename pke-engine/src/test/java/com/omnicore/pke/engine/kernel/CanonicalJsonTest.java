package com.omnicore.pke.engine.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void keyOrderDoesNotChangeTheOutputOrHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", List.of("x", true));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", List.of("x", true));
        b.put("b", 1L);
        assertThat(CanonicalJson.write(a)).isEqualTo("{\"a\":[\"x\",true],\"b\":1}").isEqualTo(CanonicalJson.write(b));
        assertThat(CanonicalJson.sha256(a)).isEqualTo(CanonicalJson.sha256(b)).hasSize(64);
    }

    @Test
    void numbersHaveOneForm() {
        assertThat(CanonicalJson.write(2.50)).isEqualTo("2.5");
        assertThat(CanonicalJson.write(3.0)).isEqualTo("3");
        assertThat(CanonicalJson.write(Integer.valueOf(7))).isEqualTo(CanonicalJson.write(7L));
    }

    @Test
    void stringsAreEscaped() {
        assertThat(CanonicalJson.write("a\"b\\c\n\u0001")).isEqualTo("\"a\\\"b\\\\c\\n\\u0001\"");
        assertThat(CanonicalJson.write(null)).isEqualTo("null");
    }
}
