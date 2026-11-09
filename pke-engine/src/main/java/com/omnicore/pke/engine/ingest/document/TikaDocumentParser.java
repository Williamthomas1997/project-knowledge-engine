package com.omnicore.pke.engine.ingest.document;

import com.omnicore.pke.engine.ingest.document.MarkdownDocumentParser.SectionBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * PDF, Word, Excel, PowerPoint and HTML through Apache Tika's XHTML output. Keeps headings ({@code h1..h6}),
 * paragraphs, list items, table rows (cells joined with {@code " | "}) and PDF page numbers. A PDF without
 * extractable text is reported as {@code OCR_REQUIRED} unless an {@link OcrProvider} is configured.
 */
@Component
@Order(20)
public class TikaDocumentParser implements DocumentParser {

    private static final Pattern HEADING = Pattern.compile("h([1-6])");
    private static final List<String> EXTENSIONS = List.of(".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".html", ".htm");

    private final AutoDetectParser parser = new AutoDetectParser();
    private final OcrProvider ocr;

    public TikaDocumentParser(ObjectProvider<OcrProvider> ocr) {
        this.ocr = ocr.getIfAvailable(() -> OcrProvider.NONE);
    }

    @Override
    public String version() {
        return "tika-4.2.0-1";
    }

    @Override
    public boolean supports(String mediaType, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith)
                || mediaType != null && (mediaType.startsWith("application/") || mediaType.equals("text/html"));
    }

    @Override
    public ParsedDocument parse(byte[] content, String filename) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        Handler handler = new Handler();
        try (TikaInputStream in = TikaInputStream.get(content)) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse " + filename + ": " + e.getMessage(), e);
        }
        String mediaType = metadata.get("Content-Type");
        List<ParsedSection> sections = handler.sections.finish();
        if (sections.isEmpty() && mediaType != null && mediaType.contains("pdf")) {
            Optional<String> text = ocr.configured() ? ocr.extractText(content, filename) : Optional.empty();
            if (text.isEmpty()) {
                return new ParsedDocument(mediaType, metadata.get(TikaCoreProperties.TITLE), List.of(), "OCR_REQUIRED");
            }
            sections = List.of(new ParsedSection(List.of(), null, 0, null, text.get()));
        }
        return new ParsedDocument(mediaType, metadata.get(TikaCoreProperties.TITLE), sections,
                sections.isEmpty() ? "EMPTY" : "PARSED");
    }

    /** SAX handler over Tika's XHTML: tracks headings, blocks, table rows and PDF pages. */
    private static final class Handler extends DefaultHandler {
        private final SectionBuilder sections = new SectionBuilder();
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder row = new StringBuilder();
        private int headingLevel;
        private int page;
        private int tableDepth;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = localName.isEmpty() ? qName : localName;
            Matcher heading = HEADING.matcher(name);
            if (heading.matches()) {
                flushBlock();
                headingLevel = Integer.parseInt(heading.group(1));
            } else if ("div".equals(name) && "page".equals(attributes.getValue("class"))) {
                page++;
                sections.page(page);
            } else if ("table".equals(name)) {
                flushBlock();
                tableDepth++;
            } else if ("tr".equals(name)) {
                row.setLength(0);
            } else if ("p".equals(name) || "li".equals(name)) {
                flushBlock();
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName.isEmpty() ? qName : localName;
            if (HEADING.matcher(name).matches()) {
                String title = normalise(buffer.toString());
                buffer.setLength(0);
                if (!title.isEmpty()) {
                    sections.heading(headingLevel, title, page == 0 ? null : page);
                }
                headingLevel = 0;
            } else if ("td".equals(name) || "th".equals(name)) {
                String cell = normalise(buffer.toString());
                buffer.setLength(0);
                if (!row.isEmpty()) {
                    row.append(" | ");
                }
                row.append(cell);
            } else if ("tr".equals(name)) {
                if (!row.toString().replace("|", "").isBlank()) {
                    sections.text(row.toString());
                }
                row.setLength(0);
            } else if ("table".equals(name)) {
                tableDepth = Math.max(0, tableDepth - 1);
            } else if ("p".equals(name) || "li".equals(name) || "div".equals(name)) {
                if (tableDepth == 0) {
                    flushBlock();
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
        }

        @Override
        public void endDocument() {
            flushBlock();
        }

        private void flushBlock() {
            if (tableDepth > 0 || headingLevel > 0) {
                return;
            }
            String text = normalise(buffer.toString());
            buffer.setLength(0);
            sections.text(text);
        }

        private static String normalise(String text) {
            return text.replace(' ', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\s*\\n\\s*", "\n").strip();
        }
    }
}
