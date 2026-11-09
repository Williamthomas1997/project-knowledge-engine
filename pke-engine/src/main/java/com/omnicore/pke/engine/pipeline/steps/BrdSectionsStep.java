package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.FingerprintStore;
import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SectionView;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.pipeline.Hashes;
import com.omnicore.pke.engine.pipeline.skill.SkillRegistry;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code brd-sections}: selects the sections that need the model. A section is skipped (no model call, no write)
 * when its fingerprint, hash(document, heading path, text, card and reconcile skill versions), was already
 * processed. Empty sections are skipped. Also lists every heading path of the version, so cards whose section
 * disappeared can be invalidated.
 */
@Component
class BrdSectionsStep implements PipelineStep {

    static final String FINGERPRINT_KIND = "brd-section";
    /** Longer sections are sent to the card skill in parts (about 2,000 tokens each). */
    static final int MAX_PART_CHARS = 8000;

    private final SourceQueryService sources;
    private final FingerprintStore fingerprints;
    private final SkillRegistry skills;

    BrdSectionsStep(SourceQueryService sources, FingerprintStore fingerprints, SkillRegistry skills) {
        this.sources = sources;
        this.fingerprints = fingerprints;
        this.skills = skills;
    }

    @Override
    public String name() {
        return "brd-sections";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        Map<String, Object> parsed = Steps.outputWith(context, "versionId", "parse");
        String key = Steps.string(parsed, "documentKey");
        String version = Steps.string(parsed, "version");
        String cardSkill = skills.active(Steps.CARD_SKILL).ref();
        String reconcileSkill = skills.active(Steps.RECONCILE_SKILL).ref();
        List<Map<String, Object>> selected = new ArrayList<>();
        List<Map<String, Object>> cardsInput = new ArrayList<>();
        List<List<String>> currentPaths = new ArrayList<>();
        int unchanged = 0;
        int empty = 0;
        for (SectionView section : sources.sections(key, version)) {
            currentPaths.add(section.headingPath());
            if (section.text() == null || section.text().isBlank()) {
                empty++;
                continue;
            }
            List<String> parts = split(section.text(), MAX_PART_CHARS);
            for (int p = 0; p < parts.size(); p++) {
                String text = parts.get(p);
                String hash = Hashes.sha256(text);
                Map<String, Object> fingerprintKey = new LinkedHashMap<>(Map.of("document", key,
                        "headingPath", section.headingPath(), "textHash", hash, "skills", List.of(cardSkill, reconcileSkill)));
                if (parts.size() > 1) {
                    fingerprintKey.put("part", p);
                }
                String fingerprint = CanonicalJson.sha256(fingerprintKey);
                if (fingerprints.contains(FINGERPRINT_KIND, fingerprint)) {
                    unchanged++;
                    continue;
                }
                String itemKey = parts.size() == 1 ? section.id() : section.id() + "#" + (p + 1);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", itemKey);
                entry.put("sectionId", section.id());
                entry.put("headingPath", section.headingPath());
                entry.put("heading", section.heading());
                entry.put("hash", hash);
                entry.put("fingerprint", fingerprint);
                entry.put("excerpt", Steps.truncate(text, 2000));
                selected.add(entry);
                Map<String, Object> skillInput = new LinkedHashMap<>();
                skillInput.put("document", Map.of("key", key, "title", Steps.string(parsed, "title")));
                skillInput.put("section", Map.of("headingPath", section.headingPath(), "heading",
                        section.heading() == null ? "" : section.heading(), "text", text));
                cardsInput.add(Map.of("key", itemKey, "input", skillInput));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(parsed);
        out.put("sections", selected);
        out.put("cardsInput", cardsInput);
        out.put("currentPaths", currentPaths);
        out.put("unchanged", unchanged);
        out.put("empty", empty);
        return out;
    }

    /**
     * Splits a long section into parts of at most {@code max} characters at paragraph boundaries (then line, then
     * sentence boundaries, then hard), so a document without headings does not become one huge prompt.
     */
    static List<String> split(String text, int max) {
        List<String> parts = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > max) {
            int cut = lastBoundary(rest, max, "\n\n");
            if (cut <= 0) {
                cut = lastBoundary(rest, max, "\n");
            }
            if (cut <= 0) {
                cut = lastBoundary(rest, max, ". ");
            }
            if (cut <= 0) {
                cut = max;
            }
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    private static int lastBoundary(String text, int max, String separator) {
        int at = text.lastIndexOf(separator, max - separator.length());
        return at < max / 4 ? -1 : at + separator.length();
    }
}
