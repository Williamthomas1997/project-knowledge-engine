package com.omnicore.pke.engine.retrieval;

import com.omnicore.pke.engine.graph.EvalRunStore;
import com.omnicore.pke.engine.graph.RetrievalQueries;
import com.omnicore.pke.engine.index.IndexMaintenance;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.engine.kernel.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Retrieval evaluation (MASTER_SPEC §10.5): datasets {@code eval/datasets/*.yaml} of question → expected entities,
 * relationship types and sources, run against the current graph.
 * <ul>
 *   <li>Search: recall@k, precision@k, MRR, nDCG@k (an expectation counts as found if any matching entity is hit).</li>
 *   <li>Context: context recall (expected entities in the task context), graph completeness (expected relationship
 *       types in the context), citation correctness (citations that resolve to an existing source version), source
 *       coverage (expected sources cited), latency and tokens.</li>
 * </ul>
 * Also the {@link IndexMaintenance.GenerationEvaluator}: mean recall@k when searching a given embedding generation.
 */
@Service
public class EvaluationService implements IndexMaintenance.GenerationEvaluator {

    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    public record Expectation(String type, Map<String, Object> props, String textContains) {
    }

    public record Question(String id, String question, List<Expectation> entities, List<String> facts, List<String> sources) {
    }

    public record Dataset(String id, String version, int k, List<Question> questions) {
    }

    private final RetrievalService retrieval;
    private final ContextService context;
    private final EvalRunStore store;
    private final RetrievalQueries queries;
    private final List<String> locations;
    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();

    public EvaluationService(RetrievalService retrieval, ContextService context, EvalRunStore store, RetrievalQueries queries,
                             @Value("${pke.eval.locations:classpath*:eval/datasets/*.yaml}") List<String> locations) {
        this.retrieval = retrieval;
        this.context = context;
        this.store = store;
        this.queries = queries;
        this.locations = List.copyOf(locations);
    }

    public synchronized Map<String, Dataset> datasets() {
        if (datasets.isEmpty()) {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (String location : locations) {
                try {
                    for (Resource resource : resolver.getResources(location)) {
                        Dataset dataset = parse(resource);
                        datasets.put(dataset.id(), dataset);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read evaluation datasets at " + location, e);
                }
            }
        }
        return Map.copyOf(datasets);
    }

    /** Runs one dataset (or all when {@code datasetId} is null) and stores the run. */
    public Map<String, Object> run(String datasetId, String generation) {
        List<Dataset> selected = datasetId == null ? List.copyOf(datasets().values())
                : List.of(java.util.Optional.ofNullable(datasets().get(datasetId))
                .orElseThrow(() -> new NotFoundException("Evaluation dataset " + datasetId + " does not exist")));
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> perQuestion = new ArrayList<>();
        for (Dataset dataset : selected) {
            for (Question q : dataset.questions()) {
                perQuestion.add(evaluate(dataset, q, generation));
            }
        }
        Map<String, Object> summary = summary(perQuestion);
        String id = store.save(datasetId == null ? "all" : datasetId, generation, CanonicalJson.write(summary),
                CanonicalJson.write(perQuestion));
        result.put("id", id);
        result.put("dataset", datasetId == null ? "all" : datasetId);
        result.put("generation", generation);
        result.put("summary", summary);
        result.put("questions", perQuestion);
        return result;
    }

    @Override
    public double recall(String generationId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Dataset dataset : datasets().values()) {
            for (Question q : dataset.questions()) {
                results.add(searchMetrics(dataset, q, generationId));
            }
        }
        return results.stream().mapToDouble(r -> ((Number) r.get("recallAtK")).doubleValue()).average().orElse(0);
    }

    Map<String, Object> evaluate(Dataset dataset, Question q, String generation) {
        Map<String, Object> r = searchMetrics(dataset, q, generation);
        List<List<String>> expected = resolve(q);
        long started = System.nanoTime();
        ContextService.ContextResult ctx = context.context(new ContextService.ContextRequest(q.question(), 2000, 2, 5));
        long contextMillis = (System.nanoTime() - started) / 1_000_000;
        Set<String> contextIds = new HashSet<>();
        Set<String> contextTypes = new HashSet<>();
        for (List<ContextService.Item> items : List.of(ctx.verified(), ctx.deterministic(), ctx.inferred(), ctx.conflicts())) {
            items.forEach(i -> {
                contextIds.add(i.id());
                if ("RELATIONSHIP".equals(i.kind())) {
                    contextTypes.add(i.type());
                }
            });
        }
        long foundInContext = expected.stream().filter(ids -> ids.stream().anyMatch(contextIds::contains)).count();
        r.put("contextRecall", ratio(foundInContext, expected.size()));
        r.put("graphCompleteness", q.facts().isEmpty() ? ratio(foundInContext, expected.size())
                : ratio(q.facts().stream().filter(contextTypes::contains).count(), q.facts().size()));
        List<String> citations = ctx.citations().stream().filter(c -> !c.startsWith("question:")).toList();
        List<String> unresolved = citations.stream().filter(c -> !resolves(c)).toList();
        long valid = citations.size() - unresolved.size();
        r.put("unresolvedCitations", unresolved);
        r.put("citationCorrectness", citations.isEmpty() ? 0.0 : ratio(valid, citations.size()));
        r.put("sourceCoverage", q.sources().isEmpty() ? 1.0 : ratio(q.sources().stream()
                .filter(s -> citations.stream().anyMatch(c -> c.startsWith(s + "@"))).count(), q.sources().size()));
        r.put("contextTokens", ctx.usedTokens());
        r.put("contextMillis", contextMillis);
        r.put("templates", ctx.templates());
        return r;
    }

    private Map<String, Object> searchMetrics(Dataset dataset, Question q, String generation) {
        int k = dataset.k();
        List<List<String>> expected = resolve(q);
        RetrievalService.SearchResult search = retrieval.search(new RetrievalService.SearchRequest(q.question(), k, null, null,
                null, null, null, generation));
        List<String> ranked = search.hits().stream().map(RetrievalService.Hit::id).toList();
        Set<String> relevant = new HashSet<>();
        expected.forEach(relevant::addAll);
        long found = expected.stream().filter(ids -> ids.stream().anyMatch(ranked::contains)).count();
        long relevantHits = ranked.stream().filter(relevant::contains).count();
        int firstRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                firstRank = i + 1;
                break;
            }
        }
        double dcg = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                dcg += 1 / log2(i + 2);
            }
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(k, expected.size()); i++) {
            idcg += 1 / log2(i + 2);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dataset", dataset.id());
        r.put("question", q.id());
        r.put("k", k);
        r.put("recallAtK", ratio(found, expected.size()));
        r.put("precisionAtK", ratio(relevantHits, k));
        r.put("mrr", firstRank == 0 ? 0.0 : 1.0 / firstRank);
        r.put("ndcgAtK", idcg == 0 ? 0.0 : Math.min(1.0, dcg / idcg));
        r.put("searchMillis", search.millis());
        r.put("unresolvedExpectations", expected.stream().filter(List::isEmpty).count());
        return r;
    }

    private List<List<String>> resolve(Question q) {
        return q.entities().stream().map(e -> store.resolve(e.type(), e.props(), e.textContains())).toList();
    }

    private boolean resolves(String citation) {
        int at = citation.indexOf('@');
        int hash = citation.indexOf('#', at + 1);
        if (at <= 0 || hash < 0) {
            return false;
        }
        String version = citation.substring(at + 1, hash);
        return queries.sourceVersionExists(citation.substring(0, at), version.isEmpty() ? null : version);
    }

    private static Map<String, Object> summary(List<Map<String, Object>> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("questions", results.size());
        for (String metric : List.of("recallAtK", "precisionAtK", "mrr", "ndcgAtK", "contextRecall", "graphCompleteness",
                "citationCorrectness", "sourceCoverage", "contextTokens", "searchMillis", "contextMillis")) {
            summary.put(metric, round(results.stream().mapToDouble(r -> ((Number) r.get(metric)).doubleValue()).average().orElse(0)));
        }
        return summary;
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0.0 : round((double) part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    @SuppressWarnings("unchecked")
    private static Dataset parse(Resource resource) throws IOException {
        JsonNode tree;
        try (InputStream in = resource.getInputStream()) {
            tree = YAML.readTree(in);
        }
        List<Question> questions = new ArrayList<>();
        for (JsonNode q : tree.get("questions")) {
            List<Expectation> entities = new ArrayList<>();
            for (JsonNode e : q.path("expect").path("entities")) {
                entities.add(new Expectation(e.get("type").asString(),
                        e.has("props") ? YAML.convertValue(e.get("props"), Map.class) : Map.of(),
                        e.path("textContains").asString(null)));
            }
            questions.add(new Question(q.get("id").asString(), q.get("question").asString(), entities,
                    strings(q.path("expect").path("facts")), strings(q.path("expect").path("sources"))));
        }
        return new Dataset(tree.get("id").asString(), tree.path("version").asString("1.0.0"), tree.path("k").asInt(5),
                List.copyOf(questions));
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asString()));
        return values;
    }
}
