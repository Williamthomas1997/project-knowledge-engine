package com.omnicore.pke.engine.security;

import com.omnicore.pke.engine.graph.AuditStore;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is calling, for REST and MCP alike: the API key authenticated by the HTTP filter, or the key the STDIO MCP
 * launcher authenticated at startup ({@link StdioLogin}). Also writes audit events for MCP tool calls.
 */
@Component
public class Access {

    private final AuditStore audit;
    private volatile Caller processCaller;

    public Access(AuditStore audit) {
        this.audit = audit;
    }

    public Optional<Caller> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Caller caller) {
            return Optional.of(caller);
        }
        return Optional.ofNullable(processCaller);
    }

    /** The current caller when it has {@code role}; otherwise {@link AccessDeniedException}. */
    public Caller require(Role role) {
        Caller caller = current().orElseThrow(() -> new AccessDeniedException("An API key is required"));
        if (!caller.has(role)) {
            throw new AccessDeniedException("API key '" + caller.name() + "' does not have the " + role + " role");
        }
        return caller;
    }

    /** The actor for change events: the key name, else {@code fallback}. */
    public String actor(String fallback) {
        return current().map(Caller::actor).orElse(fallback);
    }

    /** Records a mutating MCP tool call (also when it was refused). */
    public void audit(String tool, String outcome) {
        Optional<Caller> caller = current();
        audit.record(caller.map(Caller::actor).orElse("anonymous"),
                caller.map(c -> c.roles().stream().map(Role::name).sorted().toList()).orElse(List.of()),
                "MCP", "tool " + tool, outcome);
    }

    void authenticateProcess(Caller caller) {
        this.processCaller = caller;
    }
}
