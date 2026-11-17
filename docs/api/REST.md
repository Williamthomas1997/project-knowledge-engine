# REST API (M6)

Base URL `http://localhost:8089`. JSON unless stated. Errors are RFC 9457 problem details. Validation errors list
every problem in `violations: [{path, message}]`.

| Status | Meaning |
|---|---|
| 400 | Invalid input (schema, identifiers, values, evidence). Nothing was written |
| 404 | Entity, relationship or type not found |
| 409 | Conflict: an immutable schema or skill version, an older version, a breaking schema change, a question already answered, an execution that cannot be resumed or cancelled |
| 422 | A skill run from the API failed: invalid input or an answer outside the output schema (`errorClass`) |
| 503 | A skill run failed transiently (rate limit, timeout); retry later |

| 401 | No API key, or an unknown or revoked one |
| 403 | The key does not have the role the endpoint needs |

**Authentication (M6, ADR-017).** Every `/api` call needs an API key: `Authorization: Bearer <key>` (or
`X-API-Key: <key>`). Keys are created with `bin/pke-keys.sh create <name> <ROLE>` and only their SHA-256 is stored.
Roles (higher ones include lower ones):

| Role | May call |
|---|---|
| `READER` | every `GET`; `POST /api/retrieval/search`, `/api/context`, `/api/retrieval/templates/{id}/run`, `/api/logs/explain`, `/api/schema/validate` |
| `CONTRIBUTOR` | every other `/api` call (ingestion, pipelines, answers, proposals, snapshots, imports), plus READER |
| `SCHEMA_ADMIN` | `POST /api/schema/entity-types`, `/api/schema/relationship-types`, `/api/history/restore`, `/api/schema/migrations`, plus CONTRIBUTOR |
| `EVOLUTION_OPERATOR` | evolution runs (M7), plus CONTRIBUTOR |

Every mutating call (including refused ones) is written to the audit log as an `:AuditEvent` (actor, roles, method
and path, status). Change events record the key as the actor, `key:<name>`; the optional `X-PKE-Actor: <name>` header
adds who the key acts for (`key:kgp/william`). `GET /actuator/health` is open but shows details only to a READER key.
The examples below leave the header out: add `-H "Authorization: Bearer $PKE_API_KEY"` to each.

MCP (`/mcp`, same keys) is described in `docs/mcp-tools.md`.

## Schema registry

| Method and path | Purpose |
|---|---|
| `GET /api/schema/entity-types` · `GET /api/schema/relationship-types` | Current definitions |
| `GET /api/schema/{entity-types\|relationship-types}/{name}` | One current definition |
| `GET /api/schema/{entity-types\|relationship-types}/{name}/versions` | All registered versions |
| `POST /api/schema/entity-types` · `POST /api/schema/relationship-types` | Register (body `application/yaml` or `application/json`, same format as `definitions/schema/`). `201` registered, `200` unchanged |
| `POST /api/schema/validate` | Dry run. Returns `{valid, violations, breakingChanges, currentVersion}` and writes nothing |

```bash
curl -X POST localhost:8089/api/schema/relationship-types -H 'Content-Type: application/yaml' --data-binary @- <<'EOF'
kind: RelationshipType
name: REGULATORY_DEPENDENCY
version: 1.0.0
description: Indicates a dependency on a regulatory control
category: business
sourceTypes: [BusinessFeature]
targetTypes: [RegulatoryControl]
properties: { rationale: { type: text } }
EOF
```

## Scopes and proposals (ADR-015)

Knowledge has a scope: `main`, or `proposal:<id>`. Every write endpoint below accepts `?scope=proposal:<id>`: the change
set is validated like a main write and recorded in the proposal (invisible to default reads). Entity and relationship
reads accept `?scope=proposal:<id>` or `?includeProposals=true` to overlay proposal items on main; views have a `scope`
field. Retrieval, templates and context always read main.

| Method and path | Purpose |
|---|---|
| `POST /api/proposals` | `{id?, title, description?, sourceRun?}`: creates an `OPEN` proposal. `201`; `409` if the id exists |
| `GET /api/proposals?status=OPEN\|APPROVED\|REJECTED` | List |
| `GET /api/proposals/{id}` | Proposal + diff against main: `entities` and `relationships`, each `{added, changed, removed, unchanged}` |
| `POST /api/proposals/{id}/approve` | `{comment?}`: promotes every item to main in one transaction; events carry `proposalId` and `approvedBy` |
| `POST /api/proposals/{id}/reject` | `{reason?}`: the proposal and its items become `REJECTED`; history is kept |

