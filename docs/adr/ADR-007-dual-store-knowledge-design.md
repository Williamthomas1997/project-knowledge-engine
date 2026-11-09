# ADR-007: Pluggable graph + vector stores, knowledge cards and project dimensions

Status: **accepted for implementation** (architect design, 2026-11-09, agreed with William Thomas)
Supersedes parts of: IMPLEMENTATION_PLAN §5 (graph ↔ index), §8 (retrieval) and §13 (milestone order).
Reference projects: **Mem0** (`mem0ai/mem0`): fact extraction plus ADD/UPDATE/DELETE/NOOP memory updates
over pluggable vector and graph stores. **Graphiti** (`getzep/graphiti`): a temporal knowledge graph with
validity intervals, invalidation instead of deletion, and hybrid search. Study both codebases before you
implement M3/M4 and borrow ideas, not code (we are Java; check licence obligations if any snippet is adapted).

## 1. Stores are pluggable

| Port | Default adapter | Next adapters | Notes |
|---|---|---|---|
| `GraphStore` | Neo4j (Community-compatible) | Memgraph / FalkorDB (Cypher family) | Gremlin stores are out of scope |
| `VectorStore` (our port, built on Spring AI `VectorStore`) | **Neo4j vector index** | **Qdrant** (implement it to prove pluggability) | Spring AI also has PGVector, Oracle, Milvus, Chroma and others |

`pke.vector.provider=neo4j|qdrant`. The application must start with the default and **no extra infrastructure**.
This deliberately changes MASTER_SPEC §2.3: the vector store becomes pluggable on William's request, but a
separate vector DB is still optional.

## 2. Collections, not one big index (Mem0/Cognee pattern)

- Entity types declare `embedding.indexFields` (added to the meta-schema in M1). Each indexed field gets its own
  collection, named `<Type>_<field>` (for example `KnowledgeCard_text`, `ApiOperation_summary`,
  `UserJourney_description`, `JavaMethod_summary`, `LogStatement_template`).
- Every collection is also a graph node, `(:VectorCollection {name, type, field, dimensions, generation, provider})`,
  with the relationships `INDEXES` (to the type definition), `EMBEDDED_WITH` (to `:EmbeddingGeneration`),
  `DERIVED_FROM` and `DEPENDS_ON` (to other collections). This is how collection dependencies are stored.
- Vector record id = UUIDv5(`pke_id`, field, generation). The required payload is `pke_id`, `type`, `collection`,
  `project`, `sourceVersionId`, `epistemic`, `status`, `validAt`. Vector → graph is one `pke_id` hop.
- Two system collections:
  - **`CollectionCatalog`**: one record per collection, describing what it holds and when to search it. The router searches this first.
  - **`GraphSchemaMap`**: one record per entity and relationship type, with its description, endpoints and
    query hints. It is the "architecture section" the LLM uses to understand the graph.

## 3. Graph ↔ vector consistency

The graph is the system of record and vectors are a derived projection. Any graph write that touches an
indexed field also creates `(:IndexTask {op: UPSERT|DELETE, pke_id, field, generation, status})` **in the same
transaction** (an outbox). An `IndexWorker` applies tasks idempotently and retries with backoff.
Ingestion/pipeline status is `COMPLETED` only when its tasks are done, otherwise `PARTIAL` or `FAILED`.
A reconciliation job compares graph and vector records per collection and repairs the differences.
Embedding generations: a new model or new dimensions means a new generation, built in the background,
evaluated, then switched. **Re-embedding never creates knowledge events.**

## 4. Knowledge cards from BRDs (Mem0 pattern)

1. Raw BRD sections are stored (document store plus `:Section` nodes with a fulltext index, for citations).
   **Raw sections are never embedded.**
2. Skill `brd-to-knowledge-cards` rewrites each section into atomic, self-contained, search-friendly
   `KnowledgeCard`s (`text`, `kind`: requirement | rule | constraint | definition | exception, `keyTerms`, source section).
3. Skill `reconcile-knowledge` compares each candidate with similar existing cards (vector top-k in the same
   collection plus their graph neighbours) and returns one of **ADD | UPDATE | INVALIDATE | NOOP | CONFLICT** with
   its reasoning and evidence.
4. Policy: ADD/UPDATE/INVALIDATE/NOOP are applied automatically, with the inferred status preserved. **CONFLICT
   saves nothing.** It creates `(:OpenQuestion)` with both evidences. William answers it in Claude Code chat
   (MCP tool `answer_question`) or through REST. The answer becomes `HUMAN_VERIFIED` evidence and the
   decision is applied. No web UI.
