package com.omnicore.pke.engine.graph;

import java.util.Map;

/**
 * A change event allocated during a write transaction and persisted as a {@code :ChangeEvent} at the end of it.
 * {@code details} holds change metadata (versions, hashes, changed keys, evidence ids), never source content.
 */
record PendingEvent(long seq, String type, SubjectKind subjectKind, String subjectId, String subjectType, Long version,
                    Map<String, Object> details) {

    enum SubjectKind { ENTITY, RELATIONSHIP, SCHEMA }
}
