# ADR-014: Pluggable vector store, hybrid retrieval and context generation

Status: **accepted** (M4, 2026-11-09). Implements ADR-007 §1–§3 and §7, MASTER_SPEC §10, §19.4 and §20.

## 1. Ports and adapters

| Port (package) | Adapters | Notes |
|---|---|---|
| `store.GraphStore` (+ `store.KnowledgeChanges`, moved out of `graph`) | `graph.Neo4jGraphStore` over the M1 single writer | Code outside `graph` writes through the port (ArchUnit). Reads stay in `graph` query services that return plain Java values |
| `vectorstore.VectorStorePort` | `graph.Neo4jVectorAdapter` (default, `pke.vector.provider=neo4j`), `vectorstore.QdrantVectorAdapter` (`qdrant`, `pke.vector.qdrant.{host,port,tls,api-key}`) | Built on Spring AI `Neo4jVectorStore` / `QdrantVectorStore` (no auto-configuration). Both pass `VectorStoreContract` (5 tests) |

- ArchUnit: no `org.neo4j..` type outside `graph`; Spring AI only in `ai`, `vectorstore` and `graph`;
  `KnowledgeWriter` is not used outside `graph`; ports do not depend on their users or on the Neo4j adapter.
- The Neo4j vector adapter lives in `graph` because it needs the driver. Its records are
  `:PkeVector_<collection>_<generation>` nodes with a vector index each. Spring AI's store writes them directly.
  This is a recorded **exception to ADR-009**: they are a derived projection, not knowledge, and produce no events.
- Spring AI embeds `Document` metadata together with the text by default. Every model is wrapped so that only the
  text is embedded.

## 2. Embeddings and generations

- `HashingEmbeddingModel` is deterministic feature hashing: words, camelCase parts, bigrams and character
  trigrams, signed buckets, L2-normalised. It is the default (`pke.embedding.model=hashing-384`) and needs no key.
  The `openai` profile sets `spring-ai`.
- `(:EmbeddingGeneration {pke_id: g<n>, model, dimensions, status: BUILDING|READY|ACTIVE|RETIRED})`. Each
  generation has its own physical collections (`pke_<collection>_<generation>`), so dimensions never mix.
- `POST /api/embeddings/generations {model}` builds the generation in the background, directly from the graph (no
  tasks, **no knowledge events**). It becomes READY. `POST .../{id}/activate` evaluates the generation (mean
  recall@k over the evaluation datasets) against the active one and refuses a worse one unless `force`. The index
  worker writes every non-retired generation, so a READY generation stays current until it is switched.

## 3. Collections

- Content collections come from `embedding.indexFields`, named `<Type>_<field>`. Today: `KnowledgeCard_text`,
  `BusinessRequirement_text`, `JavaMethod_summary` and `ApiOperation_summary`.
- System collections:
  - `CollectionCatalog`: one record per content collection. The router searches it first.
  - `GraphSchemaMap`: one record per entity and relationship type, with description, properties, endpoints and
    query hints. The Cypher fallback reads it.
  - Both are regenerated from the registry. A content hash per generation skips unchanged rewrites.
- Graph: `(:VectorCollection)-[:INDEXES]->(:SchemaDefinition)`, `-[:EMBEDDED_WITH]->(:EmbeddingGeneration)`,
  `(CollectionCatalog)-[:DEPENDS_ON]->(content collections)`,
  `(GraphSchemaMap)-[:DERIVED_FROM]->(:SchemaDefinition)`.
- Record id = UUIDv5(`pke_id`, field, generation). Payload: `pke_id`, `type`, `collection`, `project`,
  `sourceVersionId`, `epistemic`, `status`, `validAt`.

## 4. Graph ↔ vector consistency (outbox)

- `KnowledgeWriter` creates `(:IndexTask {entityId, entityType, field, op: UPSERT|DELETE, status, operationId})`
  **in the same transaction** whenever an indexed field is created, changed, removed or revalidated. An entity
  invalidation creates DELETE tasks. A write that does not change an indexed field creates no task.
- `IndexWorker`: claims due tasks in batches. The last task per (entity, field) wins, and the worker reads the
  entity's *current* value, so it is idempotent. A batch whose store calls fail goes to RETRY with exponential
  backoff (`pke.index.retry-backoff`, capped at 10 min), then to FAILED after `max-attempts`
  (`POST /api/sync/retry-failed`).
- **Ingestion runs and pipeline executions are COMPLETED only when their tasks are done.** At the end they wait up
  to `pke.index.await-timeout`. If tasks are still open they end `PARTIAL` with `indexPending = true`, and the
  worker completes them when their last task is done.
- With the vector store down, nothing on the write path fails: card reconciliation falls back to fulltext, and
  retrieval falls back to fulltext lists and reports a warning.
