package com.omnicore.pke.engine.ops;

/** Neo4j kernel version and edition as reported by {@code dbms.components()}. */
public record Neo4jServerInfo(String version, String edition) {

    /** Oldest supported version: dynamic labels and relationship types ({@code $(...)}) arrived in 5.26. */
    public static final int MIN_MAJOR = 5;
    public static final int MIN_MINOR = 26;

    /**
     * True if {@link #version()} is at least 5.26. Works for semantic versions ({@code 5.26.3}) and
     * calendar versions ({@code 2026.09.0}), whose year-based major is always newer than 5.
     */
    public boolean isSupported() {
        String[] parts = version.split("[.\\-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
