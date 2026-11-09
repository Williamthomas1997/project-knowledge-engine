package com.omnicore.pke.engine.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CypherIdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {"BusinessRequirement", "JavaMethod", "Api", "RegulatoryControl2"})
    void acceptsPascalCaseLabels(String label) {
        assertThat(CypherIdentifiers.label(label)).isEqualTo(label);
        assertThat(CypherIdentifiers.isLabel(label)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"A", "businessRequirement", "Business Requirement", "Business-Requirement",
            "Business_Requirement", "Foo`) DETACH DELETE n //", "Foo:Bar", "Ä bc"})
    void rejectsInvalidLabels(String label) {
        assertThatThrownBy(() -> CypherIdentifiers.label(label)).isInstanceOf(InvalidIdentifierException.class);
        assertThat(CypherIdentifiers.isLabel(label)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CALLS", "IMPLEMENTS_REQUIREMENT", "REGULATORY_DEPENDENCY", "DEPENDS_ON_V2"})
    void acceptsUpperSnakeRelationshipTypes(String type) {
        assertThat(CypherIdentifiers.relationshipType(type)).isEqualTo(type);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"calls", "Calls", "_CALLS", "C", "CALLS]->() DELETE r //", "CALLS ME", "CALLS-ME"})
    void rejectsInvalidRelationshipTypes(String type) {
        assertThatThrownBy(() -> CypherIdentifiers.relationshipType(type))
                .isInstanceOf(InvalidIdentifierException.class)
                .hasMessageContaining("relationship type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "description", "verificationStatus", "line_start"})
    void acceptsCamelCasePropertyKeys(String key) {
        assertThat(CypherIdentifiers.propertyKey(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Name", "1name", "na me", "name}) DELETE n //", "a.b"})
    void rejectsInvalidPropertyKeys(String key) {
        assertThatThrownBy(() -> CypherIdentifiers.propertyKey(key)).isInstanceOf(InvalidIdentifierException.class);
    }

    @org.junit.jupiter.api.Test
    void rejectsIdentifiersLongerThan64Characters() {
        assertThatThrownBy(() -> CypherIdentifiers.label("A" + "b".repeat(64)))
                .isInstanceOf(InvalidIdentifierException.class);
        assertThat(CypherIdentifiers.label("A" + "b".repeat(63))).hasSize(64);
    }
}
