package com.omnicore.pke.engine.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PkeIdsTest {

    @Test
    void uuidV5MatchesTheRfcReferenceValue() {
        // RFC 4122 / Python: uuid.uuid5(uuid.NAMESPACE_DNS, "python.org")
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertThat(PkeIds.uuidV5(dns, "python.org")).hasToString("886313e1-3b8a-5372-9b90-0c9aee199e5d");
    }

    @Test
    void entityIdsAreDeterministicAndKeySensitive() {
        String a = PkeIds.entityId("JavaMethod", List.of("repo", "com.x.Transfer", "run()"));
        assertThat(PkeIds.entityId("JavaMethod", List.of("repo", "com.x.Transfer", "run()"))).isEqualTo(a);
        assertThat(PkeIds.entityId("JavaMethod", List.of("repo", "com.x.Transfer", "run(int)"))).isNotEqualTo(a);
        assertThat(PkeIds.entityId("JavaClass", List.of("repo", "com.x.Transfer", "run()"))).isNotEqualTo(a);
        // a number and the same digits as text are different keys
        assertThat(PkeIds.entityId("T", List.of(1L))).isNotEqualTo(PkeIds.entityId("T", List.of("1")));
        assertThat(UUID.fromString(a).version()).isEqualTo(5);
    }

    @Test
    void relationshipIdsDependOnTypeEndpointsAndDiscriminator() {
        String r = PkeIds.relationshipId("CALLS", "a", "b", List.of());
        assertThat(PkeIds.relationshipId("CALLS", "a", "b", List.of())).isEqualTo(r);
        assertThat(PkeIds.relationshipId("CALLS", "b", "a", List.of())).isNotEqualTo(r);
        assertThat(PkeIds.relationshipId("CALLS", "a", "b", List.of("x"))).isNotEqualTo(r);
        assertThat(PkeIds.relationshipId("IMPORTS", "a", "b", List.of())).isNotEqualTo(r);
    }

    @Test
    void uuidV7IsVersion7AndTimeOrdered() throws InterruptedException {
        UUID first = PkeIds.uuidV7();
        Thread.sleep(2);
        UUID second = PkeIds.uuidV7();
        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(Long.compareUnsigned(first.getMostSignificantBits() >>> 16, second.getMostSignificantBits() >>> 16))
                .isNegative();
    }
}
