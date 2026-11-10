package com.omnicore.pke.engine.support;

import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityWrite;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.engine.store.KnowledgeChanges.RelationshipWrite;
import com.omnicore.pke.engine.kernel.EpistemicStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic "international transfer" knowledge used by the integration tests. No real bank data. */
public final class Demo {

    public static final String REPO = "demo-transfer-service";

    private Demo() {
    }

    public static EntityWrite method(String fqcn, String signature, Object... extraProperties) {
        Map<String, Object> props = new LinkedHashMap<>(Map.of("repository", REPO, "fqcn", fqcn, "signature", signature));
        for (int i = 0; i < extraProperties.length; i += 2) {
            props.put((String) extraProperties[i], extraProperties[i + 1]);
        }
        return new EntityWrite("JavaMethod", null, props, EpistemicStatus.EXTRACTED_DETERMINISTIC, null,
                List.of(codeEvidence(fqcn.replace('.', '/') + ".java", "abc123")));
    }

    public static EntityRef methodRef(String fqcn, String signature) {
        return EntityRef.key("JavaMethod", Map.of("repository", REPO, "fqcn", fqcn, "signature", signature));
    }

    public static EntityWrite requirement(String reqId, String title, String text) {
        return new EntityWrite("BusinessRequirement", null,
                Map.of("documentId", "BRD-INTL-TRANSFER", "requirementId", reqId, "title", title, "text", text),
                EpistemicStatus.OBSERVED, null,
                List.of(new EvidenceInput("doc:BRD-INTL-TRANSFER", "v1", "section 3.2", "sha256:brd1", "parse.markdown",
                        null, null, null, null, text, null, null, EpistemicStatus.OBSERVED)));
    }

    public static RelationshipWrite calls(String fromFqcn, String fromSig, String toFqcn, String toSig, String commit) {
        return new RelationshipWrite("CALLS", methodRef(fromFqcn, fromSig), methodRef(toFqcn, toSig),
                Map.of("callCount", 1), EpistemicStatus.EXTRACTED_DETERMINISTIC, null,
                List.of(codeEvidence(fromFqcn.replace('.', '/') + ".java#" + fromSig, commit)));
    }

    public static EvidenceInput codeEvidence(String location, String commit) {
        return EvidenceInput.deterministic("repo:" + REPO, commit, location, "extract.java");
    }
}
