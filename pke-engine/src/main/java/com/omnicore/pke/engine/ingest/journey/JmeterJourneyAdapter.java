package com.omnicore.pke.engine.ingest.journey;

import com.omnicore.pke.spi.journey.JourneyContext;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import com.omnicore.pke.spi.journey.JourneyDefinition.ApiCall;
import com.omnicore.pke.spi.journey.JourneyDefinition.Feed;
import com.omnicore.pke.spi.journey.JourneyDefinition.Field;
import com.omnicore.pke.spi.journey.JourneyDefinition.Step;
import com.omnicore.pke.spi.journey.JourneyDefinition.Unresolved;
import com.omnicore.pke.spi.journey.JourneySourceAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;

/**
 * JMeter test plans ({@code .jmx}). The test plan is the journey; each Transaction Controller is a step and its
 * HTTP sampler the call (a sampler outside any controller is a step of its own). JSON and regex extractors define
 * variables; a later {@code ${var}} in a path, header or body field gives a {@code FEEDS} edge. Values are dropped
 * except gateway header codes.
 */
@Component
public class JmeterJourneyAdapter implements JourneySourceAdapter {

    private static final Pattern USE = Pattern.compile("\\$\\{([^}]+)}");

    @Override
    public String id() {
        return "journey.jmeter";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(JourneyContext context, Path file) {
        return file.toString().endsWith(".jmx");
    }

    @Override
    public List<JourneyDefinition> read(JourneyContext context, Path file) {
        Document doc = parse(context.root().resolve(file));
        Element plan = first(doc.getDocumentElement(), "TestPlan").orElseThrow(() ->
                new IllegalArgumentException(file + " has no TestPlan"));
        String name = plan.getAttribute("testname");
        List<Step> steps = new ArrayList<>();
        List<Feed> feeds = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        Map<String, Integer> setBy = new HashMap<>();
        walk(context, doc.getDocumentElement(), null, steps, feeds, unresolved, setBy);
        return List.of(new JourneyDefinition(JourneySupport.key(name), name, "jmeter", file.toString().replace('\\', '/'),
                steps, feeds, List.of(), unresolved));
    }

    /** Walks elements in document order; JMeter keeps an element's children in the {@code hashTree} that follows it. */
    private void walk(JourneyContext context, Element element, String transaction, List<Step> steps, List<Feed> feeds,
                      List<Unresolved> unresolved, Map<String, Integer> setBy) {
        List<Element> children = children(element);
        for (int i = 0; i < children.size(); i++) {
            Element child = children.get(i);
            if ("hashTree".equals(child.getTagName())) {
                // a tree that does not belong to a preceding element (the document's top-level trees)
                walk(context, child, transaction, steps, feeds, unresolved, setBy);
                continue;
            }
            Element tree = i + 1 < children.size() && "hashTree".equals(children.get(i + 1).getTagName()) ? children.get(i + 1) : null;
            if (tree != null) {
                i++;
            }
            if (isDisabled(child)) {
                continue;
            }
            if ("TransactionController".equals(child.getTagName())) {
                if (tree != null) {
                    walk(context, tree, child.getAttribute("testname"), steps, feeds, unresolved, setBy);
                }
            } else if ("HTTPSamplerProxy".equals(child.getTagName())) {
                int order = steps.size() + 1;
                String label = transaction != null ? transaction : child.getAttribute("testname");
                Map<String, String> usages = new LinkedHashMap<>();
                steps.add(new Step(order, label, sampler(context, child, tree, usages, unresolved, label), label));
                usages.forEach((field, variable) -> {
                    Integer from = setBy.get(variable);
                    if (from != null) {
                        feeds.add(new Feed(from, order, variable, field));
                    }
                });
                if (tree != null) {
                    for (Element post : children(tree)) {
                        if ("JSONPostProcessor".equals(post.getTagName())) {
                            for (String ref : prop(post, "JSONPostProcessor.referenceNames").split(";")) {
                                if (!ref.isBlank()) {
                                    setBy.put(ref.strip(), order);
                                }
                            }
                        } else if ("RegexExtractor".equals(post.getTagName())) {
                            setBy.put(prop(post, "RegexExtractor.refname").strip(), order);
                        }
                    }
                }
            } else if (tree != null) {
                walk(context, tree, transaction, steps, feeds, unresolved, setBy);
            }
        }
    }

    private ApiCall sampler(JourneyContext context, Element sampler, Element tree, Map<String, String> usages,
                            List<Unresolved> unresolved, String label) {
        String method = prop(sampler, "HTTPSampler.method").toUpperCase(Locale.ROOT);
        String path = prop(sampler, "HTTPSampler.path");
        collect("path", path, usages);
        Map<String, String> gateway = new LinkedHashMap<>();
        List<String> otherHeaders = new ArrayList<>();
        if (tree != null) {
            for (Element manager : children(tree)) {
                if (!"HeaderManager".equals(manager.getTagName())) {
                    continue;
                }
                for (Element header : descendants(manager, "elementProp")) {
                    String key = prop(header, "Header.name");
                    String value = prop(header, "Header.value");
                    if (key.isBlank()) {
                        continue;
                    }
                    Optional<String> service = context.serviceHeaders().stream().filter(s -> s.equalsIgnoreCase(key)).findFirst();
                    if (service.isPresent()) {
                        if (USE.matcher(value).find()) {
                            unresolved.add(new Unresolved(label, "header " + key + " uses a variable"));
                        }
                        gateway.put(service.get(), value);
                    } else {
                        otherHeaders.add(key);
                        collect("header:" + key, value, usages);
                    }
                }
            }
        }
        Field requestFields = null;
        boolean rawBody = "true".equals(prop(sampler, "HTTPSampler.postBodyRaw"));
        for (Element argument : descendants(sampler, "elementProp")) {
            String value = prop(argument, "Argument.value");
            if (rawBody && !value.isBlank()) {
                Optional<JsonNode> body = JourneySupport.lenientJson(value);
                if (body.isPresent()) {
                    requestFields = JourneySupport.structure(null, body.get());
                    JourneySupport.lenientJson(value.replaceAll("\\$\\{([^}]+)}", "\"@@$1@@\"").replace("\"\"@@", "\"@@")
                            .replace("@@\"\"", "@@\"")).ifPresent(original -> JourneySupport.textValues("", original, (field, text) -> {
                        Matcher m = Pattern.compile("@@([^@]+)@@").matcher(text);
                        while (m.find()) {
                            usages.put(field, m.group(1));
                        }
                    }));
                } else {
                    unresolved.add(new Unresolved(label, "request body is not JSON; field structure not recorded"));
                }
            }
        }
        return new ApiCall(method, path, gateway, otherHeaders, List.of(), List.of(), requestFields, null);
    }

    private static void collect(String field, String text, Map<String, String> usages) {
        Matcher m = USE.matcher(text);
        while (m.find()) {
            usages.put(field.equals("path") ? "path:" + m.group(1) : field, m.group(1));
        }
    }

    private static boolean isDisabled(Element element) {
        return "false".equals(element.getAttribute("enabled"));
    }

    private static String prop(Element element, String name) {
        for (Element child : children(element)) {
            if (name.equals(child.getAttribute("name"))) {
                return child.getTextContent().strip();
            }
        }
        return "";
    }

    private static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                result.add(e);
            }
        }
        return result;
    }

    private static List<Element> descendants(Element element, String tag) {
        List<Element> result = new ArrayList<>();
        var list = element.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            result.add((Element) list.item(i));
        }
        return result;
    }

    private static Optional<Element> first(Element root, String tag) {
        var list = root.getElementsByTagName(tag);
        return list.getLength() == 0 ? Optional.empty() : Optional.of((Element) list.item(0));
    }

    private static Document parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }
}