5. Re-running a pipeline on an unchanged section is a no-op (fingerprint check before any LLM call).

## 5. Temporal model (Graphiti)

Assertions and cards carry business validity, `validAt`/`invalidAt`, in addition to the system `seq` history.
Superseded facts are invalidated, never deleted. Retrieval defaults to "currently valid" and supports "as of".

## 6. Project dimensions

| Dimension | Graph content | Sources |
|---|---|---|
| **Journey** | `UserJourney -[:HAS_STEP {order}]-> JourneyStep -[:CALLS_API]-> ApiOperation`; `JourneyStep -[:FEEDS {field}]-> JourneyStep` (data flow) | `JourneySourceAdapter` SPI: **Postman collections** (order, `pm.environment.set` → FEEDS), **Java journey/E2E tests and Gatling simulations** (JavaParser: method order, client calls → endpoint constants), **JMeter `.jmx`** (Transaction Controller = step, HTTP sampler = call, extractors = FEEDS), and **prompt interview** (skill `journey-interview`: the LLM asks questions until the journey is complete) |
| **API identity** | `ApiOperation {httpMethod, pathTemplate, gatewayHeaders{moduleId, subModuleId, serviceId, screenId}}` | Endpoint constants, Postman, controllers |
| **Routing** | **Not stored as graph structure.** `RouteResolver` reads gateway route exports from `dkn-db-config-master` (configured path) or matches Spring `@RequestMapping`s, and produces one `ApiOperation -[:HANDLED_BY]-> JavaMethod` edge whose evidence cites the route file/row | Route SQL/CSV exports, controllers |
| **Logic** | `CALLS`, `INJECTS`, `USES_CONFIGURATION`, `THROWS_ERROR -> ErrorCode`, `READS_FROM/WRITES_TO`; derived `SHARES_LOGIC_WITH` between APIs whose call trees reach the same non-trivial method (`category: technical`) | JavaParser, error-code scripts |
| **Business** | `KnowledgeCard`, `BusinessRequirement`, `BusinessRule`; `IMPLEMENTED_BY` → ApiOperation / JavaMethod / shared-logic method (AI-inferred, evidence required) | BRDs via skills |
| **Log trail** (good to have) | `JavaMethod -[:LOGS {order}]-> LogStatement {level, template, loggerClass}` | JavaParser (code only, no environment config) |

Exclusions for shared logic (configurable): logging, util/utils, config, constants, dto/model/entity mappers,
generated code. A relationship-discovery pipeline may **propose new relationship types**. They are registered
through Level-1 evolution (metadata only) when existing capabilities can populate them.

## 7. Query flow

1. Embed the question, then search `CollectionCatalog` (plus keyword) to choose collections.
2. Search the chosen collections (vector + fulltext, metadata filters, currently valid) to get hits with `pke_id`s.
3. Graph expansion from the hit ids using **templates first**: journey, impact, implementation trace,
   requirement trace, shared logic, log trail. Templates are parameterised Cypher selected by intent and
   schema `queryHints`.
4. **Fallback** only when no template fits: the LLM writes Cypher from `GraphSchemaMap` plus the seed ids. Guards:
   read-only transaction, `EXPLAIN` must succeed, labels/types/properties must be registered, no write
   clauses or procedures outside an allowlist, `LIMIT` enforced, timeout, and the query and result size are logged.
5. Collect evidence (sections, code locations, cards), fuse with RRF, dedupe, and apply the token budget.
6. Context = verified facts / deterministic facts / AI inferences / conflicts and open questions, each with a citation.

## 8. Skills are Claude-compatible

Skills live in `definitions/skills/<id>/SKILL.md` with Claude-style front matter (`name`, `description`) plus our
keys (`version`, `inputSchema`, `outputSchema`, `tools`, `appliesTo`, `evalCases`). The engine's `SkillRunner`
executes them through Spring AI. The same files can be loaded by Claude Code. Initial skills:
`brd-to-knowledge-cards`, `reconcile-knowledge`, `link-requirement-to-implementation`, `journey-interview`,
`summarise-api`, `discover-relationships`, `explain-log-trail`, `pke-bootstrap` (Claude Code side, via MCP).

## 9. AI in tests

No test needs a paid key. A `ReplayChatModel` returns recorded JSON responses keyed by skill id + input hash.
Fixtures are recorded once with the `openai` profile and committed (synthetic data only). A live-model eval
runs only under `-Plive-ai`.
