package com.omnicore.pke.engine.graph;

import com.omnicore.pke.engine.kernel.PkeIds;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Processing fingerprints ({@code :Fingerprint}): "this exact input was already processed by these skill
 * versions". Checked before any model call, so re-running a pipeline on unchanged input makes no call and writes no
 * knowledge (ADR-007 §4.5). Operational records: no change events.
 */
@Service
public class FingerprintStore {

    private final GraphTransactions transactions;
    private final GraphReader reader;

    public FingerprintStore(GraphTransactions transactions, GraphReader reader) {
        this.transactions = transactions;
        this.reader = reader;
    }

    public boolean contains(String kind, String fingerprint) {
        return reader.single("MATCH (f:Fingerprint {pke_id: $id}) RETURN f.pke_id AS id", Map.of("id", id(kind, fingerprint)))
                .isPresent();
    }

    public void record(String kind, String fingerprint, String subject, String executionId) {
        Map<String, Object> props = new HashMap<>();
        props.put("kind", kind);
        props.put("fingerprint", fingerprint);
        props.put("subject", subject);
        props.put("executionId", executionId);
        props.put("at", OffsetDateTime.now(ZoneOffset.UTC));
        transactions.write("pipeline", executionId, ctx -> ctx.run(
                "MERGE (f:Fingerprint {pke_id: $id}) ON CREATE SET f += $props",
                Map.of("id", id(kind, fingerprint), "props", props)));
    }

    private static String id(String kind, String fingerprint) {
        return PkeIds.uuidV5(PkeIds.SOURCE_NAMESPACE, "fingerprint:" + kind + ":" + fingerprint).toString();
    }
}
