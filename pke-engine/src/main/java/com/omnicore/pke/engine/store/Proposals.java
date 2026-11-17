package com.omnicore.pke.engine.store;

import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeResult;
import com.omnicore.pke.engine.store.KnowledgeChanges.ChangeSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scopes and proposals (ADR-015). Knowledge lives in scope {@value #MAIN}; a proposal collects writes in scope
 * {@code proposal:<id>} that default reads never see, until the proposal is approved (promoted to main in one
 * transaction) or rejected (kept as history).
 */
public interface Proposals {

    String MAIN = "main";
    String PREFIX = "proposal:";
    Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    static String scopeOf(String proposalId) {
        return PREFIX + proposalId;
    }

    /** The proposal id of a {@code proposal:<id>} scope, or null for main / blank. Throws on any other value. */
    static String proposalIdOf(String scope) {
        if (scope == null || scope.isBlank() || MAIN.equals(scope)) {
            return null;
        }
        if (!scope.startsWith(PREFIX) || !ID.matcher(scope.substring(PREFIX.length())).matches()) {
            throw new IllegalArgumentException("scope must be 'main' or 'proposal:<id>', got '" + scope + "'");
        }
        return scope.substring(PREFIX.length());
    }

    /** @param id optional; generated (UUIDv7) when absent */
    record NewProposal(String id, String title, String description, String sourceRun) {
    }

    record ProposalView(String id, String scope, String title, String description, String sourceRun, String status,
                        String createdBy, Object createdAt, String decidedBy, Object decidedAt, String reason,
                        long itemCount, Long appliedFirstSeq, Long appliedLastSeq) {
    }

    /**
     * One proposal item compared with main.
     *
     * @param kind     ENTITY, RELATIONSHIP, ENTITY_INVALIDATION or RELATIONSHIP_INVALIDATION
     * @param targetId the id the item has (or will have) in main
     * @param change   ADDED, CHANGED, REMOVED or UNCHANGED (compared with main now)
     */
    record DiffItem(String kind, String targetId, String type, String change, String itemStatus, long itemVersion,
                    String subjectId, String objectId, Map<String, Object> properties, Map<String, Object> mainProperties) {
    }

    record DiffSection(List<DiffItem> added, List<DiffItem> changed, List<DiffItem> removed, List<DiffItem> unchanged) {
    }

    record Diff(ProposalView proposal, DiffSection entities, DiffSection relationships) {
    }

    record Decision(ProposalView proposal, ChangeResult applied) {
    }

    ProposalView create(NewProposal proposal, String actor);

    ProposalView get(String id);

    List<ProposalView> list(String status);

    /** Validates the change set like a main write and records it as items of the (open) proposal, with history. */
    ChangeResult record(String proposalId, ChangeSet changes, String actor);

    Diff diff(String proposalId);

    /** Promotes every current item to main in one transaction; events carry the proposal id and the approver. */
    Decision approve(String proposalId, String approver, String comment);

    ProposalView reject(String proposalId, String actor, String reason);
}