- **Reconciliation** (`POST /api/sync/reconcile`, daily `pke.index.reconcile-cron`): compares, per collection and
  writable generation, the graph's current entities with the store's ids. Missing records are enqueued as tasks;
  orphans are deleted.
- `GET /api/sync/status` covers MASTER_SPEC §19.4:
  - sources not processed, and sources changed since their last success (repository HEAD vs. last run; document
    version vs. last `brd-ingest`);
  - entities supported only by obsolete document versions;
  - per-collection expected vs. indexed counts, and open, retrying and failed tasks;
  - unfinished runs and pipelines;
  - schema migrations awaiting (none exist yet);
  - open questions.

## 5. Retrieval (ADR-007 §7) and what we took from Graphiti

From **Graphiti** (`getzep/graphiti`, `search_config.py`, `search_utils.py`, ideas only):

| Graphiti | PKE |
|---|---|
| Hybrid search: cosine similarity + BM25 + BFS from origin nodes | Vector search per collection + Lucene fulltext (entities, sections) + graph expansion with templates from the best hits |
| `rrf(results, rank_const=1)` | `Fusion.rrf`, rank constant **60** (the original RRF value). It flattens the head of each list, so a strong match in one list does not dominate. Revisit with real-model evaluation |
| `maximal_marginal_relevance`, λ = 0.5 | `Fusion.mmr`, λ = 0.5, optional (`mmr: true`) |
| `cross_encoder` reranker | `Reranker` port, no-op by default |
| `node_distance` reranker (1 hop from a centre node) | Template rows are scored by `seed score × 0.8^depth` |
| Default limit 10, min score 0.6, BFS depth 3 | k = 10, no minimum score for fused lists (they are ranked anyway), depth ≤ 3 in templates |
| Edges carry `valid_at`/`invalid_at`; filtering done by search filters | Valid-now by default (vector payload `status`, graph `pke_status`); `asOfSeq` filters on entity versions |

**Pipeline**

1. **Route:** search `CollectionCatalog`, plus collections whose type the question names.
2. **Search:** chosen collections (vector), `pke_entity_text` fulltext and current sections (`pke_section_text`).
3. **Fuse:** RRF, dedup, type / valid-now / as-of filters, optional MMR and reranker.

**Query templates** (`definitions/queries/*.yaml`, meta-schema `query-template.schema.json`):

- The 8 templates are `journey-of-api`, `apis-in-journey`, `impact-of-method`, `implementation-of-requirement`,
  `requirements-of-api`, `shared-logic-of-api`, `log-trail-of-api` and `evidence-of`.
- They are selected by seed type and question keywords; each seed type has defaults.
- Templates pass the same `CypherGuard` at load time and run read-only with the timeout.

**Guarded LLM-Cypher fallback** (skill `write-cypher`), used only when no template applies to any seed:

1. Static `CypherGuard`:
   - no write or administration clause (also lower case; string contents are ignored);
   - procedures only `db.index.{fulltext,vector}.queryNodes`;
   - no namespaced functions (e.g. `apoc.*`), no `IN TRANSACTIONS`, no backtick identifiers, one statement;
   - every label, relationship type and property is registered (schema + system vocabulary);
   - a `LIMIT` is injected or capped (`pke.retrieval.cypher-max-rows`, 200).
2. `EXPLAIN` must succeed.
3. Execution runs in a read-only transaction with a timeout (`pke.retrieval.cypher-timeout`, 5 s).
4. The query and result size are logged.

A rejected query is returned with its reasons and never run.

**Context packer** (`POST /api/context {task, budgetTokens, depth, seeds}`):

- Sections: verified (`HUMAN_VERIFIED`), deterministic (extractors, documents), AI inferences (`INFERRED_AI`), and
  conflicts and open questions.
- Every item cites `source@version#location`, where the location is the cited section's heading when there is one.
- Items are packed by score within the budget (tokens ≈ characters / 4); the dropped count is reported. Also
  returned as Markdown.

## 6. Evaluation

`eval/datasets/*.yaml` (packaged as `classpath:eval/datasets`):

- Each entry: question → expected entities (type + exact properties or text), relationship types, sources.
- Search metrics: recall@k, precision@k, MRR, nDCG@k.
- Context metrics:
  - context recall;
  - graph completeness (expected relationship types in the context);
  - citation correctness (citations that resolve to an existing source version);
  - source coverage;
  - latency and tokens.

Run with `POST /api/eval/runs` (stored as `:EvalRun`) or `mvn -Peval verify` (results in
`pke-engine/target/eval/`).

## Consequences

- Hashing embeddings only match shared words and word parts; synonyms need a real model (an evaluation-gated
  generation switch).
- Neo4j vector indexes of retired generations are not dropped automatically (their records can be deleted); schema
  commands stay with `GraphSchemaInitializer`.
- A timed-out or failed vector write never blocks a knowledge write; it only delays search visibility. `sync/status`
  shows the lag.
