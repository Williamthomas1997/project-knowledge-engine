package com.omnicore.pke.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PkePluginTest {

    @Test
    void pluginExposesIdAndVersion() {
        PkePlugin plugin = new PkePlugin() {
            @Override
            public String id() {
                return "extract.sample";
            }

            @Override
            public String version() {
                return "1.0.0";
            }
        };

        assertEquals("extract.sample", plugin.id());
        assertEquals("1.0.0", plugin.version());
    }

    @Test
    void spiClasspathHasNoSpring() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.context.ApplicationContext"));
    }
}
