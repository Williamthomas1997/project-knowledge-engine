# M5: History, snapshots, update pipelines, relationship discovery, log trail (spec Phases 4–5)

Precondition: M4 accepted.

## 1. History and snapshots (plan §5, ADR-003)
- History queries: entity/assertion versions, events by entity/source/commit/execution, and state as-of a `seq`
  (the reconstruction algorithm documented in `docs/versioning.md`, with its limitations).
- `:Snapshot` capturing seq, active schema/skill/pipeline/query-template versions, source versions (Git
  commits and document versions), model and embedding config, the index generation per collection, and the
  application release (`git describe`).
- Snapshot diff: entities and relationships added/changed/invalidated between two snapshots, grouped by type,
  with evidence. REST: `POST /api/snapshots`, `GET /api/snapshots/{id}`, `GET /api/snapshots/diff?from=&to=`.
- Git-to-knowledge: events link to the commit that produced them, and `GET /api/commits/{sha}/knowledge-changes`.
- Nightly JSONL export of ChangeEvents to the document store, plus a documented dump/restore runbook
  (`docs/recovery.md`), tested by a restore test where practical.

## 2. Update pipelines
- `code-on-commit`: given repo + commit range → incremental extraction → reconcile edges → snapshot optional.
  Provide a CLI entry point and `POST /api/pipelines/code-on-commit` so that William's move-to-dev/UAT/pilot skills
  can call it later, and snapshot automatically when tagged `uat` or `pilot`.
- `brd-update`: a new document version → diff sections → re-run cards and reconcile on changed sections only → questions.
- `reindex`: schema change or new embedding generation.

## 3. Relationship discovery pipeline
- Deterministic `SHARES_LOGIC_WITH` between ApiOperations whose call trees reach the same non-trivial method,
  with exclusions from config (ADR-007 §6), `category: technical`, and the shared methods as evidence.
- Skill `discover-relationships`: proposes **new relationship types** (definition YAML + population method).
  When an existing capability (template query, extractor or skill) can populate one, register it as **Level-1
  metadata** through the schema promotion flow and run the population. Otherwise create an evolution request
  (consumed in M7). Business links are never inferred from shared utilities alone (spec §7).

## 4. Log trail (good to have)
- Template `log-trail-of-api`: the expected ordered LogStatements along an API's call path (level, template,
  method).
- Skill `explain-log-trail`: given the last log line seen (pasted text), match it to a LogStatement (fulltext +
  vector on `LogStatement_template`) and return the next expected logs, the likely failing method and the
  related error codes.

## 5. Tests
History reconstruction matches the expected state at three seqs; snapshot diff after the demo code change shows
the removed and added CALLS edge; a failed migration restores from its checkpoint; brd-update touches only the
changed sections (assert the LLM call count with replay); SHARES_LOGIC_WITH on the demo service is correct and
excludes utilities; a proposed new relationship type is registered without Java changes and populated; the log-trail
template and log matching return the expected order.
