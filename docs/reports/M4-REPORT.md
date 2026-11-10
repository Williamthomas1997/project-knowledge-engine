# M4 report: pluggable vector store, hybrid retrieval and context generation

Date: 2026-11-10 · Implemented by: Claude (directly) · Prompt: `docs/prompts/M4-PROMPT.md` · ADR-014

## 1. Summary

- **Ports:** `GraphStore` (Neo4j adapter over the M1 writer) and `VectorStorePort`, with two adapters: the Neo4j
  vector index (default) and Qdrant. Both pass the same 5 contract tests. ArchUnit keeps Neo4j types inside
  `graph`.
- **Embeddings:** a deterministic `HashingEmbeddingModel` by default; OpenAI under the `openai` profile. Embedding
  generations are built in the background without knowledge events, evaluated, then switched.
- **Collections:** collections are derived from `embedding.indexFields`, plus `CollectionCatalog` and
  `GraphSchemaMap`, all modelled as graph nodes.
- **Sync:** an outbox (`:IndexTask`) is written in the same transaction as each graph write, then applied by a
  worker with backoff. Runs and pipelines end `PARTIAL` until their vectors are indexed. Reconciliation repairs
  differences, and `GET /api/sync/status` covers MASTER_SPEC §19.4.
- **Retrieval:**
  - Catalog routing, then vector plus fulltext search, fused with RRF, optional MMR and a reranker port.
  - 8 query templates, and a guarded fallback where the model writes the Cypher.
  - `POST /api/context` returns verified facts, deterministic facts, AI inferences, and conflicts and open
    questions, with `source@version#location` citations.
- **Evaluation:** `eval/datasets/demo-transfer.yaml` (8 questions, including spec §10.2 and §20). Run with
  `POST /api/eval/runs` or `mvn -Peval verify`. The measured numbers are in §5.

## 2. Versions chosen

New: `spring-ai-neo4j-store` and `spring-ai-qdrant-store` 2.0.2 (store modules only, no auto-configuration),
Qdrant client 1.18.0, `testcontainers-qdrant` 2.0.5, and the `qdrant/qdrant:v1.18.0` image for tests. All are
recorded in ADR-000.

## 3. Environment

| Item | Value |
|---|---|
| OS / Java / Maven | Windows 11 · Oracle JDK 21.0.11 · Maven 3.9.11 |
| Docker / Neo4j / Qdrant for tests | 29.4.1 · `neo4j:2026.10.1-community` · `qdrant/qdrant:v1.18.0` (Testcontainers) |
| Model | none; hashing embeddings. Replay fixtures for the skills, hand-authored as in M3 |

## 4. Files created or changed (main ones)

```
engine/store        GraphStore port; KnowledgeChanges moved here from graph (callers outside graph use the port)
engine/vectorstore  VectorStorePort, QdrantVectorAdapter, SpringAiVectorSupport, EmbeddingResolver
engine/graph        Neo4jGraphStore, Neo4jVectorAdapter, IndexTaskStore (outbox; tasks created by KnowledgeWriter in the
                    write transaction), VectorCatalogStore, RetrievalQueries, SyncQueries, EvalRunStore;
                    KnowledgeWriter (outbox, pke_searchText), GraphReader (timeout), SourceWriter (export versions),
                    GraphSchemaInitializer (+7 constraints/indexes, english fulltext pke_entity_search)
engine/ai           HashingEmbeddingModel, EmbeddingModels (text-only embedding), EmbeddingPort = default model
engine/index        IndexWorker, OperationIndexing (runs/pipelines COMPLETED only when indexed), EmbeddingGenerations,
                    Collections (content + CollectionCatalog + GraphSchemaMap), IndexMaintenance (reconciliation, build,
                    evaluate-then-switch), VectorSimilarCardFinder (M3 finder switched to vectors), Vectors, QdrantConfiguration
engine/retrieval    RetrievalService, Fusion (RRF, MMR), Reranker, Lucene, QueryTemplates, CypherGuard, GuardedCypher,
                    GraphVocabulary, CypherFallback, ContextService, EvaluationService, RetrievalBootstrap
engine/ops          SyncStatusService
engine/api/rest     RetrievalController, SyncController
definitions         queries/*.yaml (8 templates), meta/query-template.schema.json, skills/write-cypher (+ eval case)
eval/datasets       demo-transfer.yaml (packaged as classpath:eval/datasets)
ingest, pipeline    RouteResolutionService registers route exports as sources; ingestion runs and pipelines await index tasks
tests               VectorStoreContract (+ Neo4j and Qdrant ITs), IndexSyncIT, RetrievalIT, GuardedCypherIT, CypherFallbackIT,
                    EvalIT, CypherGuardTest; support: DemoKnowledge, TestVectorStoreFaults; KnowledgeIT (worker driven by tests)
docs                ADR-014, ADR-000, REST.md, README, IMPLEMENTATION_PLAN (M4 done), definitions/README
```