## Knowledge writes

| Method and path | Purpose |
|---|---|
| `POST /api/knowledge/changes` | `{entities[], relationships[], invalidations[], operationId?}` in **one transaction** |
| `POST /api/entities` | One entity write |
| `POST /api/relationships` | One relationship write |
| `POST /api/relationships/{id}/invalidate` | `{reason?, invalidAt?, evidence?}`. Ends current validity; history is kept |

Entity write: `{type, id?, properties, epistemic, validAt?, evidence[]}`. Relationship write: `{type, subject,
object, properties, epistemic, validAt?, evidence[]}`, where `subject`/`object` are `{id}` or
`{type, key: {<natural key>}}`. Evidence: `{sourceArtifactId, extractionMethod, sourceVersion?, location?,
contentHash?, pipelineVersion?, skillVersion?, modelProvider?, modelId?, excerpt? (≤2000 chars), verification?,
confidence? (0..1, stored as uncalibrated), epistemic?}`. `epistemic` is one of `OBSERVED,
EXTRACTED_DETERMINISTIC, INFERRED_AI, HUMAN_VERIFIED, CONTRADICTED, SUPERSEDED`. AI-inferred evidence needs `modelId`.

Writes state the **full current** domain properties: properties left out are removed (the previous values stay in
the version history). The response lists each item's outcome (`CREATED`, `UPDATED`, `UNCHANGED`, `EVIDENCE_ADDED`,
`INVALIDATED`, `REVALIDATED`), its version and its `seq`, plus `firstSeq`/`lastSeq`. Re-sending the same input
returns `UNCHANGED` and writes nothing.

## Reads

| Method and path | Purpose |
|---|---|
| `GET /api/entities?type=&offset=&limit=` | Page of entities (limit ≤ 200) |
| `GET /api/entities/{id}` | Entity, properties and evidence |
| `GET /api/entities/{id}/versions` | Version history (`validFromSeq`/`validToSeq`, property snapshot) |
| `GET /api/entities/{id}/relationships?includeInvalid=false` | Relationships where the entity is subject or object |
| `GET /api/relationships/{id}` · `GET /api/relationships/{id}/versions` | Assertion, evidence and history |
| `GET /api/history/events?afterSeq=0&toSeq=&limit=100&subjectId=&operationId=&commit=&source=` | Change events in `seq` order. `operationId`: an ingestion run or pipeline execution id; `commit` (7–40 hex) and `source` (`repo:…`, `doc:…`): events of the runs that processed it, or whose evidence cites it (M5) |
| `GET /api/integrity` | Assertion ↔ native edge consistency report |
| `GET /api/system/info` · `GET /actuator/health` | Versions and model availability; health |

## Sources (M2)

| Method and path | Purpose |
|---|---|
| `POST /api/sources/documents` | Multipart `file` (+ optional `key` = `doc:<slug>`, `name`). `201` new version, `200` unchanged/reparsed/reinstated. Returns the version, `parseStatus` (`PARSED`, `EMPTY`, `OCR_REQUIRED`) and section count |
| `POST /api/sources/repositories` | `{name, path, serviceName?}`: registers a local Git repository (`repo:<name>`). `serviceName` must match the gateway route's service (e.g. `international-transfer-service`) |
| `GET /api/sources?kind=` · `GET /api/sources/{key}` | Sources; one source with its current version |
| `GET /api/sources/{key}/versions` | Document versions (content hashes) or recorded commits |
| `GET /api/sources/{key}/sections?version=` | Sections of the current (or a given) document version |
| `DELETE /api/sources/{key}` | `{reason?}`: marks the source removed; versions, sections and evidence are kept |
| `GET /api/sources/sections/search?q=&limit=` | Fulltext search over current sections (not embedded) |

## Ingestion and journeys (M2)

| Method and path | Purpose |
|---|---|
| `POST /api/ingestion/runs` | `{kind: REPOSITORY\|ROUTES, target: <repository name>, mode: FULL\|INCREMENTAL}`. `202` with the run; `?wait=true` runs it before replying |
| `GET /api/ingestion/runs?target=&limit=` · `GET /api/ingestion/runs/{id}` | Runs: `status` (`PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`), commits, `statsJson` (outcome counts, journeys, routes, failures), `unresolvedJson` (first 200) |
| `POST /api/ingestion/runs/{id}/retry` | New attempt with the same parameters |
| `GET /api/journeys` | Journeys with step counts and sources |
| `GET /api/journeys/{idOrKey}` | Ordered steps with their gateway APIs, handler methods and per-source labels; data flow (`FEEDS`); covering suites |

