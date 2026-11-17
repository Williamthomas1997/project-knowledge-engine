package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SectionView;
import com.omnicore.pke.engine.graph.SourceQueryService.VersionView;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code brd-section-diff}: compares the parsed version's sections with the previous version's, by heading path:
 * {@code added}, {@code changed} (different text), {@code removed}, and the count left {@code unchanged}. It reports;
 * {@code brd-sections} then sends only sections whose fingerprint is new to the model.
 */
@Component
class BrdSectionDiffStep implements PipelineStep {

    private final SourceQueryService sources;

    BrdSectionDiffStep(SourceQueryService sources) {
        this.sources = sources;
    }

    @Override
    public String name() {
        return "brd-section-diff";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        Map<String, Object> parsed = Steps.outputWith(context, "versionId", "parse");
        String key = Steps.string(parsed, "documentKey");
        String version = Steps.string(parsed, "version");
        List<VersionView> versions = sources.versions(key);
        String previous = null;
        for (VersionView v : versions) {
            if (v.version().equals(version)) {
                break;
            }
            previous = v.version();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("previousVersion", previous);
        out.putAll(diff(previous == null ? List.of() : sources.sections(key, previous), sources.sections(key, version)));
        return out;
    }

    static Map<String, Object> diff(List<SectionView> before, List<SectionView> after) {
        Map<List<String>, String> old = new LinkedHashMap<>();
        before.forEach(s -> old.put(s.headingPath(), s.text()));
        List<List<String>> added = new ArrayList<>();
        List<List<String>> changed = new ArrayList<>();
        int unchanged = 0;
        for (SectionView s : after) {
            if (!old.containsKey(s.headingPath())) {
                added.add(s.headingPath());
            } else if (!Objects.equals(old.remove(s.headingPath()), s.text())) {
                changed.add(s.headingPath());
            } else {
                unchanged++;
            }
        }
        return Map.of("added", added, "changed", changed, "removed", List.copyOf(old.keySet()), "unchanged", unchanged);
    }
}