## 5. Build, test and evaluation results

Command: `mvn -B clean verify` (repo root, Docker running)

```
[INFO] PKE SPI ............................................ SUCCESS [  2.477 s]
[INFO] PKE Engine ......................................... SUCCESS [04:27 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  04:31 min
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire | 130 | 0 | 0 | 0 |
| pke-engine | failsafe (real Neo4j, Qdrant, replay model) | 95 | 0 | 0 | 0 |

New in M4:
- Integration tests: Neo4j and Qdrant vector contract 5 + 5, IndexSyncIT 6, RetrievalIT 5, GuardedCypherIT 5,
  CypherFallbackIT 2, EvalIT 1.
- Unit tests: CypherGuardTest 5, and ArchitectureTest +3 (no Neo4j types outside `graph`, writes go through the
  `GraphStore` port, ports do not depend on adapters).
- **No skipped tests.**

M4 prompt §5:

| Requirement | Test |
|---|---|
| Neo4j and Qdrant pass the same contract tests | `VectorStoreContract`: search with payload, upsert = replace, metadata filters, delete + existing/all ids, collection and generation isolation (5 × 2 adapters) |
| Vector store down → `PARTIAL`, then completed by the worker | `IndexSyncIT.withTheVectorStoreDownAPipelineEndsPartialAndTheWorkerCompletesIt`: the graph write succeeds, tasks go to RETRY, the execution is `PARTIAL` + `indexPending`, then `COMPLETED` once the store is back; every card is indexed |
| Invalidating an entity deletes its vectors | `invalidatingAnEntityDeletesItsVectors` (a DELETE task in the same transaction, then the record is gone) |
| New embedding generation creates no knowledge events | `aNewEmbeddingGenerationIsBuiltWithoutKnowledgeEventsAndSwitchedAfterEvaluation`: `hashing-256` g2 READY, footprint unchanged, both generations written while g2 is READY, evaluated and activated, g1 RETIRED, switching writes nothing |
| Demo transfer-flow question returns graph + document evidence with correct citations | `RetrievalIT.theTransferFlowQuestion...`: IMPLEMENTED_BY (AI-inferred, cited), the validation API, `validateCurrency` (cited at `TransferService.java:38`), the BRD section `doc:international-transfer-brd@<sha256>#FR-1 ...`, and every citation resolves |
| Context stays within budget | `theContextStaysWithinItsBudget` (150 → ≤ 150 used with items dropped; 4000 → more items, still within budget) |
| Every template has tests | `everyQueryTemplateExpandsFromItsSeed` (8 templates on the full demo corpus) |
| Every fallback guard is tested | Writes are rejected statically and by the read-only transaction; an unknown label, an EXPLAIN failure, the injected LIMIT and the timeout each have a test. Static rules (procedures, functions, properties, multiple statements, backticks, LIMIT capping) are in `CypherGuardTest`. `CypherFallbackIT`: a guarded model query answers, and a model-written `DETACH DELETE` is rejected and never run |

**Evaluation** (`mvn -Peval verify` / EvalIT; 8 questions; k = 5; hashing embeddings; replay model):

| Metric | Value |
|---|---|
| recall@5 / precision@5 | **0.552** / 0.275 |
| MRR / nDCG@5 | 0.417 / 0.426 |
| context recall (expected entities in the task context) | **0.969** |
| graph completeness (expected relationship types in the context) | 0.875 |
| citation correctness / source coverage | **1.0** / 1.0 |
| context tokens (budget 2000) | 1568 mean |
| latency | search 54 ms, context 203 ms (mean, test container) |

Per question (recall@5 → context recall):

| Question | recall@5 | context recall |
|---|---|---|
| §10.2 requirements and APIs affected by beneficiary currency validation | 0.67 | 1.0 |
| §20 "Modify beneficiary currency validation in the transfer flow" | 0.75 | 0.75 |
| daily limit | 1.0 | 1.0 |
| error 003041 | 1.0 | 1.0 |
| which method throws 003040 | 0.0 | 1.0 |
| which journey calls the execute API | 0.5 | 1.0 |
| after the OTP check | 0.5 | 1.0 |
| Kafka topic on execution | 0.0 | 1.0 |

