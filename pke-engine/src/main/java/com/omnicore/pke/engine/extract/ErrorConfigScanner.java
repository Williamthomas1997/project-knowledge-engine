package com.omnicore.pke.engine.extract;

import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import java.util.List;
import java.util.Map;

/**
 * Error codes defined in release scripts: {@code INSERT INTO <table> (... <codeColumn> ...)} rows become
 * {@code ErrorCode} entities. The description goes into the evidence excerpt (shared entities keep only their key).
 */
public final class ErrorConfigScanner {

    public static final String METHOD = "extract.sql-error-config";

    private final String table;
    private final String codeColumn;
    private final String descriptionColumn;

    public ErrorConfigScanner(String table, String codeColumn, String descriptionColumn) {
        this.table = table;
        this.codeColumn = codeColumn;
        this.descriptionColumn = descriptionColumn;
    }

    public ExtractedKnowledge scan(String file, String sql, String artifactKey, String commit) {
        ExtractedKnowledge k = new ExtractedKnowledge(file);
        for (SqlInserts.Row row : SqlInserts.read(sql, table)) {
            String code = row.get(codeColumn);
            if (code == null || code.isBlank()) {
                continue;
            }
            String description = descriptionColumn == null ? null : row.get(descriptionColumn);
            String excerpt = description == null ? null : description.length() > 200 ? description.substring(0, 200) : description;
            EvidenceInput evidence = EvidenceInput.scoped(artifactKey, commit, file, file + ":" + row.line(), METHOD, excerpt);
            k.entity("ErrorCode", Map.of("code", code.strip()), List.of("code"), evidence);
        }
        return k;
    }
}
