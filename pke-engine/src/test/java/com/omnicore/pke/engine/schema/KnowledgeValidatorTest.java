package com.omnicore.pke.engine.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnicore.pke.engine.kernel.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeValidatorTest {

    private final KnowledgeValidator validator = new KnowledgeValidator(SchemaTestSupport.bundledRegistry());

    @Test
    void normalisesDeclaredProperties() {
        List<Violation> violations = new ArrayList<>();
        var entity = validator.entity("JavaMethod", Map.of("repository", "demo", "fqcn", "a.B", "signature", "run()",
                "lineStart", 12), "/e", violations);
        assertThat(violations).isEmpty();
        assertThat(entity.properties()).containsEntry("lineStart", 12L).containsOnlyKeys("repository", "fqcn",
                "signature", "lineStart");
    }

    @Test
    void reportsEveryProblemWithItsPath() {
        List<Violation> violations = new ArrayList<>();
        validator.entity("ApiOperation", Map.of("service", "transfer", "httpMethod", "FETCH", "pathTemplate", "no-slash",
                "colour", "red", "summary", 5), "/e", violations);
        assertThat(violations).extracting(Violation::path).containsExactlyInAnyOrder(
                "/e/properties/httpMethod", "/e/properties/pathTemplate", "/e/properties/colour",
                "/e/properties/summary");
    }

    @Test
    void requiredPropertiesMustBePresent() {
        List<Violation> violations = new ArrayList<>();
        validator.entity("JavaMethod", Map.of("repository", "demo"), "/e", violations);
        assertThat(violations).extracting(Violation::path)
                .containsExactlyInAnyOrder("/e/properties/fqcn", "/e/properties/signature");
    }

    @Test
    void unknownOrInvalidTypeNamesNeverPass() {
        List<Violation> violations = new ArrayList<>();
        assertThat(validator.entity("Unknown", Map.of(), "/a", violations)).isNull();
        assertThat(validator.entity("Bad Label", Map.of(), "/b", violations)).isNull();
        assertThat(validator.entity("Evidence", Map.of(), "/c", violations)).isNull();
        assertThat(validator.relationship("NOT_REGISTERED", Map.of(), "/d", violations)).isNull();
        assertThat(validator.relationship("CALLS]->() DELETE r //", Map.of(), "/e", violations)).isNull();
        assertThat(violations).hasSize(5);
    }

    @Test
    void endpointTypesFollowTheRelationshipDefinition() {
        List<Violation> violations = new ArrayList<>();
        var calls = validator.relationship("CALLS", Map.of("callCount", 2), "/r", violations);
        validator.checkEndpoints(calls.definition(), "JavaMethod", "JavaMethod", "/r", violations);
        assertThat(violations).isEmpty();
        validator.checkEndpoints(calls.definition(), "BusinessRequirement", "JavaMethod", "/r", violations);
        assertThat(violations).extracting(Violation::path).containsExactly("/r/subject");

        var tested = validator.relationship("TESTED_BY", Map.of(), "/t", violations);
        validator.checkEndpoints(tested.definition(), "BusinessRequirement", "TestCase", "/t", violations);
        assertThat(violations).hasSize(1);
    }

    @Test
    void datetimeAndListsAreNormalised() {
        PropertyDefinition when = new PropertyDefinition("when", PropertyType.DATETIME, false, null, null, null, false);
        PropertyDefinition tags = new PropertyDefinition("tags", PropertyType.STRING_LIST, false, null, null, null, false);
        List<Violation> violations = new ArrayList<>();
        assertThat(PropertyValues.coerce(when, "2026-11-09T10:15:30+03:00", "/w", violations))
                .isEqualTo("2026-11-09T10:15:30+03:00");
        assertThat(PropertyValues.coerce(when, "yesterday", "/w", violations)).isNull();
        assertThat(PropertyValues.coerce(tags, List.of("a", "b"), "/t", violations)).isEqualTo(List.of("a", "b"));
        assertThat(PropertyValues.coerce(tags, List.of("a", 1), "/t", violations)).isNull();
        assertThat(violations).hasSize(2);
    }
}
