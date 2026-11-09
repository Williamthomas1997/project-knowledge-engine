package com.omnicore.pke.engine.kernel;

import java.util.List;

/** Input was rejected before anything was written. Carries every violation found, not just the first. */
public class ValidationFailedException extends RuntimeException {

    private final List<Violation> violations;

    public ValidationFailedException(String summary, List<Violation> violations) {
        super(summary + ": " + violations);
        this.violations = List.copyOf(violations);
    }

    public static ValidationFailedException of(String path, String message) {
        return new ValidationFailedException("Invalid input", List.of(new Violation(path, message)));
    }

    public List<Violation> violations() {
        return violations;
    }
}
