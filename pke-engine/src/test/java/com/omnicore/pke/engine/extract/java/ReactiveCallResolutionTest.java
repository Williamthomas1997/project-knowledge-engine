package com.omnicore.pke.engine.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.support.DemoCorpus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M6.1: call resolution in reactive code (demo/reactive-service): method references, Mono operator chains,
 * lambdas, WebClient chains, injected fields typed by an interface with one or several implementations.
 * Without Reactor on the solver classpath; nothing is guessed and every unresolved call has a reason.
 */
class ReactiveCallResolutionTest {

    private static final String PKG = "src/main/java/com/demo/reactive/";
    private static final String P = "com.demo.reactive.";
    private static JavaSourceExtractor extractor;

    @BeforeAll
    static void parse() {
        Path root = DemoCorpus.file("reactive-service");
        extractor = new JavaSourceExtractor(root, JavaSourceExtractor.javaFiles(root), new JavaSourceExtractor.Settings(
                "reactive-service", "repo:reactive-service", "c1", "reactive-service",
                Pattern.compile("^\\d{6}$"), Set.of("log")));
        assertThat(extractor.parseProblems()).isEmpty();
    }

    /** "fromSignature -> fqcn#signature [resolution]" for every CALLS edge of a file. */
    private static List<String> calls(String file) {
        return extractor.extract(PKG + file).relationships().stream().filter(r -> r.type().equals("CALLS"))
                .map(ReactiveCallResolutionTest::describe).toList();
    }

    private static String describe(RelationshipWrite r) {
        return r.subject().key().get("signature") + " -> " + r.object().key().get("fqcn") + "#"
                + r.object().key().get("signature") + " [" + r.properties().get("resolution") + "]";
    }

    @SuppressWarnings("unchecked")
    private static List<String> unresolved(String file, String signature) {
        EntityWrite method = extractor.extract(PKG + file).entities().stream()
                .filter(e -> e.type().equals("JavaMethod") && signature.equals(e.properties().get("signature")))
                .findFirst().orElseThrow();
        return (List<String>) method.properties().get("unresolvedCalls");
    }

    @Test
    void methodReferencesAndInterfaceFieldsReachTheImplementation() {
        assertThat(calls("ReactiveTransferController.java")).contains(
                // service::validate on a field typed by an interface with one implementation
                "validate(Mono) -> " + P + "TransferPort#validate(ReactiveRequest) [method-reference]",
                "validate(Mono) -> " + P + "DefaultTransferPort#validate(ReactiveRequest) [single-implementation]",
                // errors::toResponse, this::audit
                "validate(Mono) -> " + P + "ErrorMapper#toResponse(Throwable) [method-reference]",
                "execute(ReactiveRequest) -> " + P + "ReactiveTransferController#audit(ReactiveRequest) [method-reference]");
    }

    @Test
    void callsInsideLambdasOfOperatorChainsAreResolved() {
        assertThat(calls("ReactiveTransferController.java")).contains(
                // this.service.execute(r) inside flatMap, dispatched to the only implementation
                "execute(ReactiveRequest) -> " + P + "TransferPort#execute(ReactiveRequest) [resolved]",
                "execute(ReactiveRequest) -> " + P + "DefaultTransferPort#execute(ReactiveRequest) [single-implementation]",
                // explicitly typed lambda parameter, own method inside Mono.defer, static call inside Mono.fromCallable
                "execute(ReactiveRequest) -> " + P + "ReactiveRequest#normalised() [resolved]",
                "execute(ReactiveRequest) -> " + P + "ReactiveTransferController#fallback(ReactiveRequest) [resolved]",
                "execute(ReactiveRequest) -> " + P + "Receipts#number(ReactiveRequest) [resolved]");
        // a lambda inside a WebClient chain; the limit check before map
        assertThat(calls("DefaultTransferPort.java")).contains(
                "execute(ReactiveRequest) -> " + P + "ErrorMapper#coreFailure(Throwable) [resolved]",
                "validate(ReactiveRequest) -> " + P + "LimitChecker#check(BigDecimal) [resolved]");
    }

    @Test
    void unresolvedCallsCarryAReasonAndAreNeverGuessed() {
        List<String> execute = unresolved("ReactiveTransferController.java", "execute(ReactiveRequest)");
        assertThat(execute).contains(
                "multiple-implementations: " + P + "NotifierPort#notifyCustomer(String) -> 2 implementations",
                "untyped-lambda-parameter: id.trim/0");
        assertThat(execute).anyMatch(u -> u.startsWith("external-library: ") && u.endsWith(".switchIfEmpty/1"));
        assertThat(execute).allMatch(u -> u.matches("[a-z-]+: .+"));
        // with two implementations only the interface edge exists
        assertThat(calls("ReactiveTransferController.java"))
                .contains("execute(ReactiveRequest) -> " + P + "NotifierPort#notifyCustomer(String) [resolved]")
                .noneMatch(c -> c.contains("EmailNotifier") || c.contains("SmsNotifier"));
        // WebClient operators are library calls
        assertThat(unresolved("DefaultTransferPort.java", "execute(ReactiveRequest)"))
                .anyMatch(u -> u.startsWith("external-library: ") && u.endsWith(".bodyToMono/1"))
                .noneMatch(u -> u.contains("coreFailure"));
    }

    @Test
    void logsInLambdasStayWithTheirMethod() {
        assertThat(extractor.extract(PKG + "DefaultTransferPort.java").relationships())
                .filteredOn(r -> r.type().equals("LOGS"))
                .extracting(r -> r.subject().key().get("signature"))
                .containsExactlyInAnyOrder("validate(ReactiveRequest)", "execute(ReactiveRequest)");
        assertThat(extractor.extract(PKG + "ReactiveTransferController.java").relationships())
                .filteredOn(r -> r.type().equals("HANDLED_BY")).hasSize(2)
                .allSatisfy(r -> assertThat(r.properties()).isEqualTo(Map.of()));
    }
}
