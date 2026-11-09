package com.omnicore.pke.engine.kernel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON for hashing and for storing property snapshots: object keys are sorted and numbers are
 * written in one form. Supports null, String, Boolean, integral and floating numbers, List and Map.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    /** Lower-case hex SHA-256 of the canonical JSON. */
    public static String sha256(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> appendString(out, s);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i.longValue());
            case Long l -> out.append(l);
            case Short s -> out.append(s.longValue());
            case BigInteger b -> out.append(b);
            case BigDecimal d -> out.append(d.stripTrailingZeros().toPlainString());
            case Number n -> {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("Non-finite numbers are not supported: " + d);
                }
                out.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
            }
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendString(out, e.getKey());
                    out.append(':');
                    append(out, e.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    append(out, list.get(i));
                }
                out.append(']');
            }
            default -> appendString(out, value.toString());
        }
    }

    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