## Pipelines (M3)

| Method and path | Purpose |
|---|---|
| `GET /api/pipelines` | Active pipelines (`brd-ingest`, `code-ingest`; M5: `code-on-commit`, `brd-update`, `reindex`, `discover-relationships`) with their steps |
| `POST /api/pipelines/{id}/executions` | Body = pipeline input, e.g. `{"documentKey": "doc:international-transfer-brd"}` or `{"repository": "transfer-service", "mode": "INCREMENTAL"}`. `202`; `?wait=true` runs it first. The same input with unchanged sources returns the existing execution unless `?force=true` |
| `GET /api/pipelines/executions?pipeline=&status=&limit=` · `GET /api/pipelines/executions/{id}` | Executions with per-step `status` (`PENDING`, `RUNNING`, `RETRYING`, `COMPLETED`, `FAILED`, `CANCELLED`, `SKIPPED_NO_MODEL`), `attempts`, `errorClass` (`TRANSIENT`, `PERMANENT`, `POLICY`, `CANCELLED`) and `outputJson`. Execution status: `PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL` (no model), `FAILED`, `CANCELLED` |
| `POST /api/pipelines/executions/{id}/resume` | Continues a FAILED, CANCELLED or PARTIAL execution from its first incomplete step (`?wait=true`) |
| `POST /api/pipelines/executions/{id}/cancel` | Cancels a pending or running execution |

## Questions (M3)

| Method and path | Purpose |
|---|---|
| `GET /api/questions?status=open\|answered\|all&limit=` | Open questions: conflicts the engine does not resolve (`CARD_CONFLICT`, `LINK_REVIEW`), with `options` and the entities they concern |
| `GET /api/questions/{id}` | One question with the evidence and sections it cites |
| `POST /api/questions/{id}/answer` | `{"option": "keep-existing\|use-new\|keep-both", "comment": "..."}` (`accept\|reject` for links). Applied through the writer as `HUMAN_VERIFIED` evidence; the `X-PKE-Actor` is recorded on the change events |

## Skills and model calls (M3)

| Method and path | Purpose |
|---|---|
| `GET /api/skills` · `GET /api/skills/{name}` | Active skills; one skill with its schemas, instructions and versions |
| `POST /api/skills/{name}/run` | Runs a skill on an input and returns the validated output and provenance. Writes no knowledge (ledger only). Without a model: `{"skipped": true}` |
| `GET /api/ai/calls?executionId=&limit=` | Cost ledger: provider, model, skill, tokens, cost estimate, prompt hash, outcome |

`GET /api/entities/{id}` now also returns `status` (`CURRENT`/`INVALID`), `invalidAt` and `invalidationReason`;
`POST /api/knowledge/changes` accepts `entityInvalidations: [{entityId, reason?, invalidAt?, evidence?}]`.

## Retrieval and context (M4)

| Method and path | Purpose |
|---|---|
| `POST /api/retrieval/search` | `{query, k?, types?, collections?, asOfSeq?, includeInvalid?, mmr?, generation?}`: hybrid search (catalog routing, vector + fulltext, RRF, optional MMR). Hits: `ENTITY` or `SECTION` with `type`, `label`, `score`, `sources` (e.g. `vector:KnowledgeCard_text`, `fulltext:entity`); `warnings` when the vector store is unavailable |
| `POST /api/context` | `{task, budgetTokens? (2000), depth? (2), seeds? (5)}`: task context in four sections (`verified`, `deterministic`, `inferred`, `conflicts`), each item with citations `source@version#location`; `usedTokens`, `truncated`, `templates`, `fallbackCypher`, `markdown` |
| `GET /api/entities/{id}/neighbourhood?depth=1&limit=50` | Entities and current relationships around one entity |
| `GET /api/retrieval/templates` · `POST /api/retrieval/templates/{id}/run` | Query templates; run one from `{seedId, limit?}` (read-only, guarded) |
| `POST /api/eval/runs` · `GET /api/eval/datasets` | `{dataset?, generation?}`: runs the evaluation datasets against the current graph; recall@k, precision@k, MRR, nDCG, context recall, graph completeness, citation correctness, source coverage, latency, tokens |

