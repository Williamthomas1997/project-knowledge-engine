package com.omnicore.pke.engine.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.omnicore.pke.engine.reconcile.RelationshipPolicies.Decision;
import com.omnicore.pke.engine.reconcile.RelationshipPolicies.Origin;
import com.omnicore.pke.engine.schema.RelationshipTypeDefinition;
import com.omnicore.pke.engine.schema.SchemaRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RelationshipPoliciesTest {

    private final SchemaRegistry schema = mock(SchemaRegistry.class);
    private final RelationshipPolicies policies =
            new RelationshipPolicies(schema, "classpath:definitions/policies/relationships.yaml");

    @Test
    void aiLinksAreAcceptedAsInferredOnlyWithEvidenceAndEnoughConfidence() {
        assertThat(policies.decide("IMPLEMENTED_BY", Origin.AI, 1, 0.9).decision()).isEqualTo(Decision.ACCEPT_AS_INFERRED);
        assertThat(policies.decide("IMPLEMENTED_BY", Origin.AI, 0, 0.9).decision()).isEqualTo(Decision.REJECT);
        assertThat(policies.decide("IMPLEMENTED_BY", Origin.AI, 1, 0.3).decision()).isEqualTo(Decision.REJECT);
        assertThat(policies.decide("IMPLEMENTED_BY", Origin.AI, 1, null).decision()).isEqualTo(Decision.REJECT);
    }

    @Test
    void codeExtractedEdgesAreAutoAccepted() {
        assertThat(policies.decide("CALLS", Origin.DETERMINISTIC, 1, null).decision()).isEqualTo(Decision.AUTO_ACCEPT);
    }

    @Test
    void withoutARuleTheSchemaPolicyDecides() {
        RelationshipTypeDefinition reviewed = mock(RelationshipTypeDefinition.class);
        when(reviewed.discovery()).thenReturn(List.of("ai"));
        when(reviewed.approval()).thenReturn("review");
        when(schema.relationshipType("SHARES_LOGIC_WITH")).thenReturn(Optional.of(reviewed));
        assertThat(policies.decide("SHARES_LOGIC_WITH", Origin.AI, 1, 0.9).decision()).isEqualTo(Decision.QUESTION);

        RelationshipTypeDefinition deterministicOnly = mock(RelationshipTypeDefinition.class);
        when(deterministicOnly.discovery()).thenReturn(List.of("deterministic"));
        when(schema.relationshipType("TESTED_BY")).thenReturn(Optional.of(deterministicOnly));
        assertThat(policies.decide("TESTED_BY", Origin.AI, 1, 0.9).decision()).isEqualTo(Decision.REJECT);
        assertThat(policies.decide("NOT_A_TYPE", Origin.AI, 1, 0.9).decision()).isEqualTo(Decision.REJECT);
    }
}
