package com.omnicore.pke.engine.extract;

import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.spi.journey.JourneyDefinition.ApiCall;
import com.omnicore.pke.spi.journey.JourneyDefinition.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identity and normalisation of {@code ApiOperation}s (ADR-012). A gateway API is identified by HTTP method, path
 * template and the gateway identity headers (moduleId, subModuleId, serviceId, screenId by default); a service
 * API by service, method and path. Only structure is kept: header names, parameter names, field names and types.
 */
public final class ApiOperations {

    public static final String GATEWAY = "gateway";
    public static final List<String> NATURAL_KEY = List.of("layer", "service", "httpMethod", "pathTemplate", "gatewayKey");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)(:[^}]*)?}");
    private static final Pattern POSTMAN_VARIABLE = Pattern.compile("\\{\\{([^}]+)}}|:([A-Za-z_][A-Za-z0-9_]*)");

    private ApiOperations() {
    }

    /** Leading slash, no duplicate or trailing slashes, {@code {id:regex}}, {@code :id} and {@code {{id}}} become {@code {id}}. */
    public static String normalisePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.strip();
        int query = p.indexOf('?');
        if (query >= 0) {
            p = p.substring(0, query);
        }
        p = p.replaceAll("^[a-zA-Z]+://[^/]+", "");
        Matcher postman = POSTMAN_VARIABLE.matcher(p);
        StringBuilder sb = new StringBuilder();
        while (postman.find()) {
            String name = postman.group(1) != null ? postman.group(1).strip() : postman.group(2);
            postman.appendReplacement(sb, Matcher.quoteReplacement("{" + name + "}"));
        }
        postman.appendTail(sb);
        p = PATH_VARIABLE.matcher(sb.toString()).replaceAll("{$1}");
        p = ("/" + p).replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public static List<String> pathParameters(String template) {
        List<String> names = new ArrayList<>();
        Matcher m = PATH_VARIABLE.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    public static String gatewayKey(Map<String, String> headers, List<String> identityHeaders) {
        return String.join("|", identityHeaders.stream().map(h -> value(headers, h)).toList());
    }

    /** Header lookup ignoring case; null-safe. */
    public static String value(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                .filter(v -> v != null).findFirst().orElse("").strip();
    }

    /** Properties of a gateway {@code ApiOperation} for a client-side API call. */
    public static Map<String, Object> gatewayProperties(ApiCall call, List<String> identityHeaders) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("layer", GATEWAY);
        props.put("service", GATEWAY);
        props.put("httpMethod", call.httpMethod().toUpperCase(Locale.ROOT));
        props.put("pathTemplate", normalisePath(call.pathTemplate()));
        props.put("gatewayKey", gatewayKey(call.gatewayHeaders(), identityHeaders));
        for (String header : List.of("moduleId", "subModuleId", "serviceId", "screenId")) {
            String v = value(call.gatewayHeaders(), header);
            props.put(header, v.isEmpty() ? null : v);
        }
        props.put("units", nonEmpty(value(call.gatewayHeaders(), "unit")));
        props.put("channels", nonEmpty(value(call.gatewayHeaders(), "channel")));
        props.put("otherHeaders", sorted(call.otherHeaders()));
        props.put("pathParameters", call.pathParameters() == null || call.pathParameters().isEmpty()
                ? pathParameters(normalisePath(call.pathTemplate())) : call.pathParameters());
        props.put("queryParameters", sorted(call.queryParameters()));
        props.put("requestFields", fieldsJson(call.requestFields()));
        props.put("responseFields", fieldsJson(call.responseFields()));
        return props;
    }

    /** Merges several observations of the same gateway API (from different sources) into one property set. */
    public static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> merged = new LinkedHashMap<>(a);
        b.forEach((k, v) -> {
            Object current = merged.get(k);
            if (current == null) {
                merged.put(k, v);
            } else if (current instanceof List<?> l1 && v instanceof List<?> l2) {
                TreeSet<String> union = new TreeSet<>();
                l1.forEach(x -> union.add(String.valueOf(x)));
                l2.forEach(x -> union.add(String.valueOf(x)));
                merged.put(k, List.copyOf(union));
            } else if (current instanceof String s1 && v instanceof String s2 && k.endsWith("Fields") && s2.length() > s1.length()) {
                merged.put(k, s2);
            }
        });
        return merged;
    }

    public static String fieldsJson(Field field) {
        return field == null ? null : CanonicalJson.write(fieldMap(field));
    }

    private static Map<String, Object> fieldMap(Field field) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (field.name() != null) {
            map.put("name", field.name());
        }
        map.put("type", field.type());
        if (!field.children().isEmpty()) {
            map.put("children", field.children().stream().map(ApiOperations::fieldMap).toList());
        }
        return map;
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : List.copyOf(new TreeSet<>(values));
    }

    private static List<String> nonEmpty(String value) {
        return value.isEmpty() ? List.of() : List.of(value);
    }
}
