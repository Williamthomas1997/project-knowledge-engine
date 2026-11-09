package com.omnicore.pke.engine.graph;

import static com.omnicore.pke.engine.support.Demo.calls;
import static com.omnicore.pke.engine.support.Demo.method;
import static com.omnicore.pke.engine.support.Demo.methodRef;
import static com.omnicore.pke.engine.support.Demo.requirement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.graph.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.graph.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.graph.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipInvalidation;
import com.omnicore.pke.engine.graph.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.kernel.Violation;
import com.omnicore.pke.engine.support.Demo;
import com.omnicore.pke.engine.support.KnowledgeIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class KnowledgeWriterIT extends KnowledgeIT {

    private static final String SERVICE = "com.demo.transfer.TransferService";
    private static final String CONVERTER = "com.demo.fx.CurrencyConverter";

    @Autowired
    KnowledgeWriter writer;

    @Autowired
    KnowledgeQueryService queries;

    @Autowired
    GraphIntegrityService integrity;

    @Autowired
    MeterRegistry meters;

    private ChangeSet transferSlice() {
        return new ChangeSet(null,
                List.of(method(SERVICE, "transfer(TransferRequest)"), method(CONVERTER, "convert(Money,String)")),
                List.of(calls(SERVICE, "transfer(TransferRequest)", CONVERTER, "convert(Money,String)", "c1")),
                List.of());
    }

    @Test
    void createsEntitiesEdgesAssertionsEvidenceVersionsAndEventsTogether() {
        long seqBefore = queries.currentSeq();
        ChangeResult result = writer.apply(transferSlice(), "test");

        assertThat(result.items()).extracting(KnowledgeChanges.ItemOutcome::outcome)
                .containsExactly(Outcome.CREATED, Outcome.CREATED, Outcome.CREATED);
        assertThat(result.firstSeq()).isEqualTo(seqBefore + 1);
        assertThat(result.lastSeq()).isEqualTo(seqBefore + 3);

        String serviceId = result.items().get(0).id();
        String callId = result.items().get(2).id();
        assertThat(count("MATCH (n:Entity:JavaMethod {pke_id: $id}) RETURN count(n)", Map.of("id", serviceId))).isEqualTo(1);
        assertThat(count("""
                MATCH (:Entity {pke_id: $s})-[r:CALLS {pke_relId: $r}]->(:Entity) RETURN count(r)""",
                Map.of("s", serviceId, "r", callId))).isEqualTo(1);
        assertThat(count("""
                MATCH (a:Assertion {pke_id: $r, pke_status: 'CURRENT'})-[:SUBJECT]->(:Entity {pke_id: $s})
                MATCH (a)-[:SUPPORTED_BY]->(:Evidence {extractionMethod: 'extract.java', sourceVersion: 'c1'})
                MATCH (a)-[:HAS_VERSION]->(:AssertionVersion {version: 1})
                RETURN count(a)""", Map.of("r", callId, "s", serviceId))).isEqualTo(1);
        assertThat(count("MATCH (e:ChangeEvent)-[:ABOUT]->() WHERE e.seq > $s RETURN count(e)", Map.of("s", seqBefore)))
                .isEqualTo(3);

        KnowledgeQueryService.EntityView view = queries.entity(serviceId);
        assertThat(view.type()).isEqualTo("JavaMethod");
        assertThat(view.properties()).containsEntry("signature", "transfer(TransferRequest)").doesNotContainKey("pke_id");
        assertThat(view.evidence()).singleElement().satisfies(e -> assertThat(e).containsEntry("sourceVersion", "abc123"));
        assertThat(integrity.check().ok()).isTrue();
    }

    @Test
    void reRunningUnchangedInputProducesZeroEventsAndZeroVersions() {
        writer.apply(transferSlice(), "test");
        var before = footprint();

        ChangeResult again = writer.apply(transferSlice(), "test");

        assertThat(again.items()).extracting(KnowledgeChanges.ItemOutcome::outcome).containsOnly(Outcome.UNCHANGED);
        assertThat(again.eventCount()).isZero();
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void reRunningTheSchemaBootstrapProducesZeroEvents() {
        var before = footprint();
        var report = schemaService.bootstrap();
        assertThat(report.count(com.omnicore.pke.engine.schema.SchemaService.Outcome.REGISTERED)).isZero();
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void anUpdateCreatesANewVersionClosesThePreviousOneAndRemovesDroppedProperties() {
        writer.apply(ChangeSet.of(method(SERVICE, "transfer(TransferRequest)", "summary", "Sends money", "lineStart", 10)), "t");
        ChangeResult update = writer.apply(ChangeSet.of(method(SERVICE, "transfer(TransferRequest)",
                "summary", "Sends money abroad")), "t");

        var item = update.items().getFirst();
        assertThat(item.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(item.version()).isEqualTo(2);
        var versions = queries.entityVersions(item.id());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).validToSeq()).isEqualTo(update.firstSeq());
        assertThat(versions.get(0).properties()).containsEntry("lineStart", 10L);
        assertThat(versions.get(1).validToSeq()).isNull();
        assertThat(queries.entity(item.id()).properties()).containsEntry("summary", "Sends money abroad")
                .doesNotContainKey("lineStart");
        var event = queries.events(update.firstSeq() - 1, 1, item.id()).getFirst();
        assertThat(event.type()).isEqualTo("ENTITY_UPDATED");
        assertThat(event.details()).containsEntry("changedKeys", List.of("lineStart", "summary"));
    }

    @Test
    void newEvidenceOnAnUnchangedEntityIsRecordedWithoutANewVersion() {
        writer.apply(ChangeSet.of(method(SERVICE, "run()")), "t");
        EntityWrite sameButNewEvidence = new EntityWrite("JavaMethod", null,
                Map.of("repository", Demo.REPO, "fqcn", SERVICE, "signature", "run()"),
                EpistemicStatus.EXTRACTED_DETERMINISTIC, null, List.of(Demo.codeEvidence("TransferService.java", "def456")));

        var item = writer.apply(ChangeSet.of(sameButNewEvidence), "t").items().getFirst();

        assertThat(item.outcome()).isEqualTo(Outcome.EVIDENCE_ADDED);
        assertThat(item.version()).isEqualTo(1);
        assertThat(queries.entity(item.id()).evidence()).hasSize(2);
        assertThat(queries.entityVersions(item.id())).hasSize(1);
    }

    @Test
    void invalidationKeepsHistoryAndRemovesOnlyTheNativeEdge() {
        String callId = writer.apply(transferSlice(), "t").items().get(2).id();

        ChangeResult invalidated = writer.invalidate(
                new RelationshipInvalidation(callId, "call removed in commit c2", null, null), "t");
        assertThat(invalidated.items().getFirst().outcome()).isEqualTo(Outcome.INVALIDATED);
        assertThat(count("MATCH ()-[r:CALLS]->() RETURN count(r)")).isZero();
        var view = queries.relationship(callId);
        assertThat(view.status()).isEqualTo("INVALID");
        assertThat(view.invalidAt()).isNotNull();
        assertThat(view.evidence()).hasSize(1);
        assertThat(integrity.check().ok()).isTrue();

        // invalidating again changes nothing
        assertThat(writer.invalidate(new RelationshipInvalidation(callId, "again", null, null), "t")
                .items().getFirst().outcome()).isEqualTo(Outcome.UNCHANGED);

        // the call comes back in a later commit: same logical relationship, new version, edge restored
        var revalidated = writer.apply(new ChangeSet(null, List.of(), List.of(
                calls(SERVICE, "transfer(TransferRequest)", CONVERTER, "convert(Money,String)", "c3")), List.of()), "t");
        assertThat(revalidated.items().getFirst().outcome()).isEqualTo(Outcome.REVALIDATED);
        assertThat(count("MATCH ()-[r:CALLS {pke_relId: $id}]->() RETURN count(r)", Map.of("id", callId))).isEqualTo(1);
        assertThat(queries.relationshipVersions(callId)).extracting(KnowledgeQueryService.VersionView::status)
                .containsExactly("CURRENT", "INVALID", "CURRENT");
        assertThat(queries.relationship(callId).evidence()).hasSize(2);
        assertThat(integrity.check().ok()).isTrue();
    }

    @Test
    void unknownLabelsTypesAndPropertiesAreRejectedBeforeAnyCypherRuns() {
        double transactionsBefore = meters.get("pke.graph.write.transactions").counter().count();
        var before = footprint();

        List<ChangeSet> invalid = List.of(
                ChangeSet.of(new EntityWrite("Spaceship", null, Map.of("name", "x"), EpistemicStatus.OBSERVED, null, null)),
                ChangeSet.of(new EntityWrite("Bad Label) DETACH DELETE n //", null, Map.of(), EpistemicStatus.OBSERVED, null, null)),
                ChangeSet.of(new EntityWrite("ChangeEvent", null, Map.of(), EpistemicStatus.OBSERVED, null, null)),
                ChangeSet.of(method(SERVICE, "run()", "colour", "red")),
                ChangeSet.of(method(SERVICE, "run()", "x}) DETACH DELETE n //", "boom")),
                new ChangeSet(null, List.of(method(SERVICE, "run()"), method(CONVERTER, "convert()")),
                        List.of(new RelationshipWrite("SHARES_SECRETS_WITH", methodRef(SERVICE, "run()"),
                                methodRef(CONVERTER, "convert()"), Map.of(), EpistemicStatus.OBSERVED, null,
                                List.of(Demo.codeEvidence("x", "c1")))), List.of()),
                new ChangeSet(null, List.of(method(SERVICE, "run()"), method(CONVERTER, "convert()")),
                        List.of(new RelationshipWrite("CALLS]->() DELETE r //", methodRef(SERVICE, "run()"),
                                methodRef(CONVERTER, "convert()"), Map.of(), EpistemicStatus.OBSERVED, null, null)), List.of()));

        for (ChangeSet changes : invalid) {
            assertThatThrownBy(() -> writer.apply(changes, "t")).isInstanceOf(ValidationFailedException.class);
        }

        assertThat(meters.get("pke.graph.write.transactions").counter().count()).isEqualTo(transactionsBefore);
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void endpointTypesAndRequiredEvidenceAreEnforced() {
        writer.apply(new ChangeSet(null, List.of(requirement("FR-1", "Validate currency", "The system shall...")),
                List.of(), List.of()), "t");
        String requirementId = queries.entities("BusinessRequirement", 0, 10).items().getFirst().id();
        writer.apply(ChangeSet.of(method(SERVICE, "run()")), "t");

        // CALLS only connects methods: the requirement end is rejected (checked inside the transaction, since the
        // reference is by id), and nothing is written.
        var before = footprint();
        assertThatThrownBy(() -> writer.apply(new ChangeSet(null, List.of(), List.of(new RelationshipWrite("CALLS",
                EntityRef.id(requirementId), methodRef(SERVICE, "run()"), Map.of(), EpistemicStatus.OBSERVED, null,
                List.of(Demo.codeEvidence("x", "c1")))), List.of()), "t"))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).violations()).extracting(Violation::message)
                        .anyMatch(m -> m.contains("does not allow source type 'BusinessRequirement'")));
        assertThat(footprint()).isEqualTo(before);

        // IMPLEMENTS_REQUIREMENT requires evidence; AI-inferred evidence needs a model id
        assertThatThrownBy(() -> writer.apply(new ChangeSet(null, List.of(), List.of(new RelationshipWrite(
                "IMPLEMENTS_REQUIREMENT", methodRef(SERVICE, "run()"), EntityRef.id(requirementId), Map.of(),
                EpistemicStatus.INFERRED_AI, null, List.of())), List.of()), "t"))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("evidence item");
    }

    @Test
    void aMissingEndpointRollsBackTheWholeChangeSet() {
        var before = footprint();
        ChangeSet changes = new ChangeSet(null, List.of(method(SERVICE, "run()")),
                List.of(calls(SERVICE, "run()", CONVERTER, "doesNotExist()", "c1")), List.of());

        assertThatThrownBy(() -> writer.apply(changes, "t")).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("does not exist");
        assertThat(footprint()).isEqualTo(before);
    }
}
