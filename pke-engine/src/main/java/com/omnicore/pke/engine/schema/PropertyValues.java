package com.omnicore.pke.engine.schema;

import com.omnicore.pke.engine.kernel.Violation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Converts incoming values to the stored representation of their declared type, collecting violations. */
public final class PropertyValues {

    private PropertyValues() {
    }

    /** Returns the normalised value, or {@code null} after adding a violation (or when the input is null). */
    public static Object coerce(PropertyDefinition property, Object value, String path, List<Violation> violations) {
        if (value == null) {
            return null;
        }
        Object normalised = switch (property.type()) {
            case STRING, TEXT -> value instanceof String s ? s : null;
            case BOOLEAN -> value instanceof Boolean b ? b : null;
            case INTEGER -> integer(value);
            case NUMBER -> value instanceof Number n ? n.doubleValue() : null;
            case DATETIME -> datetime(value);
            case STRING_LIST -> stringList(value);
        };
        if (normalised == null) {
            violations.add(new Violation(path, "expected " + property.type().json() + " but got "
                    + value.getClass().getSimpleName() + " '" + abbreviate(value) + "'"));
            return null;
        }
        if (normalised instanceof String s) {
            if (!property.enumValues().isEmpty() && !property.enumValues().contains(s)) {
                violations.add(new Violation(path, "'" + abbreviate(s) + "' is not one of " + property.enumValues()));
                return null;
            }
            if (property.pattern() != null && !Pattern.compile(property.pattern()).matcher(s).find()) {
                violations.add(new Violation(path, "'" + abbreviate(s) + "' does not match " + property.pattern()));
                return null;
            }
        }
        return normalised;
    }

    private static Long integer(Object value) {
        return switch (value) {
            case Integer i -> i.longValue();
            case Long l -> l;
            case Short s -> s.longValue();
            case BigInteger b -> b.bitLength() < 64 ? b.longValue() : null;
            case BigDecimal d -> {
                try {
                    yield d.longValueExact();
                } catch (ArithmeticException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static String datetime(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (value instanceof String s) {
            try {
                return OffsetDateTime.parse(s).toString();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s)) {
                return null;
            }
            strings.add(s);
        }
        return List.copyOf(strings);
    }

    private static String abbreviate(Object value) {
        String s = String.valueOf(value);
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
