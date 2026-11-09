package com.omnicore.pke.engine.kernel;

/** One validation problem: where it is (a JSON-pointer-like path) and what is wrong. */
public record Violation(String path, String message) {

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
