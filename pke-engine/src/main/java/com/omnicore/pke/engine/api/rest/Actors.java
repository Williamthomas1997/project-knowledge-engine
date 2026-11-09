package com.omnicore.pke.engine.api.rest;

/**
 * Who made a change, recorded on change events. Until authentication arrives (M6) callers identify themselves
 * with the {@value #HEADER} header; it is informational, not a security control.
 */
final class Actors {

    static final String HEADER = "X-PKE-Actor";

    private Actors() {
    }

    static String of(String header) {
        return header == null || header.isBlank() ? "api" : "api:" + header.strip();
    }
}
