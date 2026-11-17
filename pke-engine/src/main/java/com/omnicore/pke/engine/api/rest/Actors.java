package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.security.Caller;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who made a change, recorded on change events: the API key ({@code key:<name>}), plus the optional
 * {@value #HEADER} header naming the person or tool the key acts for ({@code key:kgp/william}). The header is
 * informational; the key is the security control (ADR-017).
 */
final class Actors {

    static final String HEADER = "X-PKE-Actor";

    private Actors() {
    }

    static String of(String header) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String onBehalfOf = header == null || header.isBlank() ? null : header.strip();
        if (auth != null && auth.getPrincipal() instanceof Caller caller) {
            return onBehalfOf == null ? caller.actor() : caller.actor() + "/" + onBehalfOf;
        }
        return onBehalfOf == null ? "api" : "api:" + onBehalfOf;
    }
}
