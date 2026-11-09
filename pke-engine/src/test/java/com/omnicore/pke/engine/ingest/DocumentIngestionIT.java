package com.omnicore.pke.engine.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.graph.SourceQueryService;
import com.omnicore.pke.engine.graph.SourceWriter.Outcome;
import com.omnicore.pke.engine.graph.SourceWriter;
import com.omnicore.pke.engine.support.DemoCorpus;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DocumentIngestionIT extends KnowledgeIT {

    private static final String KEY = "doc:international-transfer-brd";

    @Autowired
    DocumentIngestionService documents;

    @Autowired
    SourceQueryService sources;

    @Autowired
    SourceWriter sourceWriter;

    private static byte[] demo(String path) throws Exception {
        return Files.readAllBytes(DemoCorpus.file(path));
    }

    @Test
    void reIngestingTheSameDocumentWritesNothing() throws Exception {
        var first = documents.ingest(demo("brd/international-transfer-brd.md"), "international-transfer-brd.md", null, null, "t");
        assertThat(first.source().outcome()).isEqualTo(Outcome.CREATED);
        assertThat(first.source().key()).isEqualTo(KEY);
        assertThat(first.sections()).isGreaterThan(5);
        var before = footprint();
        long sections = count("MATCH (s:Section) RETURN count(s)");

        var again = documents.ingest(demo("brd/international-transfer-brd.md"), "international-transfer-brd.md", null, null, "t");

        assertThat(again.source().outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(footprint()).isEqualTo(before);
        assertThat(count("MATCH (s:Section) RETURN count(s)")).isEqualTo(sections);
    }

    @Test
    void aChangedDocumentIsANewVersionAndTheOldOneIsKept() throws Exception {
        var v1 = documents.ingest(demo("brd/international-transfer-brd.md"), "international-transfer-brd.md", null, null, "t");
        var v2 = documents.ingest(demo("brd/changes/international-transfer-brd-v2.md"), "international-transfer-brd.md", null, null, "t");

        assertThat(v2.source().outcome()).isEqualTo(Outcome.CREATED);
        assertThat(sources.versions(KEY)).extracting(SourceQueryService.VersionView::version)
                .containsExactly(v1.source().version(), v2.source().version());
        assertThat(sources.byKey(KEY).currentVersion()).isEqualTo(v2.source().version());
        assertThat(sources.sections(KEY, null)).extracting(SourceQueryService.SectionView::heading).contains("FR-4 Purpose of transfer");
        assertThat(sources.sections(KEY, v1.source().version())).extracting(SourceQueryService.SectionView::heading)
                .doesNotContain("FR-4 Purpose of transfer").contains("FR-1 Beneficiary currency validation");
        assertThat(count("MATCH (e:ChangeEvent {type: 'DOCUMENT_VERSION_ADDED'}) RETURN count(e)")).isEqualTo(2);
    }

    @Test
    void aRemovedSourceKeepsItsHistoryAndCanBeReinstated() throws Exception {
        var v1 = documents.ingest(demo("brd/international-transfer-brd.md"), "international-transfer-brd.md", null, null, "t");
        var removed = sourceWriter.remove(KEY, "withdrawn", "t");

        assertThat(removed.outcome()).isEqualTo(Outcome.REMOVED);
        assertThat(sources.byKey(KEY).status()).isEqualTo("REMOVED");
        assertThat(sources.byKey(KEY).currentVersion()).isEqualTo(v1.source().version());
        assertThat(count("MATCH (:SourceArtifact {key: $k})-[:CURRENT_VERSION]->() RETURN count(*)", Map.of("k", KEY))).isZero();
        assertThat(sources.sections(KEY, v1.source().version())).isNotEmpty();
        assertThat(sources.searchSections("currency", 10)).isEmpty();

        var back = documents.ingest(demo("brd/international-transfer-brd.md"), "international-transfer-brd.md", null, null, "t");
        assertThat(back.source().outcome()).isEqualTo(Outcome.REINSTATED);
        assertThat(sources.byKey(KEY).status()).isEqualTo("ACTIVE");
        assertThat(sources.searchSections("currency", 10)).isNotEmpty();
    }

    @Test
    void wordDocumentsAndFulltextSearchOverRest() throws Exception {
        String boundary = "pke-boundary";
        byte[] docx = demo("brd/international-transfer-brd.docx");
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"international-transfer-brd.docx\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(docx);
        body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\ndoc:brd-docx\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/sources/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"parseStatus\":\"PARSED\"");
        assertThat(sources.sections("doc:brd-docx", null)).extracting(SourceQueryService.SectionView::heading)
                .contains("FR-1 Beneficiary currency validation", "4 Error codes");
        var hits = sources.searchSections("beneficiary currency", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().headingPath()).contains("FR-1 Beneficiary currency validation");

        HttpResponse<String> search = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/sources/sections/search?q=limit")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains("FR-2 Transfer limit");
    }
}
