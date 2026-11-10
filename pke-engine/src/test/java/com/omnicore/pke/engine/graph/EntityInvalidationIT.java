package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.store.KnowledgeChanges;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityInvalidation;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.Outcome;
import com.omnicore.pke.engine.graph.KnowledgeQueryService.VersionView;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import com.omnicore.pke.engine.support.KnowledgeIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Entity invalidation (M3): status, business invalidAt, history, events, revalidation, idempotency. */
class EntityInvalidationIT extends KnowledgeIT {

    @Autowired
    KnowledgeWriter writer;

    @Autowired
    KnowledgeQueryService queries;

    private static final EvidenceInput EVIDENCE = EvidenceInput.deterministic("doc:policy", "v1", "s1", "manual");

    private static EntityWrite card(String text) {
        return new EntityWrite("KnowledgeCard", "card-1", Map.of("text", text, "kind", "rule", "documentKey", "doc:policy"),
                EpistemicStatus.OBSERVED, null, List.of(EVIDENCE));
    }

    private static ChangeSet invalidation(String invalidAt) {
        return new ChangeSet(null, List.of(), List.of(), List.of(), List.of(), false,
                List.of(new EntityInvalidation("card-1", "superseded", invalidAt, List.of())));
    }

    @Test
    void anInvalidatedEntityKeepsItsHistoryAndCanBecomeCurrentAgain() {
        writer.apply(ChangeSet.of(card("Transfers above QAR 1,000 need an OTP.")), "t");

        ChangeResult invalidated = writer.apply(invalidation("2026-11-01T00:00:00Z"), "t");
        assertThat(invalidated.items()).extracting(KnowledgeChanges.ItemOutcome::outcome).containsExactly(Outcome.INVALIDATED);
        var entity = queries.entity("card-1");
        assertThat(entity.status()).isEqualTo("INVALID");
        assertThat(String.valueOf(entity.invalidAt())).startsWith("2026-11-01T00:00");
        assertThat(entity.invalidationReason()).isEqualTo("superseded");
        assertThat(queries.entityVersions("card-1")).extracting(VersionView::status).containsExactly("CURRENT", "INVALID");
        assertThat(count("MATCH (e:ChangeEvent {type: 'ENTITY_INVALIDATED', subjectId: 'card-1'}) RETURN count(e)")).isEqualTo(1);

        long seq = queries.currentSeq();
        assertThat(writer.apply(invalidation(null), "t").eventCount()).as("invalidating twice writes nothing").isZero();
        assertThat(queries.currentSeq()).isEqualTo(seq);

        ChangeResult revalidated = writer.apply(ChangeSet.of(card("Transfers above QAR 1,000 need an OTP.")), "t");
        assertThat(revalidated.items()).extracting(KnowledgeChanges.ItemOutcome::outcome).containsExactly(Outcome.REVALIDATED);
        assertThat(queries.entity("card-1").status()).isEqualTo("CURRENT");
        assertThat(queries.entity("card-1").invalidAt()).isNull();
        assertThat(queries.entityVersions("card-1")).extracting(VersionView::status)
                .containsExactly("CURRENT", "INVALID", "CURRENT");
    }

    @Test
    void anEntityCannotBeWrittenAndInvalidatedInTheSameChangeSet() {
        assertThatThrownBy(() -> writer.apply(new ChangeSet(null, List.of(card("x is y")), List.of(), List.of(), List.of(),
                false, List.of(new EntityInvalidation("card-1", "r", null, List.of()))), "t"))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("Change set rejected");
    }
}