## Index and synchronisation (M4)

| Method and path | Purpose |
|---|---|
| `GET /api/sync/status` | Sources not processed or changed since their last success, entities with only obsolete evidence, index state per collection (expected vs indexed, open tasks), unfinished runs and pipelines, schema migrations awaiting, open questions (MASTER_SPEC §19.4) |
| `POST /api/sync/reconcile` | Compares graph and vector store; enqueues missing records, deletes orphans |
| `POST /api/sync/retry-failed` | FAILED index tasks become PENDING again |
| `GET /api/vector/collections` | Collections with their generations |
| `GET /api/embeddings/generations` · `POST /api/embeddings/generations?wait=` | Generations; `{model?}` (`hashing-<dims>` or `spring-ai`) builds a new one in the background (no knowledge events) |
| `POST /api/embeddings/generations/{id}/activate?force=` | Evaluates a READY generation against the active one; switches only if not worse (or `force`) |

Ingestion runs and pipeline executions end `PARTIAL` with `indexPending: true` when their vector index tasks are still
open; the index worker completes them.

## History, snapshots and recovery (M5, ADR-016)

| Method and path | Purpose |
|---|---|
| `GET /api/history/state?seq=&type=&limit=1000` | Entities and relationships current at `seq` (from versions; see `docs/versioning.md`) |
| `GET /api/history/diff?fromSeq=&toSeq=` | Items `ADDED`, `CHANGED` or `INVALIDATED` between two seqs (`toSeq` default now), with before/after state, invalidation reason and the evidence recorded in between |
| `POST /api/snapshots` | `{label?}`: records the current seq plus active schema, skill, pipeline and query-template versions, source versions, model config, index generation per collection and the app release. `201` |
| `GET /api/snapshots?limit=` · `GET /api/snapshots/{id}` | Snapshots, newest first |
| `GET /api/snapshots/diff?from=&to=` | Knowledge changes between two snapshots grouped by change and type (`entities.ADDED.JavaMethod[]`, `relationships.INVALIDATED.CALLS[]`), counts, and the configuration that changed (`{section: {key: {from, to}}}`) |
| `GET /api/commits/{sha}/knowledge-changes` | Events produced by a commit (its ingestion runs and evidence citing it), with counts per event type |
| `POST /api/history/exports` · `GET /api/history/exports` | Exports change events not yet exported as one JSONL file to the document store (also nightly, `pke.history.export-cron`, default 02:00); lists exports (`fromSeq`, `toSeq`, `ref`, `sha256`) |
| `POST /api/history/restore` | `{seq, reason?}`: reverts every knowledge change after `seq` by writing the inverse changes (history kept; operation id `restore:<seq>:…`) |
| `POST /api/schema/migrations` | `{id, entityType, operations: [{op: setDefault, property, value} \| {op: renameProperty, from, to}], batchSize?}`: checkpoint snapshot, batched rewrite through the writer; on failure the checkpoint is restored (`status: RESTORED`) |

## Update pipelines and discovery (M5)

| Method and path | Purpose |
|---|---|
| `POST /api/pipelines/code-on-commit?wait=true` | `{repository, fromCommit?, toCommit?, tag?, snapshot?}`: incremental extraction of the commit range, `SHARES_LOGIC_WITH`, requirement links, and a snapshot when `tag` is `uat` or `pilot`. CLI: `bin/pke-code-on-commit.sh <repository> [toCommit] [tag] [fromCommit]` |
| `POST /api/pipelines/brd-update?wait=true` | `{documentKey, version?}`: section diff against the previous version (`added`, `changed`, `removed`), cards and reconcile on new or changed sections only, questions on conflicts |
| `POST /api/pipelines/reindex?wait=true` | `{model?, activate?, reason?}`: optional new embedding generation, then reconciliation of every collection |
| `POST /api/pipelines/discover-relationships/executions?wait=true` | `{focus?}`: `SHARES_LOGIC_WITH`, re-population of derived types, then skill proposals: registered and populated (Level-1) or filed as evolution requests |
| `GET /api/evolution/requests?status=OPEN` | Evolution requests (e.g. a proposed relationship type no query can populate); consumed in M7 |
| `POST /api/logs/explain` | `{line, apiId?}`: the matching log statement, the API, the logs expected next, the likely failing method, related error codes, and the `explain-log-trail` skill's explanation (`explanationStatus`: `OK`, `SKIPPED_NO_MODEL`, `NO_MATCH`) |
