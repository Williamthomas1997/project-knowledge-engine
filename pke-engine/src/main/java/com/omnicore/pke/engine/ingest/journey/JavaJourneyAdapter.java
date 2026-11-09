package com.omnicore.pke.engine.ingest.journey;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.omnicore.pke.spi.journey.JourneyContext;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import com.omnicore.pke.spi.journey.JourneyDefinition.ApiCall;
import com.omnicore.pke.spi.journey.JourneyDefinition.Feed;
import com.omnicore.pke.spi.journey.JourneyDefinition.Step;
import com.omnicore.pke.spi.journey.JourneyDefinition.Unresolved;
import com.omnicore.pke.spi.journey.JourneySourceAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Java journey classes ({@code *Journey}, {@code *E2ETest}) and Gatling simulations ({@code *Simulation}), read by
 * static interpretation (nothing is executed):
 * <ul>
 *   <li>Journey steps are the journey's public methods, in the order a test method calls them (the longest such
 *       call sequence wins; declaration order when no driver exists). Each method is traced through client calls
 *       (with argument binding) to an HTTP call: {@code get/post/put/patch/delete(path)}. Paths come from string
 *       literals and constants; gateway headers from enum constants built with {@code Map.of(...)}.</li>
 *   <li>Gatling: every {@code http("name").post(path)...} chain is a step, in source order; {@code saveAs("x")} plus a
 *       later {@code #{x}} gives a {@code FEEDS} edge.</li>
 * </ul>
 * Anything that depends on runtime values (polymorphism, computed paths) is reported as unresolved, never guessed.
 */
@Component
public class JavaJourneyAdapter implements JourneySourceAdapter {

    private static final Set<String> HTTP = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final Pattern GATLING_VAR = Pattern.compile("#\\{([^}]+)}");
    private static final int MAX_DEPTH = 6;

    private sealed interface Value permits Str, Headers, Unknown {
    }

    private record Str(String value) implements Value {
    }

    private record Headers(Map<String, String> headers) implements Value {
    }

    private record Unknown() implements Value {
    }

    private record Frame(Map<String, Value> bindings, Headers headers) {
    }

    /** A parsed repository: compilation units and indexes, reused for every file of the same root. */
    private static final class Workspace {
        final Path root;
        final Map<String, CompilationUnit> units = new LinkedHashMap<>();
        final Map<String, ClassOrInterfaceDeclaration> classesBySimpleName = new HashMap<>();
        final Map<String, Expression> constants = new HashMap<>();
        final Map<String, EnumConstantDeclaration> enumConstants = new HashMap<>();

        Workspace(Path root) {
            this.root = root;
        }
    }

    private Workspace workspace;

    @Override
    public String id() {
        return "journey.java";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(JourneyContext context, Path file) {
        if (!file.toString().endsWith(".java")) {
            return false;
        }
        String simple = file.getFileName().toString().replace(".java", "");
        return context.javaClassPatterns().stream().anyMatch(simple::matches);
    }

    @Override
    public synchronized List<JourneyDefinition> read(JourneyContext context, Path file) {
        Workspace ws = workspace(context.root());
        CompilationUnit cu = ws.units.get(file.toString().replace('\\', '/'));
        if (cu == null) {
            return List.of();
        }
        List<JourneyDefinition> journeys = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() || type.isAbstract() || !type.isTopLevelType()) {
                continue;
            }
            String name = type.getNameAsString();
            if (context.javaClassPatterns().stream().noneMatch(name::matches)) {
                continue;
            }
            String path = file.toString().replace('\\', '/');
            boolean gatling = name.endsWith("Simulation") || cu.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("io.gatling"));
            journeys.add(gatling ? simulation(ws, type, path) : journey(ws, type, path));
        }
        return journeys;
    }

    // ------------------------------------------------------------------ journey classes

    private JourneyDefinition journey(Workspace ws, ClassOrInterfaceDeclaration type, String path) {
        List<Unresolved> unresolved = new ArrayList<>();
        Map<String, MethodDeclaration> publicMethods = new LinkedHashMap<>();
        for (MethodDeclaration m : type.getMethods()) {
            if (m.isPublic() && !m.isStatic()) {
                publicMethods.putIfAbsent(m.getNameAsString(), m);
            }
        }
        Driver driver = driver(ws, type.getNameAsString(), publicMethods.keySet());
        List<String> sequence = driver.sequence().isEmpty() ? new ArrayList<>(publicMethods.keySet()) : driver.sequence();
        List<Step> steps = new ArrayList<>();
        for (String methodName : sequence) {
            MethodDeclaration method = publicMethods.get(methodName);
            if (method == null) {
                continue;
            }
            List<ApiCall> calls = new ArrayList<>();
            trace(ws, method, new Frame(Map.of(), null), 0, new HashSet<>(), calls, unresolved);
            for (int i = 0; i < calls.size(); i++) {
                String label = calls.size() == 1 ? methodName : methodName + "#" + (i + 1);
                steps.add(new Step(steps.size() + 1, label, calls.get(i), path + ":" + line(method)));
            }
        }
        return new JourneyDefinition(JourneySupport.key(type.getNameAsString(), "Journey", "E2ETest"),
                type.getNameAsString(), "java-journey", path, steps, List.of(), driver.classes(), unresolved);
    }

    private record Driver(List<String> sequence, List<String> classes) {
    }

    /** Test methods that call the journey: the longest call sequence gives the step order. */
    private Driver driver(Workspace ws, String journeyClass, Set<String> journeyMethods) {
        List<String> best = List.of();
        Set<String> classes = new TreeSet<>();
        for (CompilationUnit cu : ws.units.values()) {
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                Set<String> receivers = new HashSet<>();
                m.findAll(VariableDeclarator.class).stream().filter(v -> v.getType().asString().equals(journeyClass))
                        .forEach(v -> receivers.add(v.getNameAsString()));
                m.findAncestor(TypeDeclaration.class).ifPresent(t -> ((TypeDeclaration<?>) t).getFields().forEach(f ->
                        f.getVariables().stream().filter(v -> v.getType().asString().equals(journeyClass))
                                .forEach(v -> receivers.add(v.getNameAsString()))));
                if (receivers.isEmpty()) {
                    continue;
                }
                List<MethodCallExpr> calls = m.findAll(MethodCallExpr.class).stream()
                        .filter(c -> c.getScope().filter(s -> s instanceof NameExpr n && receivers.contains(n.getNameAsString())).isPresent())
                        .filter(c -> journeyMethods.contains(c.getNameAsString()))
                        .sorted(Comparator.comparing((MethodCallExpr c) -> c.getBegin().map(p -> p.line).orElse(0))
                                .thenComparing(c -> c.getBegin().map(p -> p.column).orElse(0)))
                        .toList();
                if (calls.isEmpty()) {
                    continue;
                }
                m.findAncestor(TypeDeclaration.class).flatMap(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName()).ifPresent(classes::add);
                List<String> sequence = calls.stream().map(MethodCallExpr::getNameAsString).toList();
                if (sequence.size() > best.size()) {
                    best = sequence;
                }
            }
        }
        return new Driver(best, List.copyOf(classes));
    }

    /** Follows calls from {@code method} until HTTP calls are found. */
    private void trace(Workspace ws, MethodDeclaration method, Frame frame, int depth, Set<MethodDeclaration> stack,
                       List<ApiCall> calls, List<Unresolved> unresolved) {
        if (depth > MAX_DEPTH || !stack.add(method)) {
            return;
        }
        Map<String, Value> locals = new HashMap<>(frame.bindings());
        for (Node node : method.getBody().map(b -> b.findAll(Node.class)).orElse(List.of())) {
            if (node instanceof VariableDeclarator v && v.getInitializer().isPresent()) {
                locals.put(v.getNameAsString(), eval(ws, v.getInitializer().get(), new Frame(locals, frame.headers())));
            }
            if (!(node instanceof MethodCallExpr call)) {
                continue;
            }
            Frame current = new Frame(locals, frame.headers());
            String name = call.getNameAsString();
            if (HTTP.contains(name) && !call.getArguments().isEmpty()
                    && eval(ws, call.getArgument(0), current) instanceof Str path && isPath(path.value())) {
                Headers headers = headersIn(ws, method, current);
                calls.add(new ApiCall(name.toUpperCase(Locale.ROOT), path.value(),
                        headers == null ? Map.of() : headers.headers(), List.of(), List.of(), List.of(), null, null));
                if (headers == null) {
                    unresolved.add(new Unresolved(location(call), "no gateway headers found for " + name.toUpperCase(Locale.ROOT)
                            + " " + path.value()));
                }
                continue;
            }
            Optional<MethodDeclaration> callee = callee(ws, call, method);
            if (callee.isEmpty()) {
                if (call.getScope().isPresent() && call.getScope().get() instanceof NameExpr scope
                        && locals.containsKey(scope.getNameAsString()) && name.endsWith("Service")) {
                    unresolved.add(new Unresolved(location(call), "polymorphic call " + call + " (depends on a runtime value)"));
                }
                continue;
            }
            Map<String, Value> bindings = new HashMap<>();
            Headers headers = frame.headers();
            List<Parameter> params = callee.get().getParameters();
            for (int i = 0; i < params.size() && i < call.getArguments().size(); i++) {
                Value value = eval(ws, call.getArgument(i), current);
                bindings.put(params.get(i).getNameAsString(), value);
                if (value instanceof Headers h && isGateway(h)) {
                    headers = h;
                }
                if (value instanceof Unknown && call.getArgument(i) instanceof MethodCallExpr arg
                        && arg.getScope().isPresent() && arg.getNameAsString().endsWith("Service")) {
                    unresolved.add(new Unresolved(location(arg), "gateway headers come from " + arg
                            + ", which depends on a runtime value"));
                }
            }
            trace(ws, callee.get(), new Frame(bindings, headers), depth + 1, stack, calls, unresolved);
        }
        stack.remove(method);
    }

    private static boolean isPath(String value) {
        return value.startsWith("/") || value.startsWith("http");
    }

    /** Headers bound in the frame, or a header-set constant referenced directly in the method. */
    private Headers headersIn(Workspace ws, MethodDeclaration method, Frame frame) {
        if (frame.headers() != null) {
            return frame.headers();
        }
        for (Value v : frame.bindings().values()) {
            if (v instanceof Headers h && isGateway(h)) {
                return h;
            }
        }
        for (FieldAccessExpr f : method.findAll(FieldAccessExpr.class)) {
            if (eval(ws, f, frame) instanceof Headers h && isGateway(h)) {
                return h;
            }
        }
        return null;
    }

    /** A map is a gateway header set only if it carries gateway header keys (a request body map does not). */
    private static boolean isGateway(Headers headers) {
        return headers.headers().keySet().stream().map(k -> k.toLowerCase(Locale.ROOT))
                .anyMatch(k -> k.equals("moduleid") || k.equals("submoduleid") || k.equals("serviceid") || k.equals("screenid"));
    }

    /** The repository method a call invokes: symbol solver first, then the receiver's declared type by name and arity. */
    private Optional<MethodDeclaration> callee(Workspace ws, MethodCallExpr call, MethodDeclaration caller) {
        try {
            Optional<Node> ast = call.resolve().toAst();
            if (ast.isPresent() && ast.get() instanceof MethodDeclaration m && m.getBody().isPresent()) {
                return Optional.of(m);
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // fall back to declared types below
        }
        String typeName = null;
        if (call.getScope().isEmpty()) {
            typeName = caller.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c.getNameAsString()).orElse(null);
        } else if (call.getScope().get() instanceof NameExpr scope) {
            typeName = declaredType(caller, scope.getNameAsString());
        }
        ClassOrInterfaceDeclaration type = typeName == null ? null : ws.classesBySimpleName.get(typeName);
        while (type != null) {
            List<MethodDeclaration> candidates = type.getMethodsByName(call.getNameAsString()).stream()
                    .filter(m -> m.getParameters().size() == call.getArguments().size() && m.getBody().isPresent()).toList();
            if (candidates.size() == 1) {
                return Optional.of(candidates.getFirst());
            }
            if (candidates.size() > 1) {
                return Optional.empty();
            }
            type = type.getExtendedTypes().stream().findFirst().map(t -> ws.classesBySimpleName.get(t.getNameAsString())).orElse(null);
        }
        return Optional.empty();
    }

    private static String declaredType(MethodDeclaration caller, String variable) {
        for (VariableDeclarationExpr local : caller.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator v : local.getVariables()) {
                if (v.getNameAsString().equals(variable)) {
                    return v.getType().asString();
                }
            }
        }
        for (Parameter p : caller.getParameters()) {
            if (p.getNameAsString().equals(variable)) {
                return p.getType().asString();
            }
        }
        return caller.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(c -> c.getFieldByName(variable)).map(f -> f.getElementType().asString()).orElse(null);
    }

    private Value eval(Workspace ws, Expression e, Frame frame) {
        if (e instanceof StringLiteralExpr s) {
            return new Str(s.asString());
        }
        if (e instanceof TextBlockLiteralExpr t) {
            return new Str(t.asString());
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS
                && eval(ws, b.getLeft(), frame) instanceof Str l && eval(ws, b.getRight(), frame) instanceof Str r) {
            return new Str(l.value() + r.value());
        }
        if (e instanceof NameExpr n) {
            Value bound = frame.bindings().get(n.getNameAsString());
            if (bound != null) {
                return bound;
            }
            String owner = e.findAncestor(TypeDeclaration.class).map(t -> ((TypeDeclaration<?>) t).getNameAsString()).orElse("");
            return constant(ws, owner + "." + n.getNameAsString(), frame);
        }
        if (e instanceof FieldAccessExpr f) {
            String scope = f.getScope().toString();
            return constant(ws, scope.substring(scope.lastIndexOf('.') + 1) + "." + f.getNameAsString(), frame);
        }
        if (e instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            if (("of".equals(name) || "ofEntries".equals(name)) && call.getScope().map(s -> s.toString().endsWith("Map")).orElse(false)) {
                return mapOf(ws, call, frame);
            }
            if (call.getArguments().isEmpty() && call.getScope().isPresent()) {
                Value scope = eval(ws, call.getScope().get(), frame);
                if (scope instanceof Headers) {
                    return scope;
                }
            }
            return new Unknown();
        }
        if (e instanceof ObjectCreationExpr o && o.getArguments().size() == 1) {
            return eval(ws, o.getArgument(0), frame);
        }
        return new Unknown();
    }

    private Value mapOf(Workspace ws, MethodCallExpr call, Frame frame) {
        Map<String, String> headers = new LinkedHashMap<>();
        List<Expression> args = call.getArguments();
        if ("of".equals(call.getNameAsString())) {
            for (int i = 0; i + 1 < args.size(); i += 2) {
                if (eval(ws, args.get(i), frame) instanceof Str k && eval(ws, args.get(i + 1), frame) instanceof Str v) {
                    headers.put(k.value(), v.value());
                } else {
                    return new Unknown();
                }
            }
        } else {
            for (Expression entry : args) {
                if (entry instanceof MethodCallExpr m && m.getArguments().size() == 2
                        && eval(ws, m.getArgument(0), frame) instanceof Str k && eval(ws, m.getArgument(1), frame) instanceof Str v) {
                    headers.put(k.value(), v.value());
                } else {
                    return new Unknown();
                }
            }
        }
        return new Headers(headers);
    }

    private Value constant(Workspace ws, String key, Frame frame) {
        Expression initializer = ws.constants.get(key);
        if (initializer != null) {
            return eval(ws, initializer, new Frame(Map.of(), null));
        }
        EnumConstantDeclaration constant = ws.enumConstants.get(key);
        if (constant != null) {
            for (Expression arg : constant.getArguments()) {
                Value v = eval(ws, arg, new Frame(Map.of(), null));
                if (v instanceof Headers) {
                    return v;
                }
            }
        }
        return new Unknown();
    }

    // ------------------------------------------------------------------ Gatling simulations

    private JourneyDefinition simulation(Workspace ws, ClassOrInterfaceDeclaration type, String path) {
        List<Unresolved> unresolved = new ArrayList<>();
        List<MethodCallExpr> requests = type.findAll(MethodCallExpr.class).stream()
                .filter(c -> "http".equals(c.getNameAsString()) && c.getScope().isEmpty() && c.getArguments().size() == 1)
                .sorted(Comparator.comparing((MethodCallExpr c) -> c.getBegin().map(p -> p.line).orElse(0))
                        .thenComparing(c -> c.getBegin().map(p -> p.column).orElse(0)))
                .toList();
        List<Step> steps = new ArrayList<>();
        List<Feed> feeds = new ArrayList<>();
        Map<String, Integer> setBy = new HashMap<>();
        Frame frame = new Frame(Map.of(), null);
        for (MethodCallExpr request : requests) {
            int order = steps.size() + 1;
            String label = eval(ws, request.getArgument(0), frame) instanceof Str s ? s.value() : request.getArgument(0).toString();
            String method = null;
            String requestPath = null;
            Map<String, String> headers = new LinkedHashMap<>();
            Map<String, String> uses = new LinkedHashMap<>();
            List<String> saves = new ArrayList<>();
            Node current = request;
            while (current.getParentNode().isPresent() && current.getParentNode().get() instanceof MethodCallExpr parent
                    && parent.getScope().isPresent() && parent.getScope().get() == current) {
                String name = parent.getNameAsString();
                if (HTTP.contains(name) && !parent.getArguments().isEmpty()) {
                    method = name.toUpperCase(Locale.ROOT);
                    Value p = eval(ws, parent.getArgument(0), frame);
                    requestPath = p instanceof Str s ? s.value() : null;
                    if (requestPath != null) {
                        variables(requestPath).forEach(v -> uses.put("path:" + v, v));
                    }
                } else if ("headers".equals(name) && parent.getArguments().size() == 1
                        && eval(ws, parent.getArgument(0), frame) instanceof Headers h) {
                    headers.putAll(h.headers());
                } else if ("header".equals(name) && parent.getArguments().size() == 2
                        && eval(ws, parent.getArgument(0), frame) instanceof Str k && eval(ws, parent.getArgument(1), frame) instanceof Str v) {
                    headers.put(k.value(), v.value());
                } else if ("body".equals(name)) {
                    parent.findAll(StringLiteralExpr.class).forEach(s ->
                            JourneySupport.lenientJson(s.asString().replaceAll("#\\{([^}]+)}", "@@$1@@")).ifPresent(json ->
                                    JourneySupport.textValues("", json, (field, text) -> {
                                        Matcher m = Pattern.compile("@@([^@]+)@@").matcher(text);
                                        while (m.find()) {
                                            uses.put(field, m.group(1));
                                        }
                                    })));
                } else if ("check".equals(name)) {
                    parent.findAll(MethodCallExpr.class).stream().filter(c -> "saveAs".equals(c.getNameAsString()))
                            .forEach(c -> c.getArguments().forEach(a -> {
                                if (eval(ws, a, frame) instanceof Str s) {
                                    saves.add(s.value());
                                }
                            }));
                }
                current = parent;
            }
            if (method == null || requestPath == null) {
                unresolved.add(new Unresolved(location(request), "request " + label + " has no resolvable method and path"));
                steps.add(new Step(order, label, null, location(request)));
            } else {
                Map<String, String> gateway = new LinkedHashMap<>();
                List<String> other = new ArrayList<>();
                headers.forEach((k, v) -> {
                    if (Set.of("moduleid", "submoduleid", "serviceid", "screenid", "unit", "channel").contains(k.toLowerCase(Locale.ROOT))) {
                        gateway.put(k, v);
                    } else {
                        other.add(k);
                    }
                });
                steps.add(new Step(order, label, new ApiCall(method, requestPath.replaceAll("#\\{([^}]+)}", "{$1}"), gateway, other,
                        List.of(), List.of(), null, null), location(request)));
            }
            uses.forEach((field, variable) -> {
                Integer from = setBy.get(variable);
                if (from != null) {
                    feeds.add(new Feed(from, order, variable, field));
                }
            });
            saves.forEach(s -> setBy.put(s, order));
        }
        return new JourneyDefinition(JourneySupport.key(type.getNameAsString(), "Simulation"), type.getNameAsString(),
                "gatling", path, steps, feeds, List.of(), unresolved);
    }

    private static List<String> variables(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = GATLING_VAR.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ------------------------------------------------------------------ workspace

    private Workspace workspace(Path root) {
        if (workspace != null && workspace.root.equals(root)) {
            return workspace;
        }
        Workspace ws = new Workspace(root);
        List<String> files = javaFiles(root);
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        Set<Path> sourceRoots = new TreeSet<>();
        for (String f : files) {
            int idx = f.indexOf("/java/");
            if (idx > 0 && f.substring(0, idx).contains("src/")) {
                sourceRoots.add(root.resolve(f.substring(0, idx + 5)));
            }
        }
        sourceRoots.stream().filter(Files::isDirectory).forEach(r -> solver.add(new JavaParserTypeSolver(r)));
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(solver)));
        for (String f : files) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(Files.readString(root.resolve(f), StandardCharsets.UTF_8));
                result.getResult().filter(r -> result.isSuccessful()).ifPresent(cu -> ws.units.put(f, cu));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (CompilationUnit cu : ws.units.values()) {
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                String simple = type.getNameAsString();
                if (type instanceof ClassOrInterfaceDeclaration c) {
                    ws.classesBySimpleName.putIfAbsent(simple, c);
                }
                for (FieldDeclaration field : type.getFields()) {
                    if (field.isStatic()) {
                        field.getVariables().forEach(v -> v.getInitializer().ifPresent(init ->
                                ws.constants.putIfAbsent(simple + "." + v.getNameAsString(), init)));
                    }
                }
                if (type instanceof EnumDeclaration e) {
                    e.getEntries().forEach(c -> ws.enumConstants.putIfAbsent(simple + "." + c.getNameAsString(), c));
                }
            }
        }
        workspace = ws;
        return ws;
    }

    private static List<String> javaFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String location(Node node) {
        return node.findCompilationUnit().flatMap(cu -> cu.getPrimaryTypeName()).orElse("?") + ":" + line(node);
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }
}
