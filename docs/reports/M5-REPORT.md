# M5 report: history, snapshots, update pipelines, relationship discovery, log trail

Date: 2026-11-17 · Implemented by: Claude (directly) · Prompt: `docs/prompts/M5-PROMPT.md` · ADR-016 (and ADR-015)

## 1. Summary

- **Finished first (half-done work found at start):** ADR-015 scopes and proposals for KGP (proposal items stored
  outside `:Entity`/`:Assertion`, approval replays them through the writer in one transaction). Its 5 ITs passed;
  committed separately as `ADR-015: scopes and proposals for KGP`.
- **History:** state as of any `seq` and diffs between seqs, from versions (`docs/versioning.md`). Event filters by
  operation (run/execution), commit and source. `GET /api/commits/{sha}/knowledge-changes`.
- **Snapshots:** `:Snapshot` = seq + active schema, skill, pipeline and query-template versions, source versions,
  model/vector config, generation per collection, app release (`git describe`). Snapshot diff grouped by change and
  type, with evidence and the configuration that changed.
- **Recovery:** restore = inverse writes through the single writer (history kept). Data migrations (`setDefault`,
  `renameProperty`) take a checkpoint and restore it on failure. Nightly gap-checked JSONL export of events to the
  document store. Runbook `docs/recovery.md` (in-graph undo, export, `neo4j-admin` dump/load).
- **Update pipelines:** `code-on-commit` (commit range, shared logic, derived relationships, requirement links,
  snapshot on `uat`/`pilot`; `POST /api/pipelines/code-on-commit` and `bin/pke-code-on-commit.sh`), `brd-update`
  (section diff; only new or changed sections reach the model), `reindex`.
- **Relationship discovery:** deterministic `SHARES_LOGIC_WITH` (utilities, config, logging, DTOs, mappers excluded by
  `pke.discovery.shared-logic.exclude`). Skill `discover-relationships` proposes types; a proposal with a guarded
  population query is registered as Level-1 metadata (`extraction.query`) and populated, anything else becomes an
  `:EvolutionRequest` for M7. Business types are refused when their query traverses only technical relationships.
