package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.graph.RetrievalQueries;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Task context for people and coding agents (MASTER_SPEC §20, ADR-007 §7 step 6): hybrid search, graph expansion
 * from the best hits with query templates (fallback: guarded LLM Cypher), evidence for every fact, then packing into
 * a token budget in four sections: verified facts, deterministic facts, AI inferences, conflicts and open questions.
 * Every item carries citations {@code source@version#location}.
 */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    public record ContextRequest(String task, Integer budgetTokens, Integer depth, Integer seeds) {
    }

    public record Item(String id, String kind, String type, String label, String detail, double score, String epistemic,
                       List<String> citations, String via, int tokens) {
    }

    public record ContextResult(String task, int budgetTokens, int usedTokens, int truncated, List<Item> verified,
                                List<Item> deterministic, List<Item> inferred, List<Item> conflicts, List<String> citations,
                                List<String> templates, String fallbackCypher, String markdown, long millis) {
    }

    private final RetrievalService retrieval;
    private final QueryTemplates templates;
    private final CypherFallback fallback;
    private final RetrievalQueries queries;

    public ContextService(RetrievalService retrieval, QueryTemplates templates, CypherFallback fallback,
                          RetrievalQueries queries) {
        this.retrieval = retrieval;
        this.templates = templates;
        this.fallback = fallback;
        this.queries = queries;
    }

    private record Candidate(String id, String kind, String type, String label, String detail, double score, String via) {
    }

    public ContextResult context(ContextRequest request) {
        long started = System.nanoTime();
        String task = request.task() == null ? "" : request.task().strip();
        if (task.isEmpty()) {
            throw ValidationFailedException.of("/task", "is required");
        }
        int budget = request.budgetTokens() == null ? 2000 : Math.clamp(request.budgetTokens(), 100, 50_000);
        int depth = request.depth() == null ? 2 : Math.clamp(request.depth(), 0, 4);
        int seedCount = request.seeds() == null ? 5 : Math.clamp(request.seeds(), 1, 20);

        RetrievalService.SearchResult search = retrieval.search(RetrievalService.SearchRequest.of(task, 12));
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (RetrievalService.Hit hit : search.hits()) {
            candidates.putIfAbsent(hit.id(), new Candidate(hit.id(), hit.kind(), hit.type(), hit.label(),
                    hit.text() == null ? null : truncate(hit.text(), 400), hit.score(), "search"));
        }
        List<RetrievalService.Hit> seeds = seeds(task, search.hits(), seedCount);
        Set<String> used = new LinkedHashSet<>();
        boolean anyTemplate = false;
        if (depth > 0) {
            for (RetrievalService.Hit seed : seeds) {
                for (QueryTemplates.Template template : templates.select(task, seed.type())) {
                    if ("evidence-of".equals(template.id())) {
                        continue; // evidence is attached to every fact below
                    }
                    anyTemplate = true;
                    used.add(template.id());
                    try {
                        for (Map<String, Object> row : templates.run(template.id(), seed.id(), 50).rows()) {
                            int rowDepth = row.get("depth") == null ? 1 : ((Number) row.get("depth")).intValue();
                            if (rowDepth > depth || row.get("id") == null) {
                                continue;
                            }
                            double score = seed.score() * Math.pow(0.8, Math.max(rowDepth, 1));
                            Candidate existing = candidates.get((String) row.get("id"));
                            if (existing == null || existing.score() < score) {
                                candidates.put((String) row.get("id"), new Candidate((String) row.get("id"),
                                        (String) row.get("kind"), (String) row.get("type"), (String) row.get("label"),
                                        (String) row.get("detail"), existing == null ? score : Math.max(score, existing.score()),
                                        existing == null ? template.id() : existing.via()));
                            }
                        }
                    } catch (ValidationFailedException e) {
                        log.warn("Template {} failed for seed {}: {}", template.id(), seed.id(), e.violations());
                    }
                }
            }
        }
        String fallbackCypher = null;
        if (!anyTemplate && !seeds.isEmpty()) {
            java.util.Optional<CypherFallback.Answer> answer;
            try {
                answer = fallback.answer(task, seeds);
            } catch (RuntimeException e) {
                // the fallback is best effort: a model failure must not lose the context gathered so far
                log.warn("Cypher fallback failed for task context: {}", e.getMessage());
                answer = java.util.Optional.empty();
            }
            if (answer.isPresent()) {
                fallbackCypher = answer.get().cypher();
                double base = seeds.getFirst().score() * 0.8;
                for (Map<String, Object> row : answer.get().rows()) {
                    Object id = row.get("id");
                    if (id != null) {
                        candidates.putIfAbsent(String.valueOf(id), new Candidate(String.valueOf(id), "ENTITY",
                                String.valueOf(row.get("type")), String.valueOf(row.get("label")),
                                row.get("detail") == null ? null : String.valueOf(row.get("detail")), base, "fallback"));
                    }
                }
            }
        }

        // the source sections of what was found (a card's section): document evidence next to the fact
        List<String> found = candidates.values().stream().filter(c -> "ENTITY".equals(c.kind())).map(Candidate::id).toList();
        for (Map<String, Object> cited : queries.citedSections(found)) {
            Candidate owner = candidates.get((String) cited.get("entityId"));
            String sectionId = (String) cited.get("sectionId");
            if (owner != null && !candidates.containsKey(sectionId)) {
                candidates.put(sectionId, new Candidate(sectionId, "SECTION", "Section", (String) cited.get("heading"), null,
                        owner.score() * 0.9, "source-of:" + owner.id()));
            }
        }

        // facts: epistemic status and evidence (citations)
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        List<String> factIds = candidates.values().stream().filter(c -> !"SECTION".equals(c.kind())).map(Candidate::id).toList();
        queries.facts(factIds).forEach(f -> facts.put((String) f.get("id"), f));
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        queries.sections(candidates.values().stream().filter(c -> "SECTION".equals(c.kind())).map(Candidate::id).toList())
                .forEach(s -> sections.put((String) s.get("id"), s));

        List<Item> verified = new ArrayList<>();
        List<Item> deterministic = new ArrayList<>();
        List<Item> inferred = new ArrayList<>();
        List<Item> conflicts = new ArrayList<>();
        List<Candidate> ordered = candidates.values().stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList();
        List<String> entityIds = ordered.stream().filter(c -> "ENTITY".equals(c.kind())).map(Candidate::id).toList();
        int usedTokens = 0;
        int truncated = 0;
        for (Map<String, Object> q : queries.openQuestions(entityIds)) {
            String line = "Open question: " + q.get("question");
            Item item = new Item((String) q.get("id"), "QUESTION", (String) q.get("kind"), line, null, 1.0, "OPEN",
                    List.of("question:" + q.get("id")), "questions", tokens(line));
            if (usedTokens + item.tokens() > budget) {
                truncated++;
                continue;
            }
            usedTokens += item.tokens();
            conflicts.add(item);
        }
        for (Candidate c : ordered) {
            Item item;
            if ("SECTION".equals(c.kind())) {
                Map<String, Object> s = sections.get(c.id());
                if (s == null) {
                    continue;
                }
                String citation = s.get("artifactKey") + "@" + s.get("version") + "#" + s.get("heading");
                item = item(c, "OBSERVED", List.of(citation), truncate(String.valueOf(s.get("text")), 600));
            } else if ("EVIDENCE".equals(c.kind())) {
                continue;
            } else {
                Map<String, Object> f = facts.get(c.id());
                if (f == null || !"CURRENT".equals(f.get("status"))) {
                    continue;
                }
                item = item(c, (String) f.get("epistemic"), citations(f), c.detail());
            }
            if (usedTokens + item.tokens() > budget) {
                truncated++;
                continue;
            }
            usedTokens += item.tokens();
            switch (item.epistemic() == null ? "" : item.epistemic()) {
                case "HUMAN_VERIFIED" -> verified.add(item);
                case "INFERRED_AI" -> inferred.add(item);
                case "CONTRADICTED", "SUPERSEDED" -> conflicts.add(item);
                default -> deterministic.add(item);
            }
        }
        Set<String> allCitations = new LinkedHashSet<>();
        for (List<Item> section : List.of(verified, deterministic, inferred, conflicts)) {
            section.forEach(i -> allCitations.addAll(i.citations()));
        }
        String markdown = markdown(task, verified, deterministic, inferred, conflicts);
        return new ContextResult(task, budget, usedTokens, truncated, verified, deterministic, inferred, conflicts,
                List.copyOf(allCitations), List.copyOf(used), fallbackCypher, markdown, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * Seeds for graph expansion: the best entity hits, at most two per type (so one noisy type cannot take every
     * seed), preferring types that have query templates.
     */
    private List<RetrievalService.Hit> seeds(String task, List<RetrievalService.Hit> hits, int count) {
        java.util.Map<String, Integer> perType = new java.util.HashMap<>();
        List<RetrievalService.Hit> withTemplates = new ArrayList<>();
        List<RetrievalService.Hit> others = new ArrayList<>();
        for (RetrievalService.Hit hit : hits) {
            if (!"ENTITY".equals(hit.kind()) || perType.merge(hit.type(), 1, Integer::sum) > 2) {
                continue;
            }
            (templates.select(task, hit.type()).isEmpty() ? others : withTemplates).add(hit);
        }
        List<RetrievalService.Hit> seeds = new ArrayList<>(withTemplates);
        seeds.addAll(others);
        return seeds.subList(0, Math.min(count, seeds.size()));
    }

    private static Item item(Candidate c, String epistemic, List<String> citations, String detail) {
        String text = render(c.label(), detail, citations);
        return new Item(c.id(), c.kind(), c.type(), c.label(), detail, c.score(), epistemic, citations, c.via(), tokens(text));
    }

    /** {@code source@version#location}; the location is the cited section's heading when there is one. */
    @SuppressWarnings("unchecked")
    static List<String> citations(Map<String, Object> fact) {
        Set<String> citations = new LinkedHashSet<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) fact.getOrDefault("evidence", List.of())) {
            if (e == null || e.get("sourceArtifactId") == null) {
                continue;
            }
            Object location = e.get("section") != null ? e.get("section") : e.get("location");
            citations.add(e.get("sourceArtifactId") + "@" + (e.get("sourceVersion") == null ? "" : e.get("sourceVersion"))
                    + "#" + (location == null ? "" : location));
            if (citations.size() >= 3) {
                break;
            }
        }
        return List.copyOf(citations);
    }

    private static String render(String label, String detail, List<String> citations) {
        return "- " + label + (detail == null || detail.isBlank() ? "" : " — " + detail)
                + (citations.isEmpty() ? "" : " [" + String.join("; ", citations) + "]");
    }

    private static String markdown(String task, List<Item> verified, List<Item> deterministic, List<Item> inferred,
                                   List<Item> conflicts) {
        StringBuilder md = new StringBuilder("# Context: ").append(task).append("\n");
        section(md, "Verified facts", verified);
        section(md, "Deterministic facts (code, documents, extractors)", deterministic);
        section(md, "AI inferences (check before relying on them)", inferred);
        section(md, "Conflicts and open questions", conflicts);
        return md.toString();
    }

    private static void section(StringBuilder md, String title, List<Item> items) {
        md.append("\n## ").append(title).append("\n");
        if (items.isEmpty()) {
            md.append("- none\n");
        }
        items.forEach(i -> md.append(render(i.label(), i.detail(), i.citations())).append("\n"));
    }

    static int tokens(String text) {
        return (int) Math.ceil(text.length() / 4.0);
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
