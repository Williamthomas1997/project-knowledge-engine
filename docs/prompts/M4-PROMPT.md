# M4: Pluggable vector store, hybrid retrieval and context generation (spec Phase 3, ADR-007 §1–§3, §7)

Precondition: M3 accepted. Read ADR-007 §1–§3 and §7, and study **Graphiti** (`getzep/graphiti`) search: hybrid
semantic + BM25 + graph distance, RRF, MMR and reranking. Record what you borrowed in an ADR.

## 1. Vector store port and adapters
- `VectorStorePort` over Spring AI `VectorStore`: collection-aware upsert/delete/search with metadata filters.
  Adapters: **Neo4j vector index** (default) and **Qdrant** (Testcontainers). Choose with `pke.vector.provider`.
  The same contract test suite runs against both adapters.
- `GraphStore` port: extract it now around the M1 writer/reader so that Neo4j is one adapter. Add no second graph
  adapter yet, but no Neo4j types may leak outside the adapter package (add an ArchUnit rule).
- Embeddings: `HashingEmbeddingModel` (deterministic, default, used by tests) and OpenAI under the `openai`
  profile. `:EmbeddingGeneration` handling with no mixed dimensions, background build, evaluate-then-switch.

## 2. Collections and sync
- `<Type>_<field>` collections from `embedding.indexFields`, `:VectorCollection` nodes with `INDEXES`,
  `EMBEDDED_WITH`, `DERIVED_FROM` and `DEPENDS_ON`; the system collections `CollectionCatalog` and `GraphSchemaMap`,
  generated from the registry.
- `:IndexTask` outbox written in the graph transaction; `IndexWorker` with retry and backoff; ingestion status
  `COMPLETED | PARTIAL | FAILED`; a reconciliation job; `GET /api/sync/status` (spec §19.4 items).
- Switch `SimilarCardFinder` (M3) to vector search.

## 3. Retrieval
- Router: catalog search (vector + keyword) → chosen collections.
- Search within collections: vector + Neo4j fulltext, filters (project, type, valid-now or as-of seq).
- **Template library** (`definitions/queries/*.yaml`, parameterised Cypher, intent + queryHints):
  `journey-of-api`, `apis-in-journey`, `impact-of-method`, `implementation-of-requirement`,
  `requirements-of-api`, `shared-logic-of-api`, `log-trail-of-api`, `evidence-of`. Each template has tests.
- **Guarded LLM-Cypher fallback** (ADR-007 §7 step 4), with every guard tested: a write clause is rejected, an
  unknown label is rejected, the LIMIT is injected, the timeout is enforced, and execution is read-only.
- Fusion (RRF, optional MMR), an optional reranker port, and dedup.
- **Context packer**: token budget; sections for verified facts / deterministic facts / AI inferences /
  conflicts and open questions; citations `source@version#location`.
- REST: `POST /api/retrieval/search`, `POST /api/context` (task text, budget, depth), `GET /api/entities/{id}/neighbourhood`.

## 4. Evaluation
`eval/datasets/*.yaml` (question → expected entities/evidence) on the demo corpus. Metrics: recall@k,
precision@k, MRR, nDCG, citation correctness, graph completeness, latency, tokens. Run it with `-Peval` and
`POST /api/eval/runs`. Include the spec §10.2 question and the §20 "modify beneficiary currency validation"
style task adapted to the demo.

## 5. Tests
Neo4j and Qdrant adapters pass the same contract tests; a graph write with the vector store down → `PARTIAL`,
then completed by the worker; invalidating an entity deletes its vectors; a new embedding generation doesn't
create knowledge events; the demo transfer-flow question returns graph plus document evidence with correct
citations; context stays within budget; eval metrics are reported in the M4 report (real numbers).
