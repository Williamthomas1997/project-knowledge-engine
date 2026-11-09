package com.omnicore.pke.engine.ingest.document;

import java.util.Optional;

/**
 * Extension point for scanned documents (MASTER_SPEC §9.1). No OCR engine ships with the engine: the default
 * provider reports "not configured" and the document version is stored with status {@code OCR_REQUIRED}, so it
 * can be reprocessed once a provider (e.g. Tesseract) is added as a plugin.
 */
public interface OcrProvider {

    boolean configured();

    /** Text of the scanned document, if this provider can read it. */
    Optional<String> extractText(byte[] content, String filename);

    /** The default: no OCR. */
    OcrProvider NONE = new OcrProvider() {
        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public Optional<String> extractText(byte[] content, String filename) {
            return Optional.empty();
        }
    };
}
