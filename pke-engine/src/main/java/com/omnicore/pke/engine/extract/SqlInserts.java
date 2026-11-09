package com.omnicore.pke.engine.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code INSERT INTO table (columns) VALUES (values);} statements from SQL scripts (Oracle or Postgres).
 * Handles quoted strings with {@code ''} escapes, quoted column names, schema prefixes and nested expressions such
 * as {@code (SELECT MAX(id)+1 FROM t)}. Values that are not plain literals are returned as written.
 */
public final class SqlInserts {

    private static final Pattern INSERT = Pattern.compile(
            "INSERT\\s+INTO\\s+([\\w.\"]+)\\s*\\(([^)]*)\\)\\s*VALUES\\s*\\(", Pattern.CASE_INSENSITIVE);

    private SqlInserts() {
    }

    /** One row; column names upper-case; {@code null} for SQL NULL. */
    public record Row(String table, Map<String, String> values, int line) {
        public String get(String column) {
            return values.get(column.toUpperCase(Locale.ROOT));
        }
    }

    /** Rows inserted into {@code table} (compared without schema prefix and quotes, ignoring case). */
    public static List<Row> read(String sql, String table) {
        List<Row> rows = new ArrayList<>();
        Matcher m = INSERT.matcher(sql);
        int from = 0;
        while (m.find(from)) {
            String name = unquote(m.group(1));
            name = name.substring(name.lastIndexOf('.') + 1);
            int valuesStart = m.end();
            int end = closingParen(sql, valuesStart);
            if (end < 0) {
                break;
            }
            from = end + 1;
            if (!name.equalsIgnoreCase(table)) {
                continue;
            }
            List<String> columns = split(m.group(2)).stream().map(c -> unquote(c).toUpperCase(Locale.ROOT)).toList();
            List<String> values = split(sql.substring(valuesStart, end)).stream().map(SqlInserts::value).toList();
            if (columns.size() != values.size()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i), values.get(i));
            }
            rows.add(new Row(name, row, lineOf(sql, m.start())));
        }
        return rows;
    }

    private static int closingParen(String sql, int start) {
        int depth = 1;
        boolean quoted = false;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Splits on commas outside quotes and parentheses. */
    private static List<String> split(String list) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                parts.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().strip());
        }
        return parts;
    }

    private static String value(String raw) {
        if (raw.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        return raw;
    }

    private static String unquote(String name) {
        return name.strip().replace("\"", "");
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
