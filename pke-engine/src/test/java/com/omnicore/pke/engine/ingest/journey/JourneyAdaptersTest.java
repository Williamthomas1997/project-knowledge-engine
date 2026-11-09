package com.omnicore.pke.engine.ingest.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.extract.ApiOperations;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.spi.journey.JourneyContext;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import com.omnicore.pke.spi.journey.JourneyDefinition.Feed;
import com.omnicore.pke.spi.journey.JourneySourceAdapter;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The four sources of the demo transfer journey describe the same journey (no database). */
class JourneyAdaptersTest {

    private static final List<String> IDENTITY = List.of("moduleId", "subModuleId", "serviceId", "screenId");
    private static final JourneyContext CONTEXT = new JourneyContext(DemoCorpus.file("qa-automation"), IDENTITY,
            new LinkedHashSet<>(List.of("moduleId", "subModuleId", "serviceId", "screenId", "unit", "channel")),
            List.of(".*Journey", ".*E2ETest", ".*Simulation"));
    private static final List<String> EXPECTED = List.of(
            "POST /api/v1/get-ft-accounts TRANSFERS|FUND_TRANSFERS|MENUS|SCREEN1",
            "POST /api/v2/international-transfers/validation TRANSFER|INTERNATIONAL|INTL_TRANSFER|INTL_01",
            "POST /api/v3/international-transfers/execute TRANSFER|INTERNATIONAL|INTL_TRANSFER|INTL_01");

    private static JourneyDefinition read(JourneySourceAdapter adapter, String file) {
        assertThat(adapter.supports(CONTEXT, Path.of(file))).isTrue();
        List<JourneyDefinition> journeys = adapter.read(CONTEXT, Path.of(file));
        assertThat(journeys).hasSize(1);
        return journeys.getFirst();
    }

    private static List<String> apis(JourneyDefinition journey) {
        return journey.steps().stream().map(s -> s.api().httpMethod() + " " + ApiOperations.normalisePath(s.api().pathTemplate())
                + " " + ApiOperations.gatewayKey(s.api().gatewayHeaders(), IDENTITY)).toList();
    }

    @Test
    void postman() {
        JourneyDefinition j = read(new PostmanJourneyAdapter(), "api-reference/postman/International Transfer.postman_collection.json");
        assertThat(j.key()).isEqualTo("international-transfer");
        assertThat(apis(j)).containsExactlyElementsOf(EXPECTED);
        assertThat(j.feeds()).containsExactly(new Feed(1, 2, "auid", "auid"), new Feed(2, 3, "uuid", "uuid"));
        var validation = j.steps().get(1).api();
        assertThat(validation.otherHeaders()).contains("Authorization").doesNotContain("moduleId");
        assertThat(ApiOperations.fieldsJson(validation.requestFields())).contains("\"purpose\"").doesNotContain("USD");
        assertThat(ApiOperations.fieldsJson(j.steps().getFirst().api().responseFields())).contains("\"balance\"").doesNotContain("a-1");
    }

    @Test
    void javaJourneyOrderedByItsDriverTest() {
        JourneyDefinition j = read(new JavaJourneyAdapter(), "src/test/java/com/demo/qa/tests/InternationalTransferJourney.java");
        assertThat(j.key()).isEqualTo("international-transfer");
        assertThat(j.steps()).extracting(JourneyDefinition.Step::label).containsExactly("ftAccounts", "validate", "execute");
        assertThat(apis(j)).containsExactlyElementsOf(EXPECTED);
        assertThat(j.drivers()).containsExactly("com.demo.qa.tests.InternationalTransferTest");
        assertThat(j.unresolved()).isEmpty();
    }

    @Test
    void gatlingSimulation() {
        JourneyDefinition j = read(new JavaJourneyAdapter(), "src/test/java/com/demo/qa/performance/InternationalTransferSimulation.java");
        assertThat(j.key()).isEqualTo("international-transfer");
        assertThat(j.sourceKind()).isEqualTo("gatling");
        assertThat(apis(j)).containsExactlyElementsOf(EXPECTED);
        assertThat(j.feeds()).containsExactly(new Feed(1, 2, "auid", "auid"), new Feed(2, 3, "uuid", "uuid"));
    }

    @Test
    void jmeterPlan() {
        JourneyDefinition j = read(new JmeterJourneyAdapter(), "jmeter/international-transfer.jmx");
        assertThat(j.key()).isEqualTo("international-transfer");
        assertThat(j.steps()).extracting(JourneyDefinition.Step::label).containsExactly("ft-accounts", "InitiateTransfer", "FinalSubmit");
        assertThat(apis(j)).containsExactlyElementsOf(EXPECTED);
        assertThat(j.feeds()).containsExactly(new Feed(1, 2, "auid", "auid"), new Feed(2, 3, "uuid", "uuid"));
    }

    @Test
    void testNgSuite() {
        var suite = TestSuiteReader.read(CONTEXT.root(), "src/test/resources/suites/transfer.xml").orElseThrow();
        assertThat(suite.name()).isEqualTo("transfer");
        assertThat(suite.classes()).containsExactly("com.demo.qa.tests.InternationalTransferTest");
    }

    @Test
    void keysAndComments() {
        assertThat(JourneySupport.key("InternationalTransferJourney", "Journey")).isEqualTo("international-transfer");
        assertThat(JourneySupport.key("International Transfer")).isEqualTo("international-transfer");
        assertThat(JourneySupport.lenientJson("{\n // c\n \"a\": {{x}}, \"b\": \"//not a comment\",}")).isPresent();
        assertThat(JourneyKnowledgeMapper.humanise("international-transfer")).isEqualTo("International Transfer");
    }
}
