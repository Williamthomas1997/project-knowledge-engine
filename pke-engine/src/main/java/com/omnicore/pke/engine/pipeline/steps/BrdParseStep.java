package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceQueryService.SectionView;
import com.omnicore.pke.engine.graph.SourceQueryService.SourceView;
import com.omnicore.pke.engine.graph.SourceQueryService.VersionView;
import com.omnicore.pke.engine.kernel.PkeIds;
import com.omnicore.pke.spi.pipeline.PipelineStep;
import com.omnicore.pke.spi.pipeline.StepContext;
import com.omnicore.pke.spi.pipeline.StepFailure;
import com.omnicore.pke.spi.pipeline.StepFailure.ErrorClass;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code brd-parse}: resolves the document version to process (the current one unless {@code version} is given)
 * and checks it was parsed into sections. Documents are parsed when uploaded ({@code POST /api/sources/documents});
 * a version that needs OCR fails PERMANENT.
 */
@Component
class BrdParseStep implements PipelineStep {

    private final SourceQueryService sources;

    BrdParseStep(SourceQueryService sources) {
        this.sources = sources;
    }

    @Override
    public String name() {
        return "brd-parse";
    }

    @Override
    public Map<String, Object> execute(StepContext context) {
        String key = Steps.string(context.input(), "documentKey");
        SourceView source = sources.byKey(key);
        if (!"document".equals(source.kind()) || !"ACTIVE".equals(source.status())) {
            throw new StepFailure(ErrorClass.PERMANENT, key + " is not an active document (" + source.kind() + ", "
                    + source.status() + ")");
        }
        String version = Steps.string(context.input(), "version");
        String resolved = version != null ? version : source.currentVersion();
        VersionView view = sources.versions(key).stream().filter(v -> v.version().equals(resolved)).findFirst()
                .orElseThrow(() -> new StepFailure(ErrorClass.PERMANENT, key + " has no version " + resolved));
        String parseStatus = Steps.string(view.properties(), "parseStatus");
        if ("OCR_REQUIRED".equals(parseStatus)) {
            throw new StepFailure(ErrorClass.PERMANENT, key + " version " + resolved + " has no text layer (OCR required)");
        }
        List<SectionView> sections = sources.sections(key, resolved);
        String title = sections.stream().filter(s -> s.heading() != null && !s.heading().isBlank())
                .map(SectionView::heading).findFirst().orElse(String.valueOf(source.properties().get("name")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentKey", key);
        out.put("version", resolved);
        out.put("versionId", PkeIds.sourceVersionId(key, resolved));
        out.put("parseStatus", parseStatus);
        out.put("validAt", Steps.isoDateTime(view.properties().get("recordedAt")));
        out.put("title", title);
        out.put("sectionCount", sections.size());
        return out;
    }
}
