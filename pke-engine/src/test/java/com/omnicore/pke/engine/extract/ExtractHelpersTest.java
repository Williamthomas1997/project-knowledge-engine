package com.omnicore.pke.engine.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.extract.route.RouteResolver;
import com.omnicore.pke.engine.ingest.document.DocumentParser.ParsedSection;
import com.omnicore.pke.engine.ingest.document.MarkdownDocumentParser;
import com.omnicore.pke.engine.ingest.document.TikaDocumentParser;
import com.omnicore.pke.engine.support.DemoCorpus;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ExtractHelpersTest {

    @ParameterizedTest
    @CsvSource({
            "api/v1//accounts/, /api/v1/accounts",
            "{{baseUrl}}/api/v1/items/:id, /{baseUrl}/api/v1/items/{id}",
            "/api/{id:[0-9]+}/x?y=1, /api/{id}/x",
            "http://host:80/a/b, /a/b",
            "'', /"
    })
    void pathsAreNormalised(String input, String expected) {
        assertThat(ApiOperations.normalisePath(input)).isEqualTo(expected);
    }

    @Test
    void routeTemplatesMatchVariables() {
        assertThat(RouteResolver.matches("/api/{id}/x", "/api/42/x")).isTrue();
        assertThat(RouteResolver.matches("/api/{id}/x", "/api/42/y")).isFalse();
    }

    @Test
    void sqlInsertsHandleQuotesSchemasAndNestedExpressions() {
        String sql = """
                INSERT INTO OCIUAT.OCS_T_ERROR_CONFIG (TXN_ID, OCS_ERR_CODE, OCS_ERR_DESC)
                VALUES ((SELECT MAX(TXN_ID)+1 FROM OCS_T_ERROR_CONFIG), '003040', 'Beneficiary''s currency, not supported');
                INSERT INTO other_table (a) VALUES (1);
                INSERT INTO ocs_t_gateway_api_route ("method", uri, status) VALUES ('POST', '/api/x', NULL);""";
        var rows = SqlInserts.read(sql, "OCS_T_ERROR_CONFIG");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("ocs_err_code")).isEqualTo("003040");
        assertThat(rows.getFirst().get("OCS_ERR_DESC")).isEqualTo("Beneficiary's currency, not supported");
        assertThat(rows.getFirst().line()).isEqualTo(1);
        var routes = SqlInserts.read(sql, "ocs_t_gateway_api_route");
        assertThat(routes.getFirst().get("method")).isEqualTo("POST");
        assertThat(routes.getFirst().get("status")).isNull();
    }

    @Test
    void markdownSectionsKeepTheirHeadingPath() throws Exception {
        byte[] md = Files.readAllBytes(DemoCorpus.file("brd/international-transfer-brd.md"));
        var parsed = new MarkdownDocumentParser().parse(md, "international-transfer-brd.md");
        assertThat(parsed.status()).isEqualTo("PARSED");
        ParsedSection fr1 = parsed.sections().stream().filter(s -> "FR-1 Beneficiary currency validation".equals(s.heading()))
                .findFirst().orElseThrow();
        assertThat(fr1.headingPath()).containsExactly("International Transfer - Business Requirements (synthetic demo)",
                "3 Requirements", "FR-1 Beneficiary currency validation");
        assertThat(fr1.text()).contains("003040");
    }

    @Test
    void wordDocumentsKeepHeadingsAndTables() throws Exception {
        byte[] docx = Files.readAllBytes(DemoCorpus.file("brd/international-transfer-brd.docx"));
        var parsed = new TikaDocumentParser(new StaticListableBeanFactory().getBeanProvider(
                com.omnicore.pke.engine.ingest.document.OcrProvider.class)).parse(docx, "international-transfer-brd.docx");
        assertThat(parsed.status()).isEqualTo("PARSED");
        List<String> headings = parsed.sections().stream().map(ParsedSection::heading).toList();
        assertThat(headings).contains("FR-1 Beneficiary currency validation", "4 Error codes");
        assertThat(parsed.sections().stream().filter(s -> "4 Error codes".equals(s.heading())).findFirst().orElseThrow().text())
                .contains("003040 | Beneficiary currency not supported");
    }
}
