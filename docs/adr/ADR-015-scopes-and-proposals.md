# ADR-015: Scopes and proposals

Status: **accepted** (KGP P01, 2026-11-17). Requested by the Knowledge Graph Platform (KGP), which writes new business
knowledge as a proposal that a human approves before it becomes official (KGP decisions D-28, D-48).

## Context

KGP produces knowledge (requirement points, flows, solutions) that must stay invisible to normal consumers until a
human approves it, keep its full history, and become official in one step. PKE had one graph with one meaning:
everything written is current knowledge.

## Decision

### Scopes
- Every knowledge item has a scope. Main knowledge has `pke_scope = 'main'` (set by the writer on every entity and
  assertion write; items written before this ADR have no property and are read as `main`).
- A proposal owns the scope `proposal:<id>` (`id` matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$`).
- Entity and relationship views have a `scope` field.

### Isolation by construction
Proposal content is **not** written as `:Entity`/`:Assertion` nodes. It is stored as:

| Node | Content |
|---|---|
| `(:Proposal {pke_id, pke_scope, title, description, sourceRun, status OPEN/APPROVED/REJECTED, createdBy, decidedBy, reason, appliedFirstSeq, appliedLastSeq})` | the proposal |
| `(:Proposal)-[:HAS_ITEM]->(:ProposalItem {pke_id = proposal:<id>:<kind>:<targetId>, kind, targetId, pke_type, subjectId, objectId, propsJson, pke_propsHash, payloadJson, contentHash, pke_version, pke_status CURRENT/PROMOTED/REJECTED})` | one proposed write: `ENTITY`, `RELATIONSHIP`, `ENTITY_INVALIDATION` or `RELATIONSHIP_INVALIDATION`; `targetId` is the id it has (or will have) in main |
| `(:ProposalItem)-[:HAS_VERSION]->(:ProposalItemVersion {version, propsJson, payloadJson, validFromSeq, validToSeq})` | item history |

Proposal items have no `:Entity`/`:Assertion` label and no native edge. So **every existing read** (entity and
relationship reads, neighbourhood, retrieval, graph templates, context, integrity, vector sync) sees only main,
without changing any of those queries. This was chosen over a `pke_scope` filter in every query (197 `MATCH`
statements, plus YAML templates): a filter that is forgotten once leaks proposals; a label that is never set cannot.

### Writes
- `POST /api/knowledge/changes?scope=proposal:<id>` (and `/api/entities`, `/api/relationships`,
  `/api/relationships/{id}/invalidate` with the same parameter).
- The change set is validated by `KnowledgeWriter` exactly as a main write (schema, natural keys, evidence).
  Retractions and `skipMissingEndpoints` are refused. Relationship endpoints must exist in main or in the same
  proposal; invalidation targets must exist in main.
- Items are written through `GraphTransactions` (the single writer) with `ChangeEvent`s
  `PROPOSAL_ITEM_RECORDED` / `PROPOSAL_ITEM_UPDATED`. Writing the same content again is a no-op (no event, no seq).
- Only `OPEN` proposals accept writes (`409` otherwise).

### Reads
- Default: main only.
- `scope=proposal:<id>` or `includeProposals=true` (all open proposals) widens `GET /api/entities`,
  `/api/entities/{id}`, `/api/entities/{id}/relationships` and `/api/relationships/{id}`: a proposed version replaces
  the main item with the same id (status `PROPOSED`, scope = the proposal), a proposed invalidation marks it
  `PROPOSED_INVALIDATION`, and items that exist only in the proposal follow the main items.
- Retrieval, graph templates and context do **not** accept a scope yet: they always read main (see Consequences).

### Proposal endpoints
| Endpoint | Effect |
|---|---|
| `POST /api/proposals` `{id?, title, description?, sourceRun?}` | Creates an `OPEN` proposal (`PROPOSAL_CREATED`) |
| `GET /api/proposals?status=` | List |
| `GET /api/proposals/{id}` | The proposal and its diff against main now: entities and relationships, each `added`, `changed` (with `mainProperties`), `removed`, `unchanged` |
| `POST /api/proposals/{id}/approve` `{comment?}` | Replays every `CURRENT` item through `KnowledgeWriter` **in one transaction** with the decision; every event of that transaction carries `details.proposalId` and `details.approvedBy`, and the operation id `proposal:<id>:approve`. Items become `PROMOTED`, the proposal `APPROVED` (`PROPOSAL_APPROVED`) |
| `POST /api/proposals/{id}/reject` `{reason?}` | Items become `REJECTED`, the proposal `REJECTED` (`PROPOSAL_REJECTED`). Items, versions and events are kept |

### Writer changes
- `KnowledgeWriter.applyPrepared(ctx, prepared, …)`: applies a validated change set inside an existing transaction.
  `apply(...)` uses it unchanged.
- `WriteContext.tag(key, value)`: adds a key to the details of every event of the transaction.
- `PendingEvent.SubjectKind` gains `PROPOSAL` and `PROPOSAL_ITEM` (events are linked `ABOUT` the proposal node).
- New constraints: `Proposal.pke_id`, `ProposalItem.pke_id` unique.

## Consequences
- Approval is last-writer-wins against main: if main changed after the proposal was written, approval applies the
  proposed values on top (with history); the diff shows the state at the time it is read.
- Entities are never deleted in PKE; "removed" means a proposed entity or relationship invalidation.
- Proposal items are not embedded (no vector tasks) until they are approved, because only the writer enqueues them.
- Scoped retrieval, templates and context are left for when a consumer needs them (KGP P08 UI shows proposals through
  the entity and relationship reads).
- Tests: `ProposalIT` (invisible by default, widened reads, diff, idempotent re-write, approve with tagged events and
  history, reject keeps history, removals, bad scope, dangling endpoint).
