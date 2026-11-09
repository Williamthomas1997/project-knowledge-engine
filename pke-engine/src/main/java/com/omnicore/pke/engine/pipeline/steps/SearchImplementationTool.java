package com.omnicore.pke.engine.pipeline.steps;

import com.omnicore.pke.engine.graph.CardQueryService;
import com.omnicore.pke.engine.pipeline.skill.SkillTool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code search-implementation}: deterministic candidates for "which code implements this statement".
 * <ol>
 *   <li>Methods that throw an error code quoted in the text (strongest signal).</li>
 *   <li>Methods whose signature or class name contains a key term; APIs whose path contains one.</li>
 * </ol>
 * Utilities, configuration, constants, DTOs, mappers, logging and test code are never candidates (ADR-007 §6).
 */
@Component
class SearchImplementationTool implements SkillTool {

    private static final Pattern ERROR_CODE = Pattern.compile("\\b\\d{6}\\b");
    private static final Pattern EXCLUDED_PACKAGE = Pattern.compile(
            "(?i)\\.(util|utils|config|configuration|constant|constants|dto|dtos|mapper|mappers|logging|generated)\\.");
    private static final Pattern EXCLUDED_CLASS = Pattern.compile(
            "(?i).*(Util|Utils|Config|Configuration|Properties|Constants|Mapper|Exception|Errors|Paths|Request|Response)$");
    private static final Set<String> GENERIC = Set.of("system", "shall", "customer", "customers", "user", "with",
            "when", "from", "that", "this", "their", "must", "only", "request", "response", "data", "value");

    private final CardQueryService queries;

    SearchImplementationTool(CardQueryService queries) {
        this.queries = queries;
    }

    @Override
    public String name() {
        return "search-implementation";
    }

    @Override
    public String description() {
        return "Code elements (methods, APIs) that may implement a statement, by error code and key terms";
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) {
        String text = Steps.string(arguments, "text");
        Object limitArg = arguments.get("limit");
        int limit = limitArg == null ? 12 : ((Number) limitArg).intValue();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Integer> score = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>();
        Matcher matcher = ERROR_CODE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            codes.add(matcher.group());
        }
        if (!codes.isEmpty()) {
            for (Map<String, Object> m : queries.methodsThrowing(codes)) {
                if (excluded((String) m.get("fqcn"))) {
                    continue;
                }
                byId.put((String) m.get("id"), method(m));
                score.merge((String) m.get("id"), 10, Integer::sum);
            }
        }
        List<String> terms = terms(Steps.strings(arguments.get("keyTerms")));
        if (!terms.isEmpty()) {
            for (Map<String, Object> m : queries.methodsMatching(terms, 40)) {
                if (excluded((String) m.get("fqcn"))) {
                    continue;
                }
                byId.putIfAbsent((String) m.get("id"), method(m));
                score.merge((String) m.get("id"), ((Number) m.get("hits")).intValue(), Integer::sum);
            }
            for (Map<String, Object> a : queries.apisMatching(terms, 10)) {
                byId.putIfAbsent((String) a.get("id"), api(a));
                score.merge((String) a.get("id"), ((Number) a.get("hits")).intValue(), Integer::sum);
            }
        }
        List<Map<String, Object>> candidates = byId.values().stream()
                .sorted(Comparator.comparing((Map<String, Object> c) -> -score.get((String) c.get("id")))
                        .thenComparing(c -> (String) c.get("label")))
                .limit(limit).toList();
        return Map.of("candidates", candidates, "errorCodes", codes, "terms", terms);
    }

    static List<String> terms(List<String> keyTerms) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : keyTerms) {
            Arrays.stream(term.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                    .filter(w -> w.length() >= 4 && !GENERIC.contains(w)).forEach(terms::add);
        }
        return List.copyOf(terms);
    }

    private static boolean excluded(String fqcn) {
        if (fqcn == null) {
            return true;
        }
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return EXCLUDED_PACKAGE.matcher(fqcn).find() || EXCLUDED_CLASS.matcher(simple).matches();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> method(Map<String, Object> m) {
        String fqcn = (String) m.get("fqcn");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", m.get("id"));
        c.put("type", "JavaMethod");
        c.put("label", fqcn.substring(fqcn.lastIndexOf('.') + 1) + "#" + m.get("signature"));
        List<String> errorCodes = (List<String>) m.get("errorCodes");
        c.put("detail", errorCodes == null || errorCodes.isEmpty() ? fqcn : fqcn + "; throws " + String.join(", ", errorCodes));
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> api(Map<String, Object> a) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", a.get("id"));
        c.put("type", "ApiOperation");
        c.put("label", a.get("httpMethod") + " " + a.get("pathTemplate") + " (" + a.get("layer") + ", " + a.get("service") + ")");
        List<String> handlers = (List<String>) a.get("handlers");
        c.put("detail", handlers == null || handlers.isEmpty() ? "no handler resolved" : "handled by " + String.join(", ", handlers));
        return c;
    }
}
