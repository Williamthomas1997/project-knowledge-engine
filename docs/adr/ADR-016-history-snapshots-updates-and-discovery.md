# ADR-016: History queries, snapshots, update pipelines and relationship discovery

Status: **accepted** (M5, 2026-11-17). Implements plan §5 (snapshots, export), §6 (migration checkpoints) and ADR-007 §6
(shared logic, discovered relationship types, log trail).

## Decisions

### History and snapshots
- **Reconstruction from versions** (`graph.HistoryQueries`): state as of `seq` = `CURRENT` versions with
  `validFromSeq <= seq < validToSeq`; a diff classifies items changed in `(A, B]` as `ADDED`, `CHANGED` or
  `INVALIDATED`. Documented with its limits in `docs/versioning.md`. New indexes: `EntityVersion.validFromSeq`,
  `AssertionVersion.validFromSeq`, `ChangeEvent.operationId`.
- **A snapshot is a pointer, not a copy**: `(:Snapshot {seq, label, contentsJson})` with the active schema, skill,
  pipeline and query-template versions, source versions (repository commits and document hashes), model and vector
  configuration, the embedding generation of every collection and the release (`pke.release`, else `git describe`,
  else the build version). It allocates no seq and has no change event (it changes no knowledge); it is written in a
  knowledge write transaction only to read a stable seq.
- **Git → knowledge needs no new link**: runs already record the commit they processed (`IngestionRun.toVersion`) and
  their events carry the run id as `operationId`; evidence carries the commit as `sourceVersion`. The commit endpoint
  and the `commit`/`source` event filters join these.
- **Export**: events after the last export, as one canonical-JSON-lines file in the document store, with a gap check,
  recorded as `:HistoryExport`. Nightly by `@Scheduled`.

### Recovery and migrations
- **Restore = inverse writes through the single writer** (`history.RecoveryService`): the diff from the checkpoint to
  now is undone in one transaction (invalidate added items, write back old properties, re-assert invalidated items).
  Chosen over deleting versions: history stays append-only, and a restore can itself be inspected and undone.
- **Migrations** (`history.MigrationService`, `POST /api/schema/migrations`) are data migrations with a checkpoint:
  snapshot, batched entity rewrites, restore on the first failing batch. Operations: `setDefault` (the data half of
  `addRequired(default)`) and `renameProperty`. Both properties must be declared by the active schema version, and
  natural-key properties cannot be renamed (identity).

### Update pipelines (definitions only, plus small steps)
- `code-on-commit`: `repository-ingest` (now with an explicit `fromCommit`/`toCommit` range), `discover-shared-logic`, `populate-derived`,
  `requirements-link`, `snapshot` (only when `tag` is `uat`/`pilot` or `snapshot: true`). Entry points:
  `POST /api/pipelines/code-on-commit` and `bin/pke-code-on-commit.sh` (curl; exit code 0 only when `COMPLETED`).
- `brd-update`: `brd-ingest` plus a `brd-section-diff` step; the existing section fingerprints already keep unchanged
  sections away from the model.
- `reindex`: optional new generation (evaluated before activation), then reconciliation of every collection.

### Relationship discovery
- **`SHARES_LOGIC_WITH`** (technical, ApiOperation ↔ ApiOperation, one edge per unordered pair, subject id < object
  id): handlers whose call trees (1..4 `CALLS` hops) reach the same *non-trivial* method, i.e. one with an outgoing
  call, log, error, read, write or publish whose class does not match `pke.discovery.shared-logic.exclude` (default:
  util, config, constant, logging, logger, dto, mapper, properties, generated). APIs with the same handler (a gateway
  API and its service API) are not paired. Shared methods are a property and the evidence excerpt. The derivation is
  one evidence scope, so pairs that stop sharing are invalidated and an unchanged graph writes nothing.
- **Discovered types are Level-1 metadata**: the relationship-type meta-schema gains `extraction {query, proposedBy,
  rationale}`. The `discover-relationships` skill proposes types; a proposal whose population query passes the Cypher
  guard is registered through the schema registry (validation, compatibility, `SCHEMA_REGISTERED`) and populated by
  `populate-derived` (also run by `discover-relationships` on every later execution). Population runs through
  `GuardedCypher` with its own row limit (`pke.discovery.population-max-rows`, 5,000).
- **Everything else becomes an evolution request** (`:EvolutionRequest {kind, subject, reason, payloadJson, status}`,
  id derived from kind + subject, `GET /api/evolution/requests`), consumed in M7: proposals without a query, rejected
  queries, invalid definitions, and **business** proposals whose query traverses only non-business relationships
  (MASTER_SPEC §7).

### Log trail
- `log-trail-of-api` 1.1.0 orders by hops from the handler, then signature, then source order (it now sorts after the
  projection, so the order is guaranteed). Call sites have no order, so methods at the same depth are ordered by name.
- `POST /api/logs/explain` (`retrieval.LogTrailService`): hybrid search restricted to `LogStatement`, then the
  matched log's API (nearest handler), the logs after it in that API's trail, the likely failing method (the matched
  log's method: the failure came after its line) and the error codes of that method and its callees (0..2 hops).
  The `explain-log-trail` skill adds an explanation when a model is configured.

## Consequences
- Population and shared-logic writes are deterministic (`EXTRACTED_DETERMINISTIC`) even when the type was proposed by
  a model: the query, not the model, decides each edge; the type definition records who proposed it.
- Restoring does not revert definitions (schema, skills, pipelines) or vectors directly; vectors follow the outbox.
- Extractor- or skill-based population of a proposed type is not automatic yet: it becomes an evolution request.
- The dump/load runbook (`docs/recovery.md`) is manual; Community cannot stop its database inside the test container.
