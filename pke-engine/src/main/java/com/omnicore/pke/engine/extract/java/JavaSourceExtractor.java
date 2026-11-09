package com.omnicore.pke.engine.extract.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.omnicore.pke.engine.extract.ApiOperations;
import com.omnicore.pke.engine.extract.ExtractedKnowledge;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.kernel.CanonicalJson;
import com.omnicore.pke.spi.journey.JourneyDefinition.Field;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic Java analysis with JavaParser and its symbol solver (MASTER_SPEC §9.2). Construct one per
 * repository snapshot: the constructor parses every Java file to build a repository-wide index (types, constants,
 * JPA entities and repositories), then {@link #extract} produces knowledge for one file. Nothing is guessed:
 * calls the solver cannot resolve are listed on the calling method as {@code unresolvedCalls}.
 */
public final class JavaSourceExtractor {

    public static final String METHOD = "extract.java";
    public static final String VERSION = "java-1";

    /** Settings that are not code: logger field names, error-code value pattern, service name. */
    public record Settings(String repository, String artifactKey, String commit, String serviceName,
                           Pattern errorCodePattern, Set<String> loggerNames) {
    }

    private static final Set<String> CONTROLLER = Set.of("RestController", "Controller");
    private static final Set<String> COMPONENT = Set.of("Component", "Service", "Repository", "Controller",
            "RestController", "Configuration", "ControllerAdvice", "RestControllerAdvice");
    private static final Map<String, String> MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> LOG_LEVELS = Set.of("trace", "debug", "info", "warn", "error");
    private static final Set<String> JPA_REPOSITORIES = Set.of("JpaRepository", "CrudRepository",
            "PagingAndSortingRepository", "ListCrudRepository", "ReactiveCrudRepository", "R2dbcRepository",
            "MongoRepository", "Repository");
    private static final List<String> WRITE_PREFIXES = List.of("save", "delete", "remove", "insert", "update", "persist", "merge");
    private static final List<String> READ_PREFIXES = List.of("find", "get", "read", "exists", "count", "query", "search", "stream", "load");
    private static final Set<String> WRAPPERS = Set.of("ResponseEntity", "Mono", "Flux", "Optional", "CompletableFuture",
            "Callable", "DeferredResult");
    private static final List<String> TEXT_TYPES = List.of("String", "char", "Character", "UUID", "LocalDate",
            "LocalDateTime", "OffsetDateTime", "ZonedDateTime", "Instant", "Date", "LocalTime");
    private static final List<String> NUMBER_TYPES = List.of("int", "Integer", "long", "Long", "double", "Double",
            "float", "Float", "short", "Short", "byte", "Byte", "BigDecimal", "BigInteger", "Number");
    private static final List<String> LIST_TYPES = List.of("List", "Set", "Collection", "Iterable", "ArrayList", "LinkedList", "HashSet");

    private final Settings settings;
    private final Path root;
    private final Map<String, CompilationUnit> units = new TreeMap<>();
    private final Map<String, TypeDeclaration<?>> types = new HashMap<>();
    private final Map<String, String> typePaths = new HashMap<>();
    private final Map<String, String> constants = new HashMap<>();
    private final Map<String, String> entityTables = new HashMap<>();
    private final Map<String, String> repositoryTables = new HashMap<>();
    private final Map<String, String> errorConstants = new HashMap<>();
    private final List<String> parseProblems = new ArrayList<>();

    public JavaSourceExtractor(Path root, List<String> javaFiles, Settings settings) {
        this.root = root;
        this.settings = settings;
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        sourceRoots(root, javaFiles).forEach(r -> solver.add(new JavaParserTypeSolver(r)));
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        JavaParser parser = new JavaParser(configuration);
        for (String file : javaFiles) {
            try {
                ParseResult<CompilationUnit> result = parser.parse(Files.readString(root.resolve(file), StandardCharsets.UTF_8));
                if (result.getResult().isPresent() && result.isSuccessful()) {
                    units.put(file, result.getResult().get());
                } else {
                    parseProblems.add(file + ": " + result.getProblems().stream().findFirst().map(Object::toString).orElse("parse failed"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        units.forEach(this::index);
    }

    public List<String> parseProblems() {
        return List.copyOf(parseProblems);
    }

    public Set<String> files() {
        return units.keySet();
    }

    /** Fully qualified names of the types declared in a file. */
    public List<String> typesIn(String file) {
        CompilationUnit cu = units.get(file);
        return cu == null ? List.of() : cu.findAll(TypeDeclaration.class).stream()
                .map(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName().orElse(null)).filter(n -> n != null).toList();
    }

    /** Erased signature → body hash of every method of every type in a file (for rename detection). */
    public Map<String, Map<String, String>> methodHashes(String file) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        CompilationUnit cu = units.get(file);
        if (cu == null) {
            return result;
        }
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            Map<String, String> methods = new LinkedHashMap<>();
            for (CallableDeclaration<?> callable : callables(type)) {
                methods.put(signature(callable), bodyHash(callable));
            }
            type.getFullyQualifiedName().ifPresent(n -> result.put(n, methods));
        }
        return result;
    }

    // ------------------------------------------------------------------ index pass

    private void index(String file, CompilationUnit cu) {
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String fqcn = type.getFullyQualifiedName().orElse(null);
            if (fqcn == null) {
                continue;
            }
            types.put(fqcn, type);
            typePaths.put(fqcn, file);
            if (hasAnnotation(type, "Entity")) {
                String table = annotation(type, "Table").flatMap(a -> attribute(a, "name")).map(this::firstString)
                        .orElse(type.getNameAsString());
                entityTables.put(fqcn, table.toUpperCase(Locale.ROOT));
            }
            for (FieldDeclaration field : type.getFields()) {
                if (field.isStatic() && field.isFinal()) {
                    for (VariableDeclarator v : field.getVariables()) {
                        v.getInitializer().flatMap(this::literal).ifPresent(value -> {
                            constants.put(fqcn + "." + v.getNameAsString(), value);
                            constants.putIfAbsent(type.getNameAsString() + "." + v.getNameAsString(), value);
                            if (settings.errorCodePattern().matcher(value).matches()) {
                                errorConstants.put(fqcn + "." + v.getNameAsString(), value);
                                errorConstants.putIfAbsent(type.getNameAsString() + "." + v.getNameAsString(), value);
                            }
                        });
                    }
                }
            }
        }
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            if (type instanceof ClassOrInterfaceDeclaration c && c.isInterface()) {
                for (ClassOrInterfaceType parent : c.getExtendedTypes()) {
                    if (JPA_REPOSITORIES.contains(parent.getNameAsString()) && parent.getTypeArguments().isPresent()
                            && !parent.getTypeArguments().get().isEmpty()) {
                        String entity = qualify(parent.getTypeArguments().get().getFirst().orElseThrow(), cu);
                        if (entity != null) {
                            c.getFullyQualifiedName().ifPresent(n -> repositoryTables.put(n, entity));
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ extraction

    public ExtractedKnowledge extract(String file) {
        ExtractedKnowledge k = new ExtractedKnowledge(file);
        CompilationUnit cu = units.get(file);
        if (cu == null) {
            return k;
        }
        boolean test = file.contains("src/test/");
        String framework = cu.getImports().stream().map(ImportDeclaration::getNameAsString)
                .anyMatch(n -> n.startsWith("org.testng")) ? "testng" : "junit";
        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        EntityRef pkg = packageName.isEmpty() ? null : k.entity("JavaPackage",
                map("repository", settings.repository(), "name", packageName), List.of("repository", "name"), evidence(file, cu));

        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String fqcn = type.getFullyQualifiedName().orElse(null);
            if (fqcn == null) {
                continue;
            }
            List<String> externalSuperTypes = new ArrayList<>();
            List<String[]> superTypes = new ArrayList<>();
            if (type instanceof ClassOrInterfaceDeclaration c) {
                c.getExtendedTypes().forEach(t -> classify(t, cu, c.isInterface() ? "EXTENDS" : "EXTENDS", superTypes, externalSuperTypes));
                c.getImplementedTypes().forEach(t -> classify(t, cu, "IMPLEMENTS_INTERFACE", superTypes, externalSuperTypes));
            } else if (type instanceof EnumDeclaration e) {
                e.getImplementedTypes().forEach(t -> classify(t, cu, "IMPLEMENTS_INTERFACE", superTypes, externalSuperTypes));
            } else if (type instanceof RecordDeclaration r) {
                r.getImplementedTypes().forEach(t -> classify(t, cu, "IMPLEMENTS_INTERFACE", superTypes, externalSuperTypes));
            }
            EntityRef clazz = k.entity("JavaClass", map("repository", settings.repository(), "fqcn", fqcn,
                    "kind", kind(type), "path", file, "packageName", packageName.isEmpty() ? null : packageName,
                    "annotations", annotationNames(type), "superTypes", externalSuperTypes,
                    "lineStart", line(type, true), "lineEnd", line(type, false), "test", test),
                    List.of("repository", "fqcn"), evidence(file, type));
            if (pkg != null && type.isTopLevelType()) {
                k.relate("IN_PACKAGE", clazz, pkg, Map.of(), evidence(file, type), null);
            }
            for (String[] parent : superTypes) {
                k.relate(parent[0], clazz, classRef(parent[1]), Map.of(), evidence(file, type), null);
            }
            if (type.isTopLevelType()) {
                for (ImportDeclaration imp : cu.getImports()) {
                    String name = imp.getNameAsString();
                    if (!imp.isStatic() && !imp.isAsterisk() && types.containsKey(name) && !name.equals(fqcn)) {
                        k.relate("IMPORTS", clazz, classRef(name), Map.of(), evidence(file, imp), null);
                    }
                }
            }
            injections(k, file, cu, type, clazz);
            configuration(k, file, type, clazz);
            String table = entityTables.get(fqcn);
            if (table != null) {
                k.relate("MAPS_TO", clazz, k.entity("DatabaseTable", map("name", table), List.of("name"), evidence(file, type)),
                        Map.of(), evidence(file, type), null);
            }
            boolean controller = CONTROLLER.stream().anyMatch(a -> hasAnnotation(type, a));
            List<String> prefixes = controller ? annotation(type, "RequestMapping").map(this::mappingPaths)
                    .filter(l -> !l.isEmpty()).orElse(List.of("")) : List.of();
            EntityRef service = controller && settings.serviceName() != null ? k.entity("Service",
                    map("serviceName", settings.serviceName()), List.of("serviceName"), evidence(file, type)) : null;
            for (CallableDeclaration<?> callable : callables(type)) {
                method(k, file, cu, fqcn, clazz, callable, test, framework, controller ? prefixes : null, service);
            }
        }
        return k;
    }

    private void method(ExtractedKnowledge k, String file, CompilationUnit cu, String fqcn, EntityRef clazz,
                        CallableDeclaration<?> callable, boolean test, String framework, List<String> mappingPrefixes,
                        EntityRef service) {
        String signature = signature(callable);
        Map<String, Object> methodKey = map("repository", settings.repository(), "fqcn", fqcn, "signature", signature);
        List<String> unresolvedCalls = new ArrayList<>();
        Map<String, Object> props = new LinkedHashMap<>(methodKey);
        props.put("returnType", callable instanceof MethodDeclaration m ? erase(m.getType().asString()) : null);
        props.put("annotations", annotationNames(callable));
        props.put("lineStart", line(callable, true));
        props.put("lineEnd", line(callable, false));
        props.put("bodyHash", bodyHash(callable));
        props.put("test", test);
        props.put("unresolvedCalls", unresolvedCalls);
        EvidenceInput here = evidence(file, callable);

        int logOrder = 0;
        List<Object[]> pending = new ArrayList<>();
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (LOG_LEVELS.contains(name) && call.getScope().map(this::isLogger).orElse(false)) {
                logOrder++;
                pending.add(new Object[]{"LOG", logOrder, name.toUpperCase(Locale.ROOT), template(call), call});
                continue;
            }
            if (name.startsWith("send") && call.getScope().map(s -> typeName(s).contains("KafkaTemplate")).orElse(false)
                    && !call.getArguments().isEmpty()) {
                pending.add(new Object[]{"PUBLISH", stringValue(call.getArgument(0)).orElse(null), call});
                continue;
            }
            Optional<String> repositoryTable = call.getScope().map(this::scopeType).map(repositoryTables::get).map(entityTables::get);
            if (repositoryTable.isPresent()) {
                String access = WRITE_PREFIXES.stream().anyMatch(name::startsWith) ? "WRITES_TO"
                        : READ_PREFIXES.stream().anyMatch(name::startsWith) ? "READS_FROM" : null;
                if (access != null) {
                    pending.add(new Object[]{access, repositoryTable.get(), call});
                }
                continue;
            }
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                String owner = resolved.declaringType().getQualifiedName();
                if (types.containsKey(owner)) {
                    String target = resolved instanceof JavaParserMethodDeclaration jp ? signature(jp.getWrappedNode())
                            : resolved.getName() + "(" + String.join(",", paramTypes(resolved)) + ")";
                    pending.add(new Object[]{"CALL", owner, target, call});
                }
            } catch (RuntimeException | StackOverflowError e) {
                Optional<String[]> fallback = declaredTarget(call, callable, cu);
                if (fallback.isPresent()) {
                    String[] target = fallback.get();
                    if (target.length == 2) {
                        pending.add(new Object[]{"CALL", target[0], target[1], call});
                    }
                    continue;
                }
                String scope = call.getScope().map(Node::toString).orElse("this");
                String item = (scope.length() > 60 ? scope.substring(0, 57) + "..." : scope) + "." + name + "/" + call.getArguments().size();
                if (!unresolvedCalls.contains(item) && unresolvedCalls.size() < 50) {
                    unresolvedCalls.add(item);
                }
            }
        }
        EntityRef self = k.entity("JavaMethod", props, List.of("repository", "fqcn", "signature"), here);
        k.relate("DECLARES", clazz, self, Map.of(), here, null);
        unresolvedCalls.forEach(u -> k.unresolved(fqcn + "#" + signature + " -> " + u));

        EntityRef testCase = null;
        if (test && (hasAnnotation(callable, "Test") || hasAnnotation(callable, "ParameterizedTest"))) {
            testCase = k.entity("TestCase", map("repository", settings.repository(), "fqcn", fqcn,
                    "methodName", callable.getNameAsString(), "framework", framework),
                    List.of("repository", "fqcn", "methodName"), here);
        }
        for (Object[] p : pending) {
            switch ((String) p[0]) {
                case "LOG" -> {
                    MethodCallExpr call = (MethodCallExpr) p[4];
                    EntityRef log = k.entity("LogStatement", map("repository", settings.repository(), "fqcn", fqcn,
                            "signature", signature, "order", ((Integer) p[1]).longValue(), "level", p[2], "template", p[3],
                            "loggerClass", fqcn), List.of("repository", "fqcn", "signature", "order"), evidence(file, call));
                    k.relate("LOGS", self, log, Map.of("order", ((Integer) p[1]).longValue()), evidence(file, call), null);
                }
                case "PUBLISH" -> {
                    MethodCallExpr call = (MethodCallExpr) p[2];
                    if (p[1] == null) {
                        k.unresolved(fqcn + "#" + signature + " -> Kafka topic of " + call);
                    } else {
                        k.relate("PUBLISHES_TO", self, topic(k, (String) p[1], file, call), Map.of(), evidence(file, call), null);
                    }
                }
                case "READS_FROM", "WRITES_TO" -> {
                    MethodCallExpr call = (MethodCallExpr) p[2];
                    k.relate((String) p[0], self, k.entity("DatabaseTable", map("name", p[1]), List.of("name"),
                            evidence(file, call)), Map.of(), evidence(file, call), null);
                }
                case "CALL" -> {
                    MethodCallExpr call = (MethodCallExpr) p[3];
                    EntityRef target = methodRef((String) p[1], (String) p[2]);
                    k.relate("CALLS", self, target, Map.of("resolution", "resolved"), evidence(file, call), "callCount");
                    if (testCase != null && !typePaths.getOrDefault((String) p[1], "").contains("src/test/")) {
                        k.relate("TESTED_BY", target, testCase, Map.of(), evidence(file, call), null);
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        errorCodes(k, file, callable, self);
        annotation(callable, "KafkaListener").flatMap(a -> attribute(a, "topics").or(() -> attribute(a, "value")))
                .map(this::strings).orElse(List.of())
                .forEach(t -> k.relate("CONSUMES_FROM", self, topic(k, t, file, callable), Map.of(), here, null));
        for (Parameter parameter : callable.getParameters()) {
            annotation(parameter, "Value").flatMap(a -> attribute(a, "value")).map(this::firstString)
                    .ifPresent(v -> configKey(v).ifPresent(key -> k.relate("USES_CONFIGURATION", self,
                            configProperty(k, key, "value", v.contains(":"), file, parameter), Map.of(), here, null)));
        }
        if (mappingPrefixes != null && callable instanceof MethodDeclaration m) {
            endpoints(k, file, m, mappingPrefixes, self, service);
        }
    }

    private void endpoints(ExtractedKnowledge k, String file, MethodDeclaration m, List<String> prefixes, EntityRef handler,
                           EntityRef service) {
        for (AnnotationExpr a : m.getAnnotations()) {
            String name = simple(a.getNameAsString());
            List<String> methods;
            if (MAPPINGS.containsKey(name)) {
                methods = List.of(MAPPINGS.get(name));
            } else if ("RequestMapping".equals(name)) {
                methods = attribute(a, "method").map(e -> e.findAll(NameExpr.class).stream().map(NameExpr::getNameAsString)
                        .map(n -> n.toUpperCase(Locale.ROOT)).toList()).orElse(List.of());
                if (methods.isEmpty()) {
                    methods = attribute(a, "method").map(e -> e.findAll(FieldAccessExpr.class).stream()
                            .map(f -> f.getNameAsString().toUpperCase(Locale.ROOT)).toList()).orElse(List.of());
                }
                if (methods.isEmpty()) {
                    k.unresolved(m.getNameAsString() + ": @RequestMapping without an HTTP method");
                    continue;
                }
            } else {
                continue;
            }
            List<String> paths = mappingPaths(a);
            if (paths.isEmpty()) {
                paths = List.of("");
            }
            for (String prefix : prefixes) {
                for (String path : paths) {
                    for (String httpMethod : methods) {
                        String template = ApiOperations.normalisePath(prefix + "/" + path);
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("layer", "service");
                        props.put("service", settings.serviceName() == null ? settings.repository() : settings.serviceName());
                        props.put("httpMethod", httpMethod);
                        props.put("pathTemplate", template);
                        props.put("gatewayKey", "");
                        props.put("pathParameters", ApiOperations.pathParameters(template));
                        props.put("queryParameters", parameterNames(m, "RequestParam"));
                        props.put("otherHeaders", parameterNames(m, "RequestHeader"));
                        props.put("operationId", m.getNameAsString());
                        m.getParameters().stream().filter(p -> hasAnnotation(p, "RequestBody")).findFirst()
                                .map(p -> fieldsOf(p.getType(), m.findCompilationUnit().orElseThrow(), 0, new HashSet<>()))
                                .ifPresent(f -> props.put("requestFields", ApiOperations.fieldsJson(f)));
                        Field response = fieldsOf(m.getType(), m.findCompilationUnit().orElseThrow(), 0, new HashSet<>());
                        if (response != null && !response.children().isEmpty()) {
                            props.put("responseFields", ApiOperations.fieldsJson(response));
                        }
                        EvidenceInput ev = evidence(file, a);
                        EntityRef api = k.entity("ApiOperation", props, ApiOperations.NATURAL_KEY, ev);
                        k.relate("HANDLED_BY", api, handler, Map.of(), ev, null);
                        if (service != null) {
                            k.relate("EXPOSES_API", service, api, Map.of(), ev, null);
                        }
                    }
                }
            }
        }
    }

    private void injections(ExtractedKnowledge k, String file, CompilationUnit cu, TypeDeclaration<?> type, EntityRef clazz) {
        if (COMPONENT.stream().noneMatch(a -> hasAnnotation(type, a))) {
            return;
        }
        boolean lombokConstructor = hasAnnotation(type, "RequiredArgsConstructor") || hasAnnotation(type, "AllArgsConstructor");
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            String via = hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject") || hasAnnotation(field, "Resource")
                    ? "field" : lombokConstructor && (field.isFinal() || hasAnnotation(type, "AllArgsConstructor")) ? "constructor" : null;
            if (via != null) {
                inject(k, file, cu, field.getElementType(), clazz, via, field);
            }
            annotation(field, "Value").flatMap(a -> attribute(a, "value")).map(this::firstString)
                    .ifPresent(v -> configKey(v).ifPresent(key -> k.relate("USES_CONFIGURATION", clazz,
                            configProperty(k, key, "value", v.contains(":"), file, field), Map.of(), evidence(file, field), null)));
        }
        List<ConstructorDeclaration> constructors = type.getConstructors();
        for (ConstructorDeclaration constructor : constructors) {
            if (constructors.size() == 1 || hasAnnotation(constructor, "Autowired")) {
                for (Parameter p : constructor.getParameters()) {
                    if (annotation(p, "Value").isEmpty()) {
                        inject(k, file, cu, p.getType(), clazz, "constructor", p);
                    }
                }
            }
        }
        type.getMethods().stream().filter(m -> hasAnnotation(m, "Autowired")).forEach(m ->
                m.getParameters().forEach(p -> inject(k, file, cu, p.getType(), clazz, "setter", p)));
    }

    private void inject(ExtractedKnowledge k, String file, CompilationUnit cu, Type type, EntityRef clazz, String via, Node at) {
        String target = qualify(type, cu);
        if (target != null && types.containsKey(target)) {
            k.relate("INJECTS", clazz, classRef(target), Map.of("via", via), evidence(file, at), null);
        }
    }

    private void configuration(ExtractedKnowledge k, String file, TypeDeclaration<?> type, EntityRef clazz) {
        annotation(type, "ConfigurationProperties")
                .flatMap(a -> attribute(a, "prefix").or(() -> attribute(a, "value")))
                .map(this::firstString)
                .ifPresent(prefix -> k.relate("USES_CONFIGURATION", clazz,
                        configProperty(k, prefix, "prefix", false, file, type), Map.of(), evidence(file, type), null));
    }

    private void errorCodes(ExtractedKnowledge k, String file, CallableDeclaration<?> callable, EntityRef self) {
        List<Node> raising = new ArrayList<>(callable.findAll(ThrowStmt.class));
        callable.findAll(ObjectCreationExpr.class).stream()
                .filter(o -> o.getType().getNameAsString().endsWith("Exception")).forEach(raising::add);
        for (Node node : raising) {
            Set<String> codes = new HashSet<>();
            node.findAll(Expression.class).forEach(e -> {
                if (e instanceof StringLiteralExpr s && settings.errorCodePattern().matcher(s.asString()).matches()) {
                    codes.add(s.asString());
                } else if (e instanceof FieldAccessExpr || e instanceof NameExpr) {
                    String text = e.toString();
                    String code = errorConstants.get(text);
                    if (code == null) {
                        code = constantValue(e).filter(v -> settings.errorCodePattern().matcher(v).matches()).orElse(null);
                    }
                    if (code != null) {
                        codes.add(code);
                    }
                }
            });
            codes.forEach(code -> k.relate("THROWS_ERROR", self, k.entity("ErrorCode", map("code", code), List.of("code"),
                    evidence(file, node)), Map.of(), evidence(file, node), null));
        }
    }

    /**
     * Static dispatch when the symbol solver fails: the receiver's declared type (this, a local, a parameter or a
     * field) and a unique method with that name and arity in the type or its repository supertypes. Returns
     * {owner, signature}; an empty array for calls that need no edge (external types, record accessors, Lombok
     * accessors); empty when the call cannot be decided.
     */
    private Optional<String[]> declaredTarget(MethodCallExpr call, CallableDeclaration<?> caller, CompilationUnit cu) {
        String owner;
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty() || scope.get().isThisExpr()) {
            owner = caller.findAncestor(TypeDeclaration.class).flatMap(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName()).orElse(null);
        } else if (scope.get() instanceof NameExpr name) {
            Type declared = declaredType(caller, name.getNameAsString());
            if (declared == null) {
                // not a variable: a static call on a type name (repository type, import or java.lang)
                String typeName = typeNamed(name.getNameAsString(), cu);
                if (typeName == null) {
                    return Optional.empty();
                }
                if (!types.containsKey(typeName)) {
                    return Optional.of(new String[0]);
                }
                return dispatch(typeName, call, cu);
            }
            if (declared.isPrimitiveType()) {
                return Optional.of(new String[0]);
            }
            owner = qualify(declared, cu);
            if (owner == null) {
                return declared.isClassOrInterfaceType() && isJdkOrImported(declared, cu) ? Optional.of(new String[0]) : Optional.empty();
            }
        } else {
            owner = scopeType(scope.get());
            if (owner == null) {
                return Optional.empty();
            }
        }
        if (owner == null) {
            return Optional.empty();
        }
        if (!types.containsKey(owner)) {
            return Optional.of(new String[0]);
        }
        Optional<String[]> found = dispatch(owner, call, cu);
        if (found.isEmpty() && (scope.isEmpty() || scope.get().isThisExpr()) && staticallyImported(call.getNameAsString(), cu)) {
            return Optional.of(new String[0]);
        }
        return found;
    }

    /** A unique method with the call's name and arity in {@code owner} or its repository supertypes. */
    private Optional<String[]> dispatch(String owner, MethodCallExpr call, CompilationUnit cu) {
        String name = call.getNameAsString();
        int arity = call.getArguments().size();
        Set<String> visited = new HashSet<>();
        for (String current = owner; current != null && visited.add(current); ) {
            TypeDeclaration<?> type = types.get(current);
            if (type == null) {
                return Optional.of(new String[0]);
            }
            List<MethodDeclaration> candidates = type.getMethodsByName(name).stream()
                    .filter(m -> m.getParameters().size() == arity && !m.getParameters().stream().anyMatch(Parameter::isVarArgs))
                    .toList();
            if (candidates.size() == 1) {
                return Optional.of(new String[]{current, signature(candidates.getFirst())});
            }
            if (candidates.size() > 1) {
                return Optional.empty();
            }
            if (isAccessor(type, name, arity)) {
                return Optional.of(new String[0]);
            }
            String parent = null;
            if (type instanceof ClassOrInterfaceDeclaration c && !c.getExtendedTypes().isEmpty()) {
                parent = qualify(c.getExtendedTypes().getFirst().orElseThrow(), type.findCompilationUnit().orElse(cu));
            }
            current = parent;
        }
        return Optional.empty();
    }

    /** The fully qualified name a simple type name refers to in this file: imports, same package, java.lang. */
    private String typeNamed(String simple, CompilationUnit cu) {
        if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) {
            return null;
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + simple)) {
                return imp.getNameAsString();
            }
        }
        String samePackage = cu.getPackageDeclaration().map(p -> p.getNameAsString() + "." + simple).orElse(simple);
        if (types.containsKey(samePackage)) {
            return samePackage;
        }
        boolean wildcard = cu.getImports().stream().anyMatch(i -> i.isAsterisk() && !i.isStatic());
        try {
            Class.forName("java.lang." + simple, false, ClassLoader.getPlatformClassLoader());
            return "java.lang." + simple;
        } catch (ClassNotFoundException e) {
            return wildcard ? "external." + simple : null;
        }
    }

    private static boolean staticallyImported(String method, CompilationUnit cu) {
        return cu.getImports().stream().anyMatch(i -> i.isStatic()
                && (i.isAsterisk() || i.getNameAsString().endsWith("." + method)));
    }

    /** Record component accessors and Lombok-generated getters/setters exist only after compilation. */
    private static boolean isAccessor(TypeDeclaration<?> type, String name, int arity) {
        if (type instanceof RecordDeclaration r && arity == 0) {
            return r.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name));
        }
        boolean lombok = Stream.of("Data", "Getter", "Setter", "Value", "Builder").anyMatch(a -> hasAnnotation(type, a));
        if (!lombok) {
            return false;
        }
        String field = name.startsWith("get") || name.startsWith("set") ? name.substring(3) : name.startsWith("is") ? name.substring(2) : null;
        if (field == null || field.isEmpty()) {
            return false;
        }
        String property = Character.toLowerCase(field.charAt(0)) + field.substring(1);
        return type.getFieldByName(property).isPresent();
    }

    private static Type declaredType(CallableDeclaration<?> caller, String variable) {
        for (VariableDeclarator v : caller.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(variable)) {
                return v.getType();
            }
        }
        for (Parameter p : caller.getParameters()) {
            if (p.getNameAsString().equals(variable)) {
                return p.getType();
            }
        }
        Optional<TypeDeclaration> type = caller.findAncestor(TypeDeclaration.class);
        return type.flatMap(t -> ((TypeDeclaration<?>) t).getFieldByName(variable)).map(FieldDeclaration::getElementType).orElse(null);
    }

    /** True for java.lang types and explicitly imported types: they are external, not unresolved. */
    private static boolean isJdkOrImported(Type type, CompilationUnit cu) {
        String simple = type.asClassOrInterfaceType().getNameAsString();
        return cu.getImports().stream().anyMatch(i -> !i.isAsterisk() && i.getNameAsString().endsWith("." + simple))
                || List.of("String", "Object", "Integer", "Long", "Boolean", "Double", "Math", "System", "Thread",
                "StringBuilder", "Character", "Byte", "Short", "Float", "Number", "Enum", "Class").contains(simple);
    }

    // ------------------------------------------------------------------ helpers: references and evidence

    private EntityRef classRef(String fqcn) {
        return EntityRef.key("JavaClass", Map.of("repository", settings.repository(), "fqcn", fqcn));
    }

    private EntityRef methodRef(String fqcn, String signature) {
        return EntityRef.key("JavaMethod", Map.of("repository", settings.repository(), "fqcn", fqcn, "signature", signature));
    }

    private EntityRef topic(ExtractedKnowledge k, String name, String file, Node at) {
        return k.entity("KafkaTopic", map("name", name, "placeholder", name.contains("${")), List.of("name"), evidence(file, at));
    }

    private EntityRef configProperty(ExtractedKnowledge k, String key, String kind, boolean hasDefault, String file, Node at) {
        return k.entity("ConfigProperty", map("repository", settings.repository(), "key", key, "kind", kind),
                List.of("repository", "key"), evidence(file, at));
    }

    private EvidenceInput evidence(String file, Node node) {
        String location = node == null ? file : file + ":" + line(node, true);
        return EvidenceInput.scoped(settings.artifactKey(), settings.commit(), file, location, METHOD, null);
    }

    // ------------------------------------------------------------------ helpers: types and values

    private void classify(ClassOrInterfaceType t, CompilationUnit cu, String relation, List<String[]> repo, List<String> external) {
        String fqcn = qualify(t, cu);
        if (fqcn != null && types.containsKey(fqcn)) {
            repo.add(new String[]{relation, fqcn});
        } else {
            external.add(fqcn != null ? fqcn : t.getNameWithScope());
        }
    }

    /** Qualified name of a written type: symbol solver first, then exact imports and same-package types only. */
    String qualify(Type type, CompilationUnit cu) {
        Type element = type.getElementType();
        try {
            ResolvedType resolved = element.resolve();
            if (resolved.isReferenceType()) {
                return resolved.asReferenceType().getQualifiedName();
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // fall back to imports below
        }
        if (!(element instanceof ClassOrInterfaceType c)) {
            return null;
        }
        String name = c.getNameWithScope();
        if (types.containsKey(name)) {
            return name;
        }
        String simple = c.getNameAsString();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + simple)) {
                return imp.getNameAsString();
            }
        }
        String samePackage = cu.getPackageDeclaration().map(p -> p.getNameAsString() + "." + simple).orElse(simple);
        return types.containsKey(samePackage) ? samePackage : null;
    }

    private String scopeType(Expression scope) {
        try {
            ResolvedType type = scope.calculateResolvedType();
            return type.isReferenceType() ? type.asReferenceType().getQualifiedName() : null;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private String typeName(Expression scope) {
        String qualified = scopeType(scope);
        if (qualified != null) {
            return qualified;
        }
        if (scope instanceof NameExpr n) {
            return scope.findAncestor(TypeDeclaration.class).flatMap(t -> ((TypeDeclaration<?>) t).getFieldByName(n.getNameAsString()))
                    .map(f -> f.getElementType().asString()).orElse("");
        }
        return "";
    }

    private boolean isLogger(Expression scope) {
        if (scope instanceof NameExpr n && settings.loggerNames().contains(n.getNameAsString())) {
            return true;
        }
        String type = typeName(scope);
        return type.endsWith("Logger") || type.endsWith(".Log");
    }

    private Field fieldsOf(Type type, CompilationUnit cu, int depth, Set<String> seen) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType c = type.asClassOrInterfaceType();
            String simple = c.getNameAsString();
            if (WRAPPERS.contains(simple) || LIST_TYPES.contains(simple)) {
                Field inner = c.getTypeArguments().filter(a -> !a.isEmpty()).map(a -> fieldsOf(a.getFirst().orElseThrow(), cu, depth, seen)).orElse(null);
                if (LIST_TYPES.contains(simple)) {
                    return new Field(null, "array", inner == null ? List.of() : List.of(inner));
                }
                return inner;
            }
        }
        if (type.isArrayType()) {
            Field inner = fieldsOf(type.asArrayType().getComponentType(), cu, depth, seen);
            return new Field(null, "array", inner == null ? List.of() : List.of(inner));
        }
        String simple = erase(type.asString());
        if (TEXT_TYPES.contains(simple) || type.isPrimitiveType() && simple.equals("char")) {
            return new Field(null, "string", List.of());
        }
        if (NUMBER_TYPES.contains(simple)) {
            return new Field(null, "number", List.of());
        }
        if (simple.equals("boolean") || simple.equals("Boolean")) {
            return new Field(null, "boolean", List.of());
        }
        String fqcn = qualify(type, cu);
        TypeDeclaration<?> declaration = fqcn == null ? null : types.get(fqcn);
        if (declaration == null || depth >= 4 || !seen.add(fqcn)) {
            return new Field(null, "object", List.of());
        }
        if (declaration instanceof EnumDeclaration) {
            return new Field(null, "string", List.of());
        }
        CompilationUnit declaredIn = declaration.findCompilationUnit().orElse(cu);
        List<Field> children = new ArrayList<>();
        if (declaration instanceof RecordDeclaration r) {
            for (Parameter p : r.getParameters()) {
                Field f = fieldsOf(p.getType(), declaredIn, depth + 1, new HashSet<>(seen));
                children.add(new Field(jsonName(p, p.getNameAsString()), f == null ? "object" : f.type(), f == null ? List.of() : f.children()));
            }
        } else {
            for (FieldDeclaration fd : declaration.getFields()) {
                if (fd.isStatic() || hasAnnotation(fd, "JsonIgnore")) {
                    continue;
                }
                for (VariableDeclarator v : fd.getVariables()) {
                    Field f = fieldsOf(v.getType(), declaredIn, depth + 1, new HashSet<>(seen));
                    children.add(new Field(jsonName(fd, v.getNameAsString()), f == null ? "object" : f.type(), f == null ? List.of() : f.children()));
                }
            }
        }
        return new Field(null, "object", children);
    }

    private String jsonName(NodeWithAnnotations<?> node, String fallback) {
        return annotation(node, "JsonProperty").flatMap(a -> attribute(a, "value")).map(this::firstString).orElse(fallback);
    }

    private List<String> parameterNames(MethodDeclaration m, String annotation) {
        List<String> names = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            annotation(p, annotation).ifPresent(a -> names.add(attribute(a, "value").or(() -> attribute(a, "name"))
                    .map(this::firstString).filter(s -> !s.isBlank()).orElse(p.getNameAsString())));
        }
        return names;
    }

    private List<String> mappingPaths(AnnotationExpr a) {
        return attribute(a, "value").or(() -> attribute(a, "path")).map(this::strings).orElse(List.of());
    }

    private Optional<String> configKey(String placeholder) {
        if (placeholder == null || !placeholder.startsWith("${")) {
            return Optional.empty();
        }
        String inner = placeholder.substring(2, placeholder.endsWith("}") ? placeholder.length() - 1 : placeholder.length());
        int colon = inner.indexOf(':');
        return Optional.of(colon >= 0 ? inner.substring(0, colon) : inner).filter(s -> !s.isBlank());
    }

    private String template(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return "";
        }
        Expression first = call.getArgument(0);
        return literal(first).orElseGet(() -> first instanceof BinaryExpr b ? concatTemplate(b) : "<" + first + ">");
    }

    private String concatTemplate(Expression e) {
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return concatTemplate(b.getLeft()) + concatTemplate(b.getRight());
        }
        return e instanceof StringLiteralExpr s ? s.asString() : "{}";
    }

    /** All string values of an annotation member: literals, arrays, constants and concatenations. */
    private List<String> strings(Expression e) {
        if (e instanceof ArrayInitializerExpr array) {
            return array.getValues().stream().flatMap(v -> strings(v).stream()).toList();
        }
        return stringValue(e).map(List::of).orElse(List.of());
    }

    private String firstString(Expression e) {
        List<String> values = strings(e);
        return values.isEmpty() ? null : values.getFirst();
    }

    private Optional<String> stringValue(Expression e) {
        return literal(e).or(() -> constantValue(e));
    }

    /** Value of a string literal or a concatenation of literals and known constants. */
    private Optional<String> literal(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return Optional.of(s.asString());
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = stringValue(b.getLeft());
            Optional<String> right = stringValue(b.getRight());
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<String> constantValue(Expression e) {
        if (!(e instanceof NameExpr) && !(e instanceof FieldAccessExpr)) {
            return Optional.empty();
        }
        String text = e.toString();
        if (constants.containsKey(text)) {
            return Optional.of(constants.get(text));
        }
        try {
            ResolvedValueDeclaration resolved = e instanceof NameExpr n ? n.resolve() : ((FieldAccessExpr) e).resolve();
            if (resolved instanceof JavaParserFieldDeclaration field) {
                String owner = field.declaringType().getQualifiedName();
                return Optional.ofNullable(constants.get(owner + "." + field.getName()));
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // not a resolvable constant
        }
        if (e instanceof NameExpr n) {
            return e.findAncestor(TypeDeclaration.class).flatMap(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName())
                    .map(owner -> constants.get(owner + "." + n.getNameAsString()));
        }
        return Optional.empty();
    }

    private static List<String> paramTypes(ResolvedMethodDeclaration m) {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < m.getNumberOfParams(); i++) {
            params.add(erase(m.getParam(i).describeType()));
        }
        return params;
    }

    /** {@code name(Type,Type)} with generics and packages removed; varargs as arrays. */
    static String signature(CallableDeclaration<?> callable) {
        List<String> params = new ArrayList<>();
        for (Parameter p : callable.getParameters()) {
            params.add(erase(p.getType().asString()) + (p.isVarArgs() ? "[]" : ""));
        }
        return callable.getNameAsString() + "(" + String.join(",", params) + ")";
    }

    static String erase(String type) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (char c : type.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0 && !Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        String erased = out.toString().replace("...", "[]");
        int dims = erased.indexOf('[');
        String base = dims >= 0 ? erased.substring(0, dims) : erased;
        String suffix = dims >= 0 ? erased.substring(dims) : "";
        return base.substring(base.lastIndexOf('.') + 1) + suffix;
    }

    private static String bodyHash(CallableDeclaration<?> callable) {
        Node body = callable instanceof MethodDeclaration m ? m.getBody().orElse(null)
                : ((ConstructorDeclaration) callable).getBody();
        if (body == null) {
            return null;
        }
        Node copy = body.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        return CanonicalJson.sha256(copy.toString().replaceAll("\\s+", " ").strip()).substring(0, 16);
    }

    private static List<CallableDeclaration<?>> callables(TypeDeclaration<?> type) {
        List<CallableDeclaration<?>> callables = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof CallableDeclaration<?> c) {
                callables.add(c);
            }
        }
        return callables;
    }

    private static String kind(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        return "annotation";
    }

    private static Long line(Node node, boolean begin) {
        return node.getRange().map(r -> (long) (begin ? r.begin.line : r.end.line)).orElse(null);
    }

    private static List<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(a -> simple(a.getNameAsString())).distinct().sorted().toList();
    }

    private static boolean hasAnnotation(Object node, String name) {
        return node instanceof NodeWithAnnotations<?> n && annotation(n, name).isPresent();
    }

    private static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String name) {
        NodeList<AnnotationExpr> annotations = node.getAnnotations();
        return annotations.stream().filter(a -> simple(a.getNameAsString()).equals(name)).findFirst();
    }

    private static Optional<Expression> attribute(AnnotationExpr a, String name) {
        if (a instanceof SingleMemberAnnotationExpr s) {
            return "value".equals(name) ? Optional.of(s.getMemberValue()) : Optional.empty();
        }
        if (a instanceof NormalAnnotationExpr n) {
            return n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).map(p -> p.getValue()).findFirst();
        }
        return Optional.empty();
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** {@code .../src/<set>/java} roots that contain the given files, or the repository root. */
    private static List<Path> sourceRoots(Path root, List<String> javaFiles) {
        Set<Path> roots = new java.util.TreeSet<>();
        for (String file : javaFiles) {
            String normalised = file.replace('\\', '/');
            int idx = normalised.indexOf("/java/");
            int srcIdx = normalised.lastIndexOf("src/", idx);
            if (idx > 0 && srcIdx >= 0) {
                roots.add(root.resolve(normalised.substring(0, idx + "/java".length())));
            }
        }
        if (roots.isEmpty()) {
            roots.add(root);
        }
        return roots.stream().filter(Files::isDirectory).toList();
    }

    /** Java files below a directory, relative to it, sorted. */
    public static List<String> javaFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
