package com.omnicore.pke.engine.ai;

/** A model call failed. {@code transientFailure} = a retry may succeed (rate limit, timeout, server error). */
public class AiCallException extends RuntimeException {

    private final boolean transientFailure;

    public AiCallException(String message, boolean transientFailure, Throwable cause) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    public boolean isTransient() {
        return transientFailure;
    }

    /** Classifies a provider exception by name (the providers' SDKs have no common exception hierarchy). */
    static boolean looksTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.contains("RateLimit") || name.contains("Timeout") || name.contains("InternalServer")
                    || name.contains("IoException") || name.contains("IOException") || name.contains("Unavailable")
                    || t instanceof java.net.SocketException) {
                return true;
            }
        }
        return false;
    }
}