- **Log trail:** `log-trail-of-api` 1.1.0 (guaranteed order) and `POST /api/logs/explain` (match, next expected logs,
  likely failing method, error codes, plus the `explain-log-trail` skill's explanation).

## 2. Versions chosen

No new dependencies.

## 3. Environment

| Item | Value |
|---|---|
| OS / Java / Maven | Windows 11 · Oracle JDK 21.0.11 · Maven 3.9.11 |
| Docker / Neo4j / Qdrant for tests | 29.4.1 · `neo4j:2026.10.1-community` · `qdrant/qdrant:v1.18.0` (Testcontainers) |
| Model | none; hashing embeddings. Replay fixtures, hand-authored as in M3/M4 (3 new) |

## 4. Files created or changed (main ones)

```
engine/graph        HistoryQueries (state as of seq, diff, current entities), HistoryStore (snapshots, configuration
                    capture, event rows, export records), EvolutionRequestStore; KnowledgeQueryService (EventFilter:
                    operation/commit/source); CodeQueryService (sharedLogic, relationshipPatterns, logContext);
                    GraphSchemaInitializer (+3 indexes, Snapshot constraint)
engine/history      SnapshotService (capture, diff, release), RecoveryService (restore to seq), HistoryExporter
                    (nightly JSONL), MigrationService (checkpointed data migrations)
engine/pipeline     steps: SnapshotStep, SharedLogicStep, DerivedRelationships + PopulateDerivedStep,
                    RelationshipDiscoveryStep, BrdSectionDiffStep, ReindexStep; RepositoryIngestStep (commit range)
engine/ingest       IngestionRunService.Request(from, to), RepositoryIngestionService.ingest(.., to, ..)
engine/retrieval    LogTrailService; GuardedCypher.run(.., maxRows)
engine/api/rest     HistoryController, DiscoveryController; KnowledgeController (event filters);
                    PipelineController (POST /api/pipelines/{code-on-commit|brd-update|reindex})
definitions         pipelines code-on-commit, brd-update, reindex, discover-relationships; skills
                    discover-relationships, explain-log-trail (+ evals); relationships/SHARES_LOGIC_WITH;
                    meta/relationship-type: extraction {query, proposedBy, rationale}; queries/log-trail-of-api 1.1.0
demo                transfer-service-changes/c4 (shared validateCurrency + TransferTextUtils utility)
bin                 pke-code-on-commit.sh
tests               HistoryIT, DiscoveryAndUpdatesIT; TestFaultInjector.failOnceAfter; 3 replay fixtures
docs                ADR-016, versioning.md, recovery.md, REST.md, README, IMPLEMENTATION_PLAN (M5 done),
                    definitions/README, demo/README
```

## 5. Build and test results

Command: `mvn -B clean verify` (repo root, Docker running)

```
[INFO] PKE SPI ............................................ SUCCESS [  3.181 s]
[INFO] PKE Engine ......................................... SUCCESS [07:16 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  07:20 min
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire | 130 | 0 | 0 | 0 |
| pke-engine | failsafe (real Neo4j, Qdrant, replay model) | 108 | 0 | 0 | 0 |

New ITs (13), mapped to the prompt's test list:

| Prompt requirement | Test |
|---|---|
| History reconstruction at three seqs | `HistoryIT.stateAsOfASeqIsReconstructedFromVersions` (four seqs, plus REST state and diff) |
| Snapshot diff after the demo code change shows the removed and added `CALLS` | `HistoryIT.codeOnCommitSnapshotsUatAndTheDiffShowsTheRemovedAndAddedCall` (c2 through `code-on-commit` tagged `uat`; also commit → knowledge, no snapshot for `dev`) |
| A failed migration restores from its checkpoint | `HistoryIT.aFailedMigrationRestoresTheCheckpoint` (fails in batch 2; state equals the checkpoint state; a clean run then completes) |
| Export (restore test where practical) | `HistoryIT.changeEventsAreExportedAsJsonlExactlyOnce` |
| brd-update touches only changed sections (LLM call count) | `DiscoveryAndUpdatesIT.brdUpdateSendsOnlyChangedSectionsToTheModel` (v1 → v2: 6 unchanged; card calls = sections sent, all in added ∪ changed) |
| `SHARES_LOGIC_WITH` correct, utilities excluded | `DiscoveryAndUpdatesIT.sharedLogicLinksApisThroughBusinessMethodsButNotUtilities` (c4: validate ↔ execute share exactly `validateCurrency(String)`; `TransferTextUtils` never counted; re-run writes nothing) |
| A proposed relationship type registered without Java changes and populated | `DiscoveryAndUpdatesIT.aProposedRelationshipTypeIsRegisteredWithoutJavaChangesAndPopulated` (`CAN_RETURN_ERROR` registered with its event and populated: validate API → 003040, 003041; a business type from technical paths and an unpopulatable type become evolution requests) |
| Log-trail template and log matching return the expected order | `DiscoveryAndUpdatesIT.theLogTrailIsInCallOrderAndALogLineIsExplained` |

Plus ADR-015's `ProposalIT` (5), now in the suite.

## 6. Problems found and fixed during the milestone

1. `log-trail-of-api` sorted inside a `WITH` and then projected with `DISTINCT`, so the order was not guaranteed. It now
   sorts after `RETURN` (1.1.0).
2. The log-trail skill input listed error codes in `collect()` order, which changed the replay hash between runs. Codes
   are sorted.
3. A population query that fails (e.g. wrong endpoint types) failed the whole step; failures are now reported per type.
4. My first brd-update assertion expected 5 unchanged sections (the fingerprint count in `BrdIngestPipelineIT`); the
   section diff counts by heading path and finds 6 (the title section text changes too).

## 7. Deviations from the prompt

1. **Migrations are data migrations only** (`setDefault`, `renameProperty`); the schema change itself is still
   registered through the existing (backwards-compatible) promotion flow first. `retype`, `deprecate` and
   `recompute(pipeline)` are not implemented. Migrations live in `history` (they depend on checkpoints and restore),
   not `schema`, which may not depend on `graph`.
2. **Restore test is the migration checkpoint restore plus the export test**; an automated `neo4j-admin` dump/load
   test was not practical (Community cannot stop its only database inside the test container). The procedure is in
   `docs/recovery.md`.
3. **Population capabilities:** only a query (template-style read-only Cypher) can populate a proposed type
   automatically. Extractor- or skill-based population becomes an evolution request.
4. **Snapshots have no change event** (they change no knowledge, so they take no seq); they are listed by
   `GET /api/snapshots`.
5. **Log-trail order is by call depth, then method name, then source order**: `CALLS` edges have no call-site order.
6. **`brd-update` reuses the brd-ingest steps** plus a section-diff step; the "changed sections only" guarantee comes
   from the existing section fingerprints.
7. **No real-repository smoke this milestone**; M4's smoke covers the ingestion path. `SHARES_LOGIC_WITH` and the
   `log-trail` explanation should be tried on `dkn-international-transfer-service` next.
8. Fixtures for the two new skills are hand-authored (no `OPENAI_API_KEY`), as in M3/M4.

## 8. Problems and open questions

- **Shared-logic exclusion list** (product decision, low risk): the default regex excludes class names containing
  util, config, constant, logging, logger, dto, mapper, properties, generated. Real OCS code may need more (e.g.
  `Helper`, `Validator` base classes). Configurable via `pke.discovery.shared-logic.exclude`.
- **Restore of renamed entities**: restoring an entity whose natural key changed after the checkpoint (a rename) is
  not covered by a test.
- **`POST /api/history/restore` and migrations are unauthenticated** until M6.
- Earlier open items unchanged: real embeddings and prompts (needs the key), library classpath for symbol resolution,
  error-constant mapping, Word documents without headings.

## 9. Git

ADR-015 committed as `ADR-015: scopes and proposals for KGP`; M5 committed as `M5: history, snapshots, update
pipelines, relationship discovery, log trail` by William Thomas <pkwilliamthomas@gmail.com>, both pushed to
`origin` main.

## Next milestone

**M6** (`docs/prompts/M6-PROMPT.md`): MCP server, security, Claude Code skills (bootstrap, journey interview,
answer questions).
