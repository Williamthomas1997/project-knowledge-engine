package com.omnicore.pke.engine.extract.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.extract.ExtractedKnowledge;
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

/** Deterministic extraction from the synthetic transfer service (no database). */
class JavaSourceExtractorTest {

    private static final String PKG = "src/main/java/com/demo/transfer/";
    private static JavaSourceExtractor extractor;

    @BeforeAll
    static void parse() {
        Path root = DemoCorpus.file("transfer-service");
        extractor = new JavaSourceExtractor(root, JavaSourceExtractor.javaFiles(root), new JavaSourceExtractor.Settings(
                "transfer-service", "repo:transfer-service", "c1", "transfer-service",
                Pattern.compile("^(?!000000$)(?:[A-Z]{2,6}-)?\\d{6}$"), Set.of("log", "logger")));
        assertThat(extractor.parseProblems()).isEmpty();
    }

    private static List<RelationshipWrite> rels(String file, String type) {
        return extractor.extract(file).relationships().stream().filter(r -> r.type().equals(type)).toList();
    }

    private static String key(Object ref) {
        return String.valueOf(ref);
    }

    @Test
    void resolvedCallsBetweenRepositoryMethods() {
        List<RelationshipWrite> calls = rels(PKG + "TransferService.java", "CALLS");
        assertThat(calls).anySatisfy(c -> {
            assertThat(c.subject().key()).containsEntry("signature", "validateCurrency(String)");
            assertThat(c.object().key()).containsEntry("fqcn", "com.demo.transfer.CurrencyConverter")
                    .containsEntry("signature", "isSupported(String)");
            assertThat(c.properties()).containsEntry("resolution", "resolved").containsEntry("callCount", 1L);
        });
        assertThat(calls).anySatisfy(c -> {
            assertThat(c.subject().key()).containsEntry("signature", "validate(TransferRequest)");
            assertThat(c.object().key()).containsEntry("signature", "validateCurrency(String)");
        });
        assertThat(calls).anySatisfy(c -> assertThat(c.object().key()).containsEntry("signature", "convert(BigDecimal,String,String)"));
    }

    @Test
    void unresolvedCallsAreListedNotGuessed() {
        EntityWrite rate = extractor.extract(PKG + "FxRateClient.java").entities().stream()
                .filter(e -> e.type().equals("JavaMethod") && "rate(String,String)".equals(e.properties().get("signature")))
                .findFirst().orElseThrow();
        assertThat((List<?>) rate.properties().get("unresolvedCalls")).isNotEmpty();
        assertThat(rels(PKG + "FxRateClient.java", "CALLS")).isEmpty();
    }

    @Test
    void springEndpointsBecomeServiceApiOperationsWithFieldStructure() {
        ExtractedKnowledge k = extractor.extract(PKG + "TransferController.java");
        EntityWrite validation = k.entities().stream().filter(e -> e.type().equals("ApiOperation")
                && "/api/v2/international-transfers/validation".equals(e.properties().get("pathTemplate"))).findFirst().orElseThrow();
        assertThat(validation.properties()).containsEntry("layer", "service").containsEntry("service", "transfer-service")
                .containsEntry("httpMethod", "POST").containsEntry("otherHeaders", List.of("channel"));
        assertThat((String) validation.properties().get("requestFields")).contains("\"currency\"").contains("\"purpose\"")
                .contains("\"code\"").contains("\"number\"");
        assertThat(k.relationships()).anySatisfy(r -> {
            assertThat(r.type()).isEqualTo("HANDLED_BY");
            assertThat(r.object().key()).containsEntry("signature", "validate(TransferRequest,String)");
        });
        assertThat(k.relationships()).filteredOn(r -> r.type().equals("EXPOSES_API")).hasSize(3);
    }

    @Test
    void injectionConfigurationKafkaJpaErrorsAndLogs() {
        assertThat(rels(PKG + "TransferService.java", "INJECTS")).extracting(r -> r.object().key().get("fqcn"))
                .contains("com.demo.transfer.CurrencyConverter", "com.demo.transfer.BeneficiaryRepository",
                        "com.demo.transfer.TransferEventPublisher");
        assertThat(rels(PKG + "TransferService.java", "USES_CONFIGURATION")).extracting(r -> r.object().key().get("key"))
                .contains("transfer.max-amount");
        assertThat(rels(PKG + "TransferLimitsProperties.java", "USES_CONFIGURATION")).extracting(r -> r.object().key().get("key"))
                .containsExactly("transfer.limits");
        assertThat(rels(PKG + "TransferEventPublisher.java", "PUBLISHES_TO")).extracting(r -> r.object().key().get("name"))
                .containsExactly("transfer.executed");
        assertThat(rels(PKG + "RatesListener.java", "CONSUMES_FROM")).extracting(r -> r.object().key().get("name"))
                .containsExactly("fx.rates.updated");
        assertThat(rels(PKG + "Beneficiary.java", "MAPS_TO")).extracting(r -> r.object().key().get("name"))
                .containsExactly("DKN_BENEFICIARY");
        assertThat(rels(PKG + "TransferService.java", "READS_FROM")).extracting(r -> r.subject().key().get("signature"))
                .containsExactly("validate(TransferRequest)");
        assertThat(rels(PKG + "TransferService.java", "WRITES_TO")).extracting(r -> r.subject().key().get("signature"))
                .containsExactly("execute(ExecuteRequest)");
        assertThat(rels(PKG + "TransferService.java", "THROWS_ERROR")).extracting(
                        r -> r.subject().key().get("signature") + " " + r.object().key().get("code"))
                .containsExactlyInAnyOrder("validateCurrency(String) 003040", "validate(TransferRequest) 003041");
        List<RelationshipWrite> logs = rels(PKG + "TransferService.java", "LOGS");
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.subject().key()).containsEntry("signature", "validate(TransferRequest)");
            assertThat(l.properties()).containsEntry("order", 1L);
        });
        EntityWrite error = extractor.extract(PKG + "TransferService.java").entities().stream()
                .filter(e -> e.type().equals("LogStatement") && "ERROR".equals(e.properties().get("level"))).findFirst().orElseThrow();
        assertThat(error.properties()).containsEntry("template", "Currency {} is not supported");
    }

    @Test
    void testsAreLinkedToTheMethodsTheyCall() {
        List<RelationshipWrite> tested = rels("src/test/java/com/demo/transfer/TransferServiceTest.java", "TESTED_BY");
        assertThat(tested).anySatisfy(t -> {
            assertThat(t.subject().key()).containsEntry("signature", "validateCurrency(String)");
            assertThat(t.object().key()).isEqualTo(Map.of("repository", "transfer-service",
                    "fqcn", "com.demo.transfer.TransferServiceTest", "methodName", "rejectsUnsupportedBeneficiaryCurrency"));
        });
    }

    @Test
    void erasedSignatures() {
        assertThat(JavaSourceExtractor.erase("java.util.Map<String, List<Integer>>")).isEqualTo("Map");
        assertThat(JavaSourceExtractor.erase("String...")).isEqualTo("String[]");
        assertThat(JavaSourceExtractor.erase("com.x.Foo[]")).isEqualTo("Foo[]");
        assertThat(key(extractor.typesIn(PKG + "TransferRequest.java")))
                .contains("com.demo.transfer.TransferRequest", "com.demo.transfer.TransferRequest.Purpose");
    }
}
