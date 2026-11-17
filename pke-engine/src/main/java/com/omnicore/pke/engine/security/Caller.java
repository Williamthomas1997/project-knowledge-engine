package com.omnicore.pke.engine.security;

import java.util.Set;

/** An authenticated API key: its name and its effective roles (including implied ones). */
public record Caller(String name, Set<Role> roles) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** The actor recorded on change events and audit events. */
    public String actor() {
        return "key:" + name;
    }
}
