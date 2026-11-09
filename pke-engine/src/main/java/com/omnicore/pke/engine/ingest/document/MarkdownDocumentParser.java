package com.omnicore.pke.engine.ingest.document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Markdown and plain text (CommonMark). One section per heading, with the heading path of its ancestors. */
@Component
@Order(10)
public class MarkdownDocumentParser implements DocumentParser {

    private final Parser parser = Parser.builder().build();
    private final TextContentRenderer renderer = TextContentRenderer.builder().build();

    @Override
    public String version() {
        return "markdown-1";
    }

    @Override
    public boolean supports(String mediaType, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")
                || "text/markdown".equals(mediaType) || "text/plain".equals(mediaType);
    }

    @Override
    public ParsedDocument parse(byte[] content, String filename) {
        Node document = parser.parse(new String(content, StandardCharsets.UTF_8));
        SectionBuilder sections = new SectionBuilder();
        String title = null;
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                String text = renderer.render(heading).strip();
                if (title == null && heading.getLevel() == 1) {
                    title = text;
                }
                sections.heading(heading.getLevel(), text, null);
            } else {
                sections.text(renderer.render(node).strip());
            }
        }
        List<ParsedSection> parsed = sections.finish();
        return new ParsedDocument("text/markdown", title, parsed, parsed.isEmpty() ? "EMPTY" : "PARSED");
    }

    /** Accumulates text under the current heading path; shared with the Tika parser. */
    static final class SectionBuilder {
        private final List<String> path = new ArrayList<>();
        private final List<Integer> levels = new ArrayList<>();
        private final List<ParsedSection> sections = new ArrayList<>();
        private String heading;
        private int level;
        private Integer page;
        private StringBuilder text = new StringBuilder();

        void heading(int newLevel, String title, Integer currentPage) {
            flush();
            while (!levels.isEmpty() && levels.getLast() >= newLevel) {
                levels.removeLast();
                path.removeLast();
            }
            levels.add(newLevel);
            path.add(title);
            heading = title;
            level = newLevel;
            page = currentPage;
        }

        void page(Integer currentPage) {
            if (page == null) {
                page = currentPage;
            }
        }

        void text(String block) {
            if (block != null && !block.isBlank()) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(block.strip());
            }
        }

        List<ParsedSection> finish() {
            flush();
            return List.copyOf(sections);
        }

        private void flush() {
            if (!text.isEmpty() || heading != null) {
                sections.add(new ParsedSection(List.copyOf(path), heading, level, page, text.toString()));
            }
            text = new StringBuilder();
            heading = null;
            page = null;
        }
    }
}
