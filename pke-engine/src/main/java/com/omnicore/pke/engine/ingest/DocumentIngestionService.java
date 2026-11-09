package com.omnicore.pke.engine.ingest;

import com.omnicore.pke.engine.graph.SourceWriter;
import com.omnicore.pke.engine.graph.SourceWriter.DocumentVersionInput;
import com.omnicore.pke.engine.graph.SourceWriter.SectionInput;
import com.omnicore.pke.engine.graph.SourceWriter.SourceResult;
import com.omnicore.pke.engine.ingest.document.DocumentParser;
import com.omnicore.pke.engine.ingest.document.DocumentParser.ParsedDocument;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.source.DocumentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Document ingestion (no LLM): store the original bytes, parse them into sections and record a source version.
 * The version identity is the content hash; the same bytes with the same parser version write nothing. Raw
 * sections are stored for citation and fulltext search but never embedded (ADR-007 §4).
 */
@Service
public class DocumentIngestionService {

    static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("^doc:[a-z0-9][a-z0-9._-]{0,127}$");

    private final DocumentStore store;
    private final List<DocumentParser> parsers;
    private final SourceWriter sources;

    public DocumentIngestionService(DocumentStore store, List<DocumentParser> parsers, SourceWriter sources) {
        this.store = store;
        this.parsers = parsers;
        this.sources = sources;
    }

    public record DocumentResult(SourceResult source, String parseStatus, int sections) {
    }

    /**
     * @param key optional logical key ({@code doc:<slug>}); defaults to the file name without extension, so a new
     *            upload of the same file name is a new version of the same document
     */
    public DocumentResult ingest(byte[] content, String filename, String key, String name, String actor) {
        if (content == null || content.length == 0) {
            throw ValidationFailedException.of("/file", "is empty");
        }
        if (content.length > MAX_BYTES) {
            throw ValidationFailedException.of("/file", "is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        if (filename == null || filename.isBlank()) {
            throw ValidationFailedException.of("/file", "needs a file name");
        }
        String logicalKey = key != null && !key.isBlank() ? key : defaultKey(filename);
        if (!KEY.matcher(logicalKey).matches()) {
            throw ValidationFailedException.of("/key", "must match " + KEY.pattern());
        }
        DocumentParser parser = parsers.stream().filter(p -> p.supports(null, filename)).findFirst()
                .orElseThrow(() -> ValidationFailedException.of("/file", "unsupported document type: " + filename));
        DocumentStore.Stored stored = store.put(content);
        ParsedDocument parsed = parser.parse(content, filename);
        List<SectionInput> sections = new ArrayList<>();
        for (int i = 0; i < parsed.sections().size(); i++) {
            var s = parsed.sections().get(i);
            sections.add(new SectionInput(i + 1, s.headingPath(), s.heading(), s.level(), s.page(), s.text(),
                    CanonicalJson.sha256(Map.of("path", s.headingPath(), "text", s.text()))));
        }
        String title = name != null && !name.isBlank() ? name : parsed.title() != null ? parsed.title() : filename;
        SourceResult result = sources.addDocumentVersion(new DocumentVersionInput(logicalKey, title, filename,
                parsed.mediaType(), stored.sha256(), stored.ref(), stored.sizeBytes(), parser.version(), parsed.status(),
                sections), actor);
        return new DocumentResult(result, parsed.status(), sections.size());
    }

    static String defaultKey(String filename) {
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return "doc:" + (slug.isEmpty() ? "document" : slug);
    }
}
