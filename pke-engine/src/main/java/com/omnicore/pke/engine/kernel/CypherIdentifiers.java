package com.omnicore.pke.engine.kernel;

import java.util.regex.Pattern;

/**
 * Validates identifiers that end up as Neo4j labels, relationship types or property keys.
 * <p>
 * Even though they are passed to Cypher as parameters ({@code SET n:$($label)}), we only accept names
 * that are also valid, readable schema identifiers. This keeps the graph tidy and stops arbitrary
 * user input from becoming database schema.
 */
public final class CypherIdentifiers {

    private static final Pattern LABEL = Pattern.compile("^[A-Z][A-Za-z0-9]{1,63}$");
    private static final Pattern RELATIONSHIP_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^[a-z][A-Za-z0-9_]{0,63}$");

    private CypherIdentifiers() {
    }

    /** Returns the label unchanged if it is a valid PascalCase entity label. */
    public static String label(String candidate) {
        return check(candidate, LABEL, "label");
    }

    /** Returns the type unchanged if it is a valid UPPER_SNAKE_CASE relationship type. */
    public static String relationshipType(String candidate) {
        return check(candidate, RELATIONSHIP_TYPE, "relationship type");
    }

    /** Returns the key unchanged if it is a valid camelCase property key. */
    public static String propertyKey(String candidate) {
        return check(candidate, PROPERTY_KEY, "property key");
    }

    public static boolean isLabel(String candidate) {
        return candidate != null && LABEL.matcher(candidate).matches();
    }

    public static boolean isRelationshipType(String candidate) {
        return candidate != null && RELATIONSHIP_TYPE.matcher(candidate).matches();
    }

    private static String check(String candidate, Pattern pattern, String kind) {
        if (candidate == null || !pattern.matcher(candidate).matches()) {
            throw new InvalidIdentifierException(kind, candidate, pattern.pattern());
        }
        return candidate;
    }
}
