# Versioning and historical reconstruction

How PKE answers "what did the graph say at seq S?" (plan §5, ADR-009, ADR-016).

## What is recorded

Every knowledge write runs in one Neo4j transaction that takes the next `seq` values from the `:Sequence` node
(gap-free, in commit order) and writes, for each real change:

| Node | Holds |
|---|---|
| `(:Entity)-[:HAS_VERSION]->(:EntityVersion)` | `version`, `status` (`CURRENT`/`INVALID`), `propsJson` (all domain properties), `epistemic`, `validFromSeq`, `validToSeq` |
| `(:Assertion)-[:HAS_VERSION]->(:AssertionVersion)` | the same for a relationship; type, subject and object are on the assertion and never change |
| `(:ChangeEvent {seq})-[:ABOUT]->(subject)` | why: event type, actor, `operationId` (ingestion run, pipeline execution, migration, restore), details (no values) |
| `(:Evidence {recordedSeq})` | which source version supports the item, recorded with the seq of the change |

A new version closes the previous one: `previous.validToSeq = new.validFromSeq`. Unchanged input writes nothing.

## The algorithm

State as of `S`:

```
versions v with v.validFromSeq <= S AND (v.validToSeq IS NULL OR v.validToSeq > S) AND v.status = 'CURRENT'
```

`GET /api/history/state?seq=S` returns them (entities and relationships, optionally one type).

Diff between `A < B` (`GET /api/history/diff`, snapshot diff): every item with a version whose `validFromSeq` is in
`(A, B]`, classified by its state at `A` and at `B`: `ADDED` (absent or invalid at A, current at B), `INVALIDATED`
(the reverse), `CHANGED` (current at both with different properties or epistemic status). The evidence listed is the
evidence recorded in `(A, B]`.

Git → knowledge: an ingestion run records the commit it processed (`toVersion`); its events carry the run id as
`operationId`. `GET /api/commits/{sha}/knowledge-changes` joins the two, plus events whose evidence cites the commit.

## Limitations

- **Evidence is not versioned.** Evidence nodes are append-only and dated by `recordedSeq`; the state at S says
  which items held, and evidence with `recordedSeq <= S` is what supported them then.
- **Schema meaning is not re-applied.** Versions keep `schemaVersion`; reading an old version with a newer schema is
  the reader's job. Retired types stay readable.
- **Entity identity is stable across renames** (`pke_id` kept, `:Alias` for the old key), so a rename is a `CHANGED`
  entity, not an `INVALIDATED` + `ADDED` pair.
- **Vectors are not historical.** Vector records follow the current graph; as-of retrieval filters on graph state.
- **Proposals are outside main history** (ADR-015): their items have their own versions until approved.
- **Snapshots are cheap pointers**: a seq plus configuration, not a copy of the graph. Restoring "to a snapshot" means
  writing the inverse of later changes (`POST /api/history/restore`), which is itself history.
- **Reconstruction is bounded**: one state or diff call returns at most 5,000 items per kind.
