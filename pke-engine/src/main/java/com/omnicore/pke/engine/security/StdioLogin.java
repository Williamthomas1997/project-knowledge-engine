package com.omnicore.pke.engine.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The STDIO MCP launcher has no HTTP headers: the process authenticates once, at startup, with the key in the
 * {@code PKE_API_KEY} environment variable, and every tool call runs as that key. Startup fails without a valid key.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "true")
class StdioLogin implements InitializingBean {

    private final ApiKeyService keys;
    private final Access access;
    private final String key;

    StdioLogin(ApiKeyService keys, Access access, @Value("${PKE_API_KEY:}") String key) {
        this.keys = keys;
        this.access = access;
        this.key = key;
    }

    @Override
    public void afterPropertiesSet() {
        Caller caller = keys.authenticate(key).orElseThrow(() -> new IllegalStateException(
                "The STDIO MCP server needs a valid, active API key in the PKE_API_KEY environment variable"));
        access.authenticateProcess(caller);
    }
}
