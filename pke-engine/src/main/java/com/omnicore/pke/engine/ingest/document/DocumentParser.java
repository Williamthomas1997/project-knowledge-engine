package com.omnicore.pke.engine.ingest.document;

import java.util.List;

/** Turns document bytes into ordered sections. Deterministic: the same bytes and version give the same sections. */
public interface DocumentParser {

    /** Changes whenever the parser's output for the same input could change (triggers reparsing). */
    String version();

    boolean supports(String mediaType, String filename);

    ParsedDocument parse(byte[] content, String filename);

    /**
     * @param status {@code PARSED}, {@code EMPTY} (no text at all), or {@code OCR_REQUIRED} (scanned pages, no OCR
     *               engine configured; see {@link OcrProvider})
     */
    record ParsedDocument(String mediaType, String title, List<ParsedSection> sections, String status) {
    }

    /** @param level heading level (0 = text before the first heading) */
    record ParsedSection(List<String> headingPath, String heading, int level, Integer page, String text) {
    }
}