The gap between search recall and context recall is the point of §10.2: vector and keyword search alone do not find
"the method that throws 003040" or "the topic published on execution"; graph expansion from the hits does.

Problems found by the tests and the evaluation, and fixed:
1. The Cypher guard rejected one of my own templates (`c.m` map access).
2. With the vector store down, card reconciliation failed. Writes no longer depend on vectors: fulltext fallback.
3. Routing chose empty collections. Lucene missed camelCase identifiers (`pke_searchText` plus the English
   analyzer fix this). One noisy type took every context seed (now at most 2 per type). Test methods duplicated
   test cases. Cards crowded out code in search (keyword lists are now ranked per type).
4. Route evidence cited `routes:<file>@<hash>`, a source that did not exist (citation correctness 0.954 → 1.0). Route
   exports are now registered as `export` sources.
5. Spring AI embeds document metadata with the text by default; only the text is embedded now.
6. Smoke: sync status listed the route export as a document awaiting `brd-ingest` (fixed).

## 6. Real-repository smoke (manual, no model, hashing embeddings)

Packaged jar, throwaway Neo4j, read-only `dkn-international-transfer-service` and the real route export. **Nothing
was committed.**

| Check | Result |
|---|---|
| `code-ingest` FULL | COMPLETED in 116 s, `indexPending: false`: 174 log-statement records indexed in the same run |
| `sync/status` | 174 expected / 174 indexed in `LogStatement_template`; no open tasks; nothing unprocessed or changed |
| `sync/reconcile` | 0 missing, 0 orphans |
| search "beneficiary currency validation" | `OcsMasterValidationServiceImpl` "Failed to validate currency" log, `V2ValidationBeneficiaryEnrichmentService#enrichIfNeeded`, `POST /api/v2/international-transfers/validation`, in 0.55 s |
| search "daily transfer limit exceeded" | `InternationalTransferReservationServiceImp#validateTransferLimits`, the limit-error mapper tests and the limit log |
| context "Modify beneficiary currency validation ..." | 1481 / 1500 tokens, 22 deterministic facts with `repo:...@<commit>#<file>:<line>` citations, templates `impact-of-method` and `journey-of-api`, 0.9 s |

**Observation:** without a model the real repository has no cards and no summaries, so `LogStatement_template` is
the only populated collection and log statements dominate the context. Once BRDs are carded and API summaries are
written (`summarise-api`, M5), the other collections will balance this.

## 7. Deviations from the prompt

1. **Fixtures are still hand-authored** (no `OPENAI_API_KEY`), now 39, including `write-cypher`. The eval numbers above
   come from hashing embeddings; real-embedding numbers need the key (`--spring.profiles.active=openai`, then a
   `spring-ai` generation, evaluated and switched).
2. **The `GraphStore` port covers writes**; reads stay in `graph` query services that return plain Java values (no
   Neo4j types leak, which ArchUnit enforces). A second graph adapter would re-implement those services.
3. **Neo4j vector records are written by Spring AI's store directly** (not through `GraphTransactions`). This is
   recorded as an exception to ADR-009: they are a derived projection with no events.
4. **RRF uses k = 60** (Graphiti uses 1), and keyword search ranks each entity type separately. Both choices are
   recorded in ADR-014; revisit with real embeddings.
5. **Retired generations' Neo4j vector indexes are not dropped** (schema commands stay with `GraphSchemaInitializer`).
6. **Schema migrations awaiting** in sync status is always empty: declared migrations do not exist yet.

## 8. Problems and open questions

- **Real embeddings and real prompts** (architect decision, cost): run the evaluation with an OpenAI `spring-ai`
  generation and compare it with hashing. Generation activation then shows whether it is better.
- **API and method summaries** (`summarise-api`) would populate `ApiOperation_summary` and `JavaMethod_summary`;
  M5 could add a pipeline step for them.
- **Earlier open items unchanged:** library classpath for symbol resolution, error-constant mapping, Word documents
  without headings, real Gatling/polymorphic journeys, authentication (M6).

## 9. Git

Committed as `M4: pluggable vector store, hybrid retrieval and context generation` by William Thomas
<pkwilliamthomas@gmail.com> and pushed to `origin` main.

## Next milestone

**M5** (`docs/prompts/M5-PROMPT.md`): history queries, snapshots and diffs, incremental update pipelines,
relationship discovery, log trail, recovery.
