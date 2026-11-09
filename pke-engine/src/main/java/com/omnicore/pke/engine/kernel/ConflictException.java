package com.omnicore.pke.engine.kernel;

/** The request is valid on its own but clashes with existing state (immutable versions, breaking schema changes). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
