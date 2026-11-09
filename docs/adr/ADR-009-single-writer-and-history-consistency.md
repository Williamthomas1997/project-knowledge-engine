# ADR-009: Single knowledge writer, write protocol and history consistency

Status: **accepted** (M1, 2026-11-09). Implements plan §2 (ADR-001) and §5 (ADR-003).

## Context

MASTER_SPEC §11.4 says a graph mutation must never succeed while its history record is lost. The plan decided to
write graph state, versions and change events in one Neo4j transaction, with no outbox or event store. M1 had to
make that true in code, and make it hard to break later.

## Decision

### One writer

- `graph.GraphTransactions` is the only class that opens write transactions (`Session.executeWrite`).
  `graph.GraphSchemaInitializer` may run auto-commit schema commands. Every knowledge mutation goes through
  `graph.KnowledgeWriter` (entities, relationships, invalidations) or `graph.Neo4jSchemaDefinitionStore`
  (definitions). Both use `GraphTransactions`.
- Other packages read through `graph.GraphReader`. It runs `executeRead` in a READ-mode session, so **the database
  rejects writes** sent through it (`GraphIntegrityIT.graphReaderCannotWrite`).
- Enforced by ArchUnit (`ArchitectureTest`):
  - `onlyGraphUsesTheDriver`: no class outside `graph` may depend on `org.neo4j.driver..` or
    `org.springframework.data.neo4j..`.
  - `onlyGraphTransactionsOpensWriteTransactions`: apart from the two classes above, no class may call
    `executeWrite`, `executeWriteWithoutResult`, `run`, `beginTransaction` or `executableQuery` on a driver
    `Session`/`QueryRunner`/`Driver` (or the async/reactive sessions).
  - `ArchitectureRuleFixturesTest` proves both rules fail on deliberate violations (`RogueWriter` outside `graph`,
    and `RogueGraphWriter` inside it) and pass on the real classes.
- `schema` must not depend on `graph`. Schema persistence goes through the `schema.SchemaDefinitionStore` port,
  which `graph` implements.

### Write protocol (one transaction per change set)

1. **Validate in memory before any Cypher runs**: types, labels, relationship types and property keys must exist in
   the active schema (`SchemaRegistry` cache). Values are coerced, required properties checked, ids computed and
   evidence validated. All violations are collected, and the change set is rejected before a transaction opens
   (proven with the `pke.graph.write.transactions` counter).
2. **Lock the sequence**: the first statement is
   `MERGE (s:Sequence {name:'knowledge'}) ON CREATE SET s.value = 0 SET s.lockedBy = $token`. This takes the
   node's write lock, so knowledge writes are serialised (plan risk R4: acceptable at one-team scale).
3. **Apply changes**: entities, then relationships, then invalidations. Each real change allocates the next `seq`
   in memory. An unchanged item allocates nothing.
4. **Write events last**: one `:ChangeEvent` per allocated `seq` (`ABOUT` the entity, assertion or definition),
   then `SET s.value = <last seq>`.
5. Commit. Any exception (validation inside the transaction, integrity guard, driver error, injected fault) rolls
   back everything, including the sequence advance, so `seq` stays gap-free. Driver-managed retries replay the
   whole unit with a fresh context.

Fault-injection points (`WriteStage`: AFTER_ENTITY, AFTER_ASSERTION, AFTER_EDGE, AFTER_EVIDENCE, AFTER_VERSION,
BEFORE_EVENTS) are a `WriteFaultInjector` hook. Production registers none. `WriteRollbackIT` fails at every stage
and shows that nothing remains.

### What one change looks like

| Item | Nodes and edges written |
|---|---|
| Entity created | `(:Entity:<Type> {pke_*, domain props})`, `-[:HAS_VERSION]->(:EntityVersion {version, propsJson, validFromSeq})`, `-[:SUPPORTED_BY]->(:Evidence)`, `ENTITY_CREATED` event |
| Entity changed | node updated (dropped properties removed), previous version gets `validToSeq`, new version, new evidence, `ENTITY_UPDATED` (details: `changedKeys`, `fromVersion`, hashes; no values) |
| New evidence only | `Evidence` + `EVIDENCE_ADDED`. No new version |
| Relationship created | `(:Assertion {pke_status:'CURRENT', pke_validAt})-[:SUBJECT/OBJECT]->`, native `(s)-[:<TYPE> {pke_relId}]->(o)`, version, evidence, `RELATIONSHIP_CREATED` |
| Relationship invalidated | assertion `INVALID` with `pke_invalidAt` and reason, **native edge deleted**, new version, `RELATIONSHIP_INVALIDATED`. Evidence and history are kept |
| Relationship asserted again | assertion `CURRENT` again, edge recreated, `RELATIONSHIP_REVALIDATED` |

### Idempotency

Change detection compares a SHA-256 of the canonical JSON of the normalised domain properties, plus the
epistemic status and any requested `validAt`. Evidence ids are deterministic
(UUIDv5(subject, source, version, location, method, hash)). Re-applying the same input therefore produces no
events, no versions and no `seq` (`KnowledgeWriterIT.reRunningUnchangedInputProducesZeroEventsAndZeroVersions`).

### Integrity

`GraphIntegrityService` (exposed as `GET /api/integrity`) checks that every CURRENT assertion has exactly one
native edge of its type between its endpoints, and that every `pke_relId` edge belongs to a CURRENT assertion with
the same type and endpoints. The writer also refuses to continue if an update or invalidation finds a number of
edges other than one.

## Consequences

- Write throughput is limited by the serialised sequence. `ConcurrentWritesIT` (8 threads, 80 transactions,
  160 events) shows the trade-off works: gap-free, strictly ordered `seq`, and time-ordered events.
- History is exactly as durable as Neo4j. The JSONL export of events (plan §5) is still to do (M5).
- Change event details deliberately hold no property values. Values live in versions, so sensitive content is
  never copied into the event log.
