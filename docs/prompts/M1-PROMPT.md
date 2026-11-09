# M1: Foundation (spec Phase 1). Review feedback on M0 and the M1 scope

M0 has been reviewed and **accepted**. Implement M1 as described in `docs/IMPLEMENTATION_PLAN.md`
(§2–§6 and §13) and at the end of `docs/reports/M0-REPORT.md`: kernel IDs and epistemic status; the YAML schema
registry validated by a JSON Schema 2020-12 meta-schema; the `graph` writer, which saves entity + native edge +
assertion + evidence + version + `ChangeEvent` in **one transaction** with a global `seq`; REST for schema
registration, entities and relationships; idempotent re-runs.

## Decisions and extra requirements from the review

1. **R7 is decided.** OpenAI is approved for real Dukhan repos and documents. Keep the synthetic demo corpus
   for automated tests so they stay reproducible. Update the plan's risk table.
2. **Target Neo4j Community compatibility.** Use only the uniqueness constraints and indexes Community supports.
   Enforce required properties in the schema validator, not with Enterprise-only existence or type
   constraints. Record this in a new ADR.
3. **One writer.** Add an ArchUnit rule: only the `graph` package may write knowledge to Neo4j. Other packages may
   only read (for example `ops` running `dbms.components()`). Make the rule precise enough that it fails if
   someone adds a write elsewhere.
4. **Required M1 tests** (against a real Neo4j):
   - Re-running unchanged input produces **zero** `ChangeEvent`s and zero new versions.
   - A failure partway through a write (inject one after the edge, before the event) rolls back entity, edge,
     assertion, evidence, version and event **together**.
   - Concurrent writes (at least 8 threads) still get gap-free, strictly ordered `seq` values.
   - Every current assertion has exactly one native edge and vice versa (an integrity check service + test).
   - Labels, relationship types and property names that aren't in the active schema are rejected before any Cypher runs.
5. **Jackson 3.** Check whether `com.networknt:json-schema-validator` 3.0.9 works with Boot 4's Jackson 3. If it
   needs Jackson 2, decide (co-exist with an isolated Jackson 2 ObjectMapper, or use another 2020-12 validator)
   and record the result and the reason in ADR-000.
6. **Security TODO.** List in the M1 report that `/actuator` health `show-details: always` must be locked down in M6.

## Forward-compatibility hook (vector design is being finalised separately)

A pluggable vector-store design (separate vector collections linked to graph nodes) is being decided now and
will arrive as ADR-007 before M3. To avoid reworking the schema later:
- Allow an **optional** `embedding` block in entity type definitions in the meta-schema:
  `embedding: { indexFields: [<property names>], collection: <optional name> }`. Validate that every
  `indexFields` entry is a declared string property. Store it in the registry, but implement **no** embedding
  behaviour in M1.
- Keep every entity's `pke_id` stable and deterministic as planned. Vector entries will reference it.

## Done means
`mvn clean verify` is BUILD SUCCESS with the integration tests actually run; the new ADRs are written; and
`docs/reports/M1-REPORT.md` follows the same 9-section format as M0, with real test counts. Commit locally as
`M1: ...` and never push.
