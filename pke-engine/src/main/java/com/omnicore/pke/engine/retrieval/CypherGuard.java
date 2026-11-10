package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.kernel.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static guard for Cypher that the engine did not write itself (ADR-007 §7 step 4; query templates are checked too):
 * <ul>
 *   <li>no write or administration clause ({@code CREATE, MERGE, SET, DELETE, REMOVE, DROP, FOREACH, LOAD CSV, ...});</li>
 *   <li>procedures and namespaced functions only from an allowlist; no {@code IN TRANSACTIONS};</li>
 *   <li>every label, relationship type and property must be registered (schema registry + system vocabulary);</li>
 *   <li>a {@code LIMIT} is injected when missing and capped when too high.</li>
 * </ul>
 * Execution adds the rest: {@code EXPLAIN} must succeed, the transaction is read-only and has a timeout.
 */
public final class CypherGuard {

    private static final Pattern WRITE = Pattern.compile(
            "\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|DROP|FOREACH|LOAD|INSERT|ALTER|RENAME|GRANT|DENY|REVOKE|TERMINATE|"
                    + "START|STOP|ENABLE|DISABLE|USE|SHOW|FINISH)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_TRANSACTIONS = Pattern.compile("\\bIN\\s+(CONCURRENT\\s+)?TRANSACTIONS\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROCEDURE = Pattern.compile("\\bCALL\\s+([A-Za-z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FUNCTION = Pattern.compile("\\b([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+)\\s*\\(");
    private static final Pattern NODE_LABELS = Pattern.compile("\\(\\s*[A-Za-z_]?\\w*\\s*((?::\\s*[A-Za-z_][\\w|&!:]*\\s*)+)");
    private static final Pattern REL_TYPES = Pattern.compile("\\[\\s*[A-Za-z_]?\\w*\\s*:\\s*([A-Za-z_][\\w|:&!]*)");
    private static final Pattern PROPERTY = Pattern.compile("(?<![\\w$])[A-Za-z_]\\w*\\s*\\.\\s*([A-Za-z_]\\w*)");
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\s+(\\d+|\\$\\w+)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED_PROCEDURES = Set.of("db.index.fulltext.querynodes", "db.index.vector.querynodes");

    public record Result(String cypher, List<Violation> violations) {
        public boolean allowed() {
            return violations.isEmpty();
        }
    }

    private final Set<String> labels;
    private final Set<String> relationshipTypes;
    private final Set<String> properties;
    private final int maxLimit;

    public CypherGuard(Set<String> labels, Set<String> relationshipTypes, Set<String> properties, int maxLimit) {
        this.labels = Set.copyOf(labels);
        this.relationshipTypes = Set.copyOf(relationshipTypes);
        this.properties = Set.copyOf(properties);
        this.maxLimit = maxLimit;
    }

    public Result check(String cypher) {
        List<Violation> violations = new ArrayList<>();
        if (cypher == null || cypher.isBlank()) {
            return new Result(cypher, List.of(new Violation("$", "the query is empty")));
        }
        if (cypher.indexOf('`') >= 0) {
            violations.add(new Violation("$", "quoted identifiers (backticks) are not allowed"));
        }
        String code = strip(cypher);
        if (code.contains(";") && !code.strip().endsWith(";") || code.chars().filter(c -> c == ';').count() > 1) {
            violations.add(new Violation("$", "only one statement is allowed"));
        }
        Matcher write = WRITE.matcher(code);
        while (write.find()) {
            violations.add(new Violation("$", "write or administration clause '" + write.group(1).toUpperCase(Locale.ROOT)
                    + "' is not allowed"));
        }
        if (IN_TRANSACTIONS.matcher(code).find()) {
            violations.add(new Violation("$", "CALL ... IN TRANSACTIONS is not allowed"));
        }
        Matcher procedure = PROCEDURE.matcher(code);
        while (procedure.find()) {
            if (!ALLOWED_PROCEDURES.contains(procedure.group(1).toLowerCase(Locale.ROOT))) {
                violations.add(new Violation("$", "procedure '" + procedure.group(1) + "' is not allowed"));
            }
        }
        String withoutProcedures = code.replaceAll("(?i)db\\.index\\.(fulltext|vector)\\.queryNodes", "allowedProcedure");
        Matcher function = FUNCTION.matcher(withoutProcedures);
        while (function.find()) {
            violations.add(new Violation("$", "namespaced function '" + function.group(1) + "' is not allowed"));
        }
        Matcher nodes = NODE_LABELS.matcher(withoutProcedures);
        while (nodes.find()) {
            for (String label : nodes.group(1).split("[:|&!\\s]+")) {
                if (!label.isBlank() && !labels.contains(label)) {
                    violations.add(new Violation("$", "label '" + label + "' is not a registered type"));
                }
            }
        }
        Matcher rels = REL_TYPES.matcher(withoutProcedures);
        while (rels.find()) {
            for (String type : rels.group(1).split("[:|&!]+")) {
                if (!type.isBlank() && !relationshipTypes.contains(type)) {
                    violations.add(new Violation("$", "relationship type '" + type + "' is not registered"));
                }
            }
        }
        Matcher props = PROPERTY.matcher(withoutProcedures);
        while (props.find()) {
            String property = props.group(1);
            if (!properties.contains(property) && !property.startsWith("pke_")) {
                violations.add(new Violation("$", "property '" + property + "' is not registered"));
            }
        }
        return new Result(violations.isEmpty() ? withLimit(cypher.strip()) : cypher, List.copyOf(violations));
    }

    /** Appends {@code LIMIT max} when the query has no final LIMIT; lowers a final numeric LIMIT above the maximum. */
    String withLimit(String cypher) {
        String query = cypher.endsWith(";") ? cypher.substring(0, cypher.length() - 1).strip() : cypher;
        Matcher limit = LIMIT.matcher(strip(query));
        if (!limit.find()) {
            return query + "\nLIMIT " + maxLimit;
        }
        String value = limit.group(1);
        if (!value.startsWith("$") && Long.parseLong(value) > maxLimit) {
            return query.substring(0, query.toUpperCase(Locale.ROOT).lastIndexOf("LIMIT")) + "LIMIT " + maxLimit;
        }
        return query;
    }

    /** Removes comments and the content of string literals, so keywords inside strings are not matched. */
    static String strip(String cypher) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < cypher.length()) {
            char c = cypher.charAt(i);
            if (c == '/' && i + 1 < cypher.length() && cypher.charAt(i + 1) == '/') {
                while (i < cypher.length() && cypher.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < cypher.length() && cypher.charAt(i + 1) == '*') {
                int end = cypher.indexOf("*/", i + 2);
                i = end < 0 ? cypher.length() : end + 2;
                out.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                out.append(quote).append(quote);
                i++;
                while (i < cypher.length() && cypher.charAt(i) != quote) {
                    if (cypher.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
