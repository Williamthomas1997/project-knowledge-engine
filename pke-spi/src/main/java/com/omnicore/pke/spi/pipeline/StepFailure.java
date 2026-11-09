package com.omnicore.pke.spi.pipeline;

/**
 * A classified step failure.
 * <ul>
 *   <li>{@code TRANSIENT}: may succeed when retried (timeouts, rate limits, unavailable services).</li>
 *   <li>{@code PERMANENT}: retrying the same input fails again (invalid model output, missing source).</li>
 *   <li>{@code POLICY}: refused by a rule (undeclared tool, forbidden write); never retried.</li>
 *   <li>{@code CANCELLED}: the execution was cancelled.</li>
 * </ul>
 */
public class StepFailure extends RuntimeException {

    public enum ErrorClass { TRANSIENT, PERMANENT, POLICY, CANCELLED }

    private final ErrorClass errorClass;

    public StepFailure(ErrorClass errorClass, String message) {
        this(errorClass, message, null);
    }

    public StepFailure(ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
