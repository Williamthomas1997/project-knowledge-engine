# Project Knowledge Engine — Implementation Plan (v0.1)

Status: **accepted, M0 done** · Author: Claude (with William Thomas) · Date: 2026-10-19, revised 2026-11-05
Location: `C:\Code2025\Dukhan_Bank\BitBucket\project-knowledge-engine`

This is the Step 1 + Step 2 output of the master specification: what the environment
looks like, the architecture I intend to build, the decisions behind it, the risks, and the
milestones. Nothing here is implemented yet.

---

## 0. Environment inspection (actual findings)

| Item | Finding | Consequence |
|---|---|---|
| Existing knowledge-engine code | None in `BitBucket` (40+ `dkn-*` service repos, docs, `dkn-db-config-master`) | New project, created alongside the services |
| House baseline | Newer services use **Spring Boot 4.0.4 + Java 21**, Maven, `mvn clean install` | Same Java 21 + Maven baseline. Boot moved to **4.1.2**, the version Spring AI 2.0.2 is built against (ADR-000) |
| Neo4j | Neo4j Desktop 2 with **Enterprise 2026.09.0**; Docker Desktop 29.4 | Tests: Testcontainers `neo4j:2026.10.1-community`, or Desktop `pketest` DB |
| Build machine (2026-11-05) | Windows: JDK 21.0.11, Maven 3.9.11, Maven Central and Docker Hub reachable | **Claude builds and tests directly.** R1 resolved |
| Linux shell on your PC | Java 11, no Maven, Maven Central blocked | Can't build there either |
| Real Dukhan code/BRDs | Present locally | Read-only smoke checks; OpenAI use approved (R7). Tests use the synthetic demo corpus |

All library versions were verified in M0 and are recorded in `docs/adr/ADR-000-technology-versions.md`.

---

## 1. Architecture at a glance

Modular monolith, one deployable, three Maven modules:

```
project-knowledge-engine/
├── pke-spi/            Plugin contracts only (no Spring Boot). What generated code implements.
├── pke-engine/         Spring Boot app: all core components, REST, MCP
├── pke-plugins/        Level-3 generated/handwritten plugins (one sub-module each)
├── definitions/        Git-versioned metadata: schemas, relationships, pipelines, skills, policies, prompts
├── demo/               Synthetic "international transfer" project used by the end-to-end demo
├── eval/               Retrieval + extraction evaluation datasets
├── docs/               Architecture, ADRs, guides
└── docker/             compose for Neo4j (+ optional sandbox Neo4j)
```

Package boundaries inside `pke-engine` (enforced by ArchUnit tests, not just convention):

| Package | Responsibility | May depend on |
|---|---|---|
| `kernel` | IDs, epistemic status, value types, clock, errors | — |
| `schema` | Type/relationship/property definitions, validation, promotion, migrations | kernel |
| `graph` | **The only writer to Neo4j knowledge**: entities, assertions, evidence, versions, history events | kernel, schema |
| `history` | Event log reads, historical reconstruction, snapshots, diffs | kernel, graph |
| `source` | Document store, source registry, source versions, Git (JGit) | kernel |
| `ingest` | Source adapters (Tika, Markdown, Java, OpenAPI, SQL DDL, YAML/properties) → common `ParsedArtifact` | source, spi |
| `extract` | Deterministic extractors + AI skills → `CandidateKnowledge` | ingest, schema, ai |
| `reconcile` | Entity resolution, relationship policies, conflict detection | graph, schema |
| `retrieval` | Chunk index, fulltext, vector, graph expansion, fusion, context packing, evaluation | graph, source, ai |
| `ai` | Spring AI wrappers: chat, embeddings, structured output, cost ledger; provider-neutral ports | kernel |
| `pipeline` | Pipeline + skill registry, executor, step state, retries | all of the above via SPI |
| `evolution` | Evolution requests, planning, worktrees, sandbox build/test, activation, recovery | pipeline, schema, source |
| `api.rest` / `api.mcp` | Thin adapters over application services | application services only |
| `ops` | Metrics, sync-status reports, backups | read-only on others |

REST and MCP call the **same application services** (`KnowledgeQueryService`,
`ContextService`, `IngestionService`, `SchemaService`, `EvolutionService` …). No business
logic in controllers or MCP tool classes.

---

## 2. Graph representation (ADR-001) — hybrid native + reified

The core tension: native Neo4j labels/types give fast traversal, but relationships in Neo4j
can't point at evidence nodes, and dynamic types risk Cypher injection.

**Decision**

1. **Entities** are nodes with a fixed system label `:Entity` **plus** the registered type as a
   native label (`:Entity:BusinessRequirement`). Labels are only ever taken from the active
   schema registry and must match `^[A-Z][A-Za-z0-9]{1,63}$`; they are passed as parameters
   using Cypher dynamic labels (`SET n:$($label)`), never string-concatenated.
2. **Relationships** exist twice, deliberately:
   - a **native relationship** (`(a)-[:CALLS {relId, status, epistemic}]->(b)`) for traversal,
     present only while the relationship is *currently valid*;
   - an **`:Assertion` node** (the logical relationship) holding identity, version chain and
     evidence: `(:Assertion)-[:SUBJECT]->(a)`, `-[:OBJECT]->(b)`, `-[:SUPPORTED_BY]->(:Evidence)`.
     Assertions are never deleted; they become `INVALID`/`SUPERSEDED`.
   Both are written in the same transaction by `graph`; a consistency check verifies
   "every current assertion has exactly one native edge and vice versa".
3. **System properties** use a `pke_` prefix (`pke_id`, `pke_type`, `pke_schemaVersion`,
   `pke_status`, `pke_epistemic`, `pke_seq`); domain properties use their registered names.
   Property names are also validated against the registry.
4. Infrastructure node labels (`:Evidence`, `:SourceArtifact`, `:SourceVersion`, `:Chunk`,
   `:ChangeEvent`, `:EntityVersion`, `:AssertionVersion`, `:Snapshot`, `:SchemaDefinition`,
   `:PipelineExecution` …) are fixed and owned by the engine — they are engine concepts, not domain.

Trade-off accepted: write amplification (edge + assertion) in exchange for provenance on
relationships and cheap traversal. Requires Neo4j ≥ 5.26 for dynamic labels/types (verified in M0 by `Neo4jDynamicLabelIT`; the app refuses to start on older servers).

---

## 3. Core data model

> **Implemented in M1** (see ADR-008 Community features, ADR-009 single writer and write protocol, ADR-010 definition
> format and identity). Differences from the sketch below: evidence stores `sourceArtifactId`/`sourceVersion` as
> properties until source nodes exist (M2 adds `-[:FROM]->(:SourceVersion)`); assertions also carry Graphiti-style
> `pke_validAt`/`pke_invalidAt` (ADR-007 §5); `:Conflict` and `:Snapshot` arrive in M3/M5.


```
(:SourceArtifact {pke_id, kind, logicalKey})-[:HAS_VERSION]->(:SourceVersion {contentHash, gitCommit?, docVersion, blobRef, ingestedAt})
(:SourceVersion)-[:HAS_CHUNK]->(:Chunk {chunkId, section, page, lineStart, lineEnd, text, contentHash, chunkerVersion, emb_g<N>})
(:Evidence {extractionMethod, pipelineVersion, skillVersion, model?, excerpt|structuredRef, location, verification, confidence?})-[:FROM]->(:SourceVersion)
(:Entity:<Type>)-[:SUPPORTED_BY]->(:Evidence)
(:Assertion {relId, relType, pke_epistemic, status})-[:SUBJECT]->(:Entity) ; -[:OBJECT]->(:Entity) ; -[:SUPPORTED_BY]->(:Evidence)
(:Entity)-[:HAS_VERSION]->(:EntityVersion {version, props, validFromSeq, validToSeq})
(:Assertion)-[:HAS_VERSION]->(:AssertionVersion {...})
(:ChangeEvent {seq, type, at, actor, executionId, sourceVersionId, details})
(:Snapshot {id, seq, schemaSet, pipelineSet, skillSet, sources, modelConfig, indexGeneration, appRelease})
(:Conflict {status, policy})-[:BETWEEN]->(:Evidence)
```

**Epistemic status** (engine semantics, so a closed enum is correct here):
`OBSERVED`, `EXTRACTED_DETERMINISTIC`, `INFERRED_AI`, `HUMAN_VERIFIED`, `CONTRADICTED`, `SUPERSEDED`.
Confidence is stored as *uncalibrated model-reported metadata* and labelled as such.

---

## 4. Identity and entity resolution (ADR-002)

- `pke_id` = UUIDv5(type-namespace, canonical natural key) when the schema declares a natural
  key; otherwise a generated UUIDv7 plus alias matching.
- Schema declares the identity strategy per type, e.g.
  `JavaMethod: naturalKey [repo, fqcn, erasedSignature]`, `ApiOperation: [service, method, pathTemplate]`,
  `BusinessRequirement: [sourceArtifact, requirementId]` falling back to generated + resolution.
- Content hash detects change; it is **never** identity.
- Renames/moves: Java extractor uses Git rename detection + body-hash similarity in the same
  commit → `ENTITY_RENAMED` event, old key kept as `:Alias`, `pke_id` preserved.
- Resolution ladder: exact id → canonical key → alias → fulltext candidate → (optional) AI
  judgement. AI judgement can only produce a **merge proposal**; auto-merge is allowed only by
  an explicit per-type policy. Merges and splits are events and fully reversible from versions.
- Relationship id = UUIDv5(relType, subjectId, objectId, discriminator). If endpoints change it
  is a *new* assertion and the old one becomes `SUPERSEDED` with a `REPLACED_BY` link.

---

## 5. History, consistency and snapshots (ADR-003)

**No separate event store and no Kafka.** Neo4j is ACID; the graph mutation, the version node
and the `:ChangeEvent` are written **in one transaction**. If any part fails, all fail. That
removes the outbox problem for graph ↔ history.

- Ordering: a global `seq` taken from a single `:Sequence` node inside the write transaction
  (serialises knowledge writes — fine for one-team scale; revisit if throughput demands).
- Versions use `validFromSeq`/`validToSeq` (plus wall-clock). State *as of seq S* =
  versions where `validFromSeq ≤ S < validToSeq`. This is the reconstruction algorithm;
  events give the "why", versions give the "what".
- Idempotency: every write carries `(sourceVersionId, extractorVersion)`; an unchanged
  re-run produces a no-op diff and **no** events.
- Durability beyond Neo4j: nightly JSONL export of `ChangeEvent`s to the document store, plus
  documented Neo4j dump/restore.
- **Snapshot** = immutable record of `seq` + active schema/pipeline/skill versions + source
  versions (Git commits *and* non-Git document versions) + model/embedding config + index
  generation + app release (`git describe`). Snapshot diff = set difference of versions valid
  at the two `seq`s, grouped by type, with evidence.

**Graph ↔ vector store** (superseded by ADR-007 §1–§3, binding from M4): the graph is the system of
record and vectors are a derived, pluggable projection (`pke.vector.provider=neo4j|qdrant`). Each embedded
field has its own collection (`<Type>_<field>`, declared by `embedding.indexFields`, stored since M1), and
every collection is also a `(:VectorCollection)` node. A graph write that touches an indexed field creates an
`(:IndexTask)` in the same transaction (outbox); an `IndexWorker` applies tasks idempotently with retries.
Ingestion is `COMPLETED` only when its tasks are done, otherwise `PARTIAL`/`FAILED`; a reconciliation job
repairs differences; embedding generations are switched after evaluation, and re-embedding never creates
knowledge events. **Raw document sections are never embedded** (ADR-007 §4): they are stored as `:Section`
nodes with a fulltext index (M2) and BRDs are rewritten into knowledge cards (M3).

---

## 6. Schema registry and evolution (ADR-004)

- Definitions are YAML in `definitions/schema/**`, validated against a JSON Schema 2020-12
  **meta-schema** (`definitions/meta/*.schema.json`) — entity types, relationship types,
  properties, identity strategy, evidence requirements, discovery/approval policy, temporal
  behaviour, query hints, category, lifecycle.
- Promotion flow: Git → `SchemaService.validate` → compatibility check against active version
  → impact query (count affected nodes/edges) → migration plan → dry run in sandbox →
  activate → integrity check → `SCHEMA_REGISTERED/MIGRATED` event. Active set stored as
  `:SchemaDefinition` nodes; Git is the source, the registry decides what's live.
- Migrations are declarative ops (`addOptional`, `addRequired(default|derive)`, `rename`,
  `deprecate`, `retype`, `recompute(pipeline)`) with a declared expected effect and
  a checkpoint (snapshot) before non-reversible steps. Retired schemas stay readable for history.
- **A new relationship type that existing capabilities can handle needs zero Java changes**:
  register YAML → generic extractors/policies/retrieval pick it up via its declared
  `extraction` block (e.g. `skill: map-feature-to-control`).

---

## 7. Ingestion and extraction

Implemented in M2 (ADR-011 sources and incremental ingestion, ADR-012 API identity, journeys and routes).
No LLM is used; everything below is deterministic.

| Source | Parser (deterministic first) | Status |
|---|---|---|
| PDF / DOCX / XLSX / PPTX / HTML | Apache Tika 4 (pdf, microsoft, html, text modules): headings, paragraphs, table rows, PDF pages → `:Section` | M2 |
| Markdown / text | CommonMark: one section per heading with its heading path | M2 |
| Scanned PDF | `OcrProvider` extension point; without one the version is stored as `OCR_REQUIRED` | M2 (no OCR engine) |
| Java | JavaParser + symbol solver, with deterministic static-dispatch fallback: packages, types, methods, imports, inheritance, `CALLS` (unresolved calls listed, never guessed), annotations, Spring endpoints → service `ApiOperation`, `INJECTS`, `USES_CONFIGURATION`, Kafka, JPA `MAPS_TO`/`READS_FROM`/`WRITES_TO`, `TESTED_BY`, `THROWS_ERROR`, `LOGS` | M2 |
| Release SQL (error configuration) | own quote-aware `INSERT` reader (`OCS_T_ERROR_CONFIG.OCS_ERR_CODE`, configurable) | M2 |
| Journeys (ADR-007 §6) | `JourneySourceAdapter` SPI: Postman, Java journeys + Gatling (static interpretation), JMeter, TestNG suites | M2; prompt interview in M6 |
| Gateway routes | `RouteResolver`: route export (`ocs_t_gateway_api_route` INSERTs) then Spring mapping; no route nodes | M2 |
| OpenAPI / Swagger, `application*.yml` | swagger-parser, SnakeYAML | later |
| Git | JGit: commits (as source versions), diffs, renames; analysis reads committed content only | M2 |

Incremental: since the last processed commit, changed Java files plus the reverse-dependency set (files whose
current relationships point into changed types or methods). Each scope (file) is re-extracted as a whole and
retracted: relationships it no longer produces are invalidated with history. Renames keep `pke_id` through
`:Alias` nodes. Unchanged content writes nothing (scoped evidence, ADR-011).

---

## 8. Retrieval and context generation (ADR-005, refined by ADR-007 §7)

> Implemented in M4 (ADR-014). ADR-007 §7 is binding from M4: search the `CollectionCatalog` first to choose collections, search them
> (vector + fulltext, currently valid), expand from the hits with **parameterised query templates** (journey,
> impact, implementation trace, requirement trace, shared logic, log trail) and fall back to guarded,
> read-only LLM-written Cypher only when no template fits. The original sketch follows.

1. **Query understanding** (AI optional): extract terms and candidate entity types.
2. **Entity linking**: Neo4j fulltext over names/aliases + vector over entity summaries.
3. **Chunk search**: fulltext (Lucene BM25) + vector (Neo4j vector index), metadata filters
   (project, source kind, version, as-of snapshot).
4. **Graph expansion**: typed, category-aware traversal with per-relationship weights from
   schema `queryHints`; business and technical categories expanded separately so shared
   utilities don't create false business links; depth + node budget.
5. **Fusion**: reciprocal-rank fusion; optional reranker port.
6. **Context packer**: dedupe, token budget, sections *Verified facts / Deterministic facts /
   AI inferences / Conflicts & stale evidence*, every item with a citation
   (`source@version#location`).

Index generations: `emb_g<N>` property + vector index per (model, dimensions); new generation
built in background, evaluated, then switched; re-embedding does **not** create knowledge events.

**Runs without paid APIs**: a deterministic local `HashingEmbeddingModel` (feature hashing)
is the default for dev/tests; OpenAI (or Azure OpenAI / Ollama) is enabled by profile. AI
steps without a configured model report `SKIPPED_NO_MODEL` instead of failing silently.

**Evaluation**: `eval/*.yaml` question → expected evidence/entities; metrics recall@k,
precision@k, MRR, nDCG, citation correctness, graph completeness, latency, tokens. Runs as a
Maven profile and as `POST /api/eval/runs`.

---

## 9. Skills, pipelines, policies

Implemented in M3 as described in ADR-013 (which refines the bullets below): skills are Claude-compatible
`SKILL.md` files run by `SkillRunner` through the provider-neutral `ChatPort`; tools are called by steps on the
skill's behalf (declared tools only); policies are `AUTO_ACCEPT | ACCEPT_AS_INFERRED | QUESTION | REJECT`, with
`QUESTION` replacing `REVIEW` (conflicts become `:OpenQuestion`s, ADR-007 §4).

- **Skill** = Markdown + YAML front matter (id, version, inputSchema, outputSchema, tools,
  applicable types, eval cases). Executed by `SkillRunner` through Spring AI `ChatClient`
  with structured output; output validated against `outputSchema` before becoming
  `CandidateKnowledge`. A skill cannot do anything its declared tools don't allow.
- **Pipeline** = YAML list of steps referencing registered capabilities (`PipelineStep` SPI beans,
  e.g. `parse.tika`, `extract.java`, `skill:extract-requirements`, `reconcile`, `index.chunks`).
  In-process executor with persisted `:PipelineExecution/:StepExecution` (status, attempts,
  timeouts, idempotency key, cancellation, resume-from-step). No Temporal/Camunda until needed;
  the executor sits behind an interface so one can be swapped in.
- **Relationship policy** YAML: by relationship type × epistemic source × impact →
  `AUTO_ACCEPT`, `ACCEPT_AS_INFERRED`, `REVIEW`, `REJECT`; conflict policy per source
  authority (e.g. code authoritative for `CALLS`, BRD authoritative for requirement text,
  neither proves `IMPLEMENTS_REQUIREMENT`). Default minimises manual review.

---

## 10. Autonomous evolution (ADR-006)

```
request → classify (L1 metadata | L2 workflow | L3 code) → plan (uses the engine's own
knowledge of itself — the engine ingests its own repo) → change set
  L1/L2: registry change → validate → dry-run on sandbox Neo4j + demo corpus → integrity + eval → activate
  L3:    git worktree evolution/<id> → CodeGenerationAgent writes only under pke-plugins/** and definitions/**
         → mvn verify in an isolated process (no network, CPU/mem/time limits) → ArchUnit + eval
         → deploy to test profile → validate → activate per policy → snapshot
failure → keep diagnostics, revert worktree/registry, restore from pre-change snapshot,
          record fingerprint; identical request+plan is blocked from re-applying unless the
          failure was classified transient or inputs changed
```

- `CodeGenerationAgent` is a port: built-in Spring AI tool loop, or an external headless agent
  (e.g. Claude Code in the worktree). Either way it gets a restricted tool set.
- Autonomy policy per environment: `dev` auto-activate, `test` auto-activate after green
  validation, `prod` activation requires approval (configurable). MCP can submit evolution
  requests but cannot bypass the policy.

---

## 11. Risks and open issues

| # | Risk | Mitigation / decision needed |
|---|---|---|
| ~~R1~~ | ~~Can't build or test from the sandbox~~ | **Resolved 2026-11-05**: Claude builds and tests on the Windows machine (Maven Central + Docker reachable) |
| ~~R2~~ | ~~Versions unverified~~ | **Resolved in M0**: Boot 4.1.2, Spring AI 2.0.2, driver 6.1.0, Testcontainers 2.0.5 (ADR-000) |
| R3 | Neo4j Community lacks multi-database, online backup, existence/type constraints | Community-only features (ADR-008); required properties enforced by the validator; sandbox = second instance; dump-based backups |
| R4 | Global sequence serialises writes | Accepted and implemented (ADR-009); `ConcurrentWritesIT` proves gap-free ordered `seq` under 8 writers. Batch writes per pipeline step; revisit with measurements |
| R5 | LLM extraction quality / false business links | Evidence-required policies, inferred status, evaluation sets, review gate for high-impact types |
| R6 | Self-modifying code safety | Worktree isolation, path allowlist, no-network sandboxed build, policy-gated activation, audit events |
| ~~R7~~ | ~~Bank data to an external LLM~~ | **Decided 2026-11-09**: OpenAI is approved for real Dukhan repos and documents. Automated tests keep using the synthetic `demo/` corpus so they stay reproducible and need no key |
| R8 | Scope (7 phases) is large | Working vertical slice at every milestone; demo scenario grows with each one |

**Reversible defaults I've chosen** (tell me if any is wrong): project name
`project-knowledge-engine`; group `com.omnicore.pke`; Spring Boot 4.1.2 / Java 21 (ADR-000); single Neo4j DB for graph + vectors + history; OpenAI behind a profile,
hashing embeddings by default; MCP over streamable HTTP plus STDIO; API-key auth for v1 with
roles `READER`, `CONTRIBUTOR`, `SCHEMA_ADMIN`, `EVOLUTION_OPERATOR`.

---

## 12. Testing strategy

- **Unit**: identity, schema validation, meta-schema, policy engine, versioning maths,
  diff, context packer, pipeline state machine.
- **Integration (Testcontainers Neo4j)**: persistence, dynamic labels, assertions/edges
  consistency, history atomicity, snapshots, fulltext/vector search, JGit on a temp repo,
  REST (MockMvc), MCP tool calls.
- **Failure**: index failure after graph success → `PARTIAL`; history write failure → whole tx
  rolls back; re-ingest unchanged → zero events; source version replaced; migration failure →
  restore; generated code fails compile → recovery recorded and not re-applied; interrupted
  pipeline resumes; duplicates → merge proposal; contradictory sources → `:Conflict`.
- **Architecture**: ArchUnit package rules; "only `graph` writes knowledge".
- **AI evaluation**: deterministic fixtures with recorded model responses for CI; live-model
  eval behind a profile.
- No test may need a paid API.

---

## 13. Milestones

Milestone order since ADR-007 (2026-11-09); the prompts are `docs/prompts/M<n>-PROMPT.md`.

| M | Scope | Status |
|---|---|---|
| **M0** | Build skeleton, verified versions, Neo4j compose + Testcontainers, ArchUnit | done |
| **M1** | Foundation: schema registry, single graph writer, history in one transaction, REST | done |
| **M2** | Deterministic ingestion: documents, Java code, Git, journeys (Postman / Java tests / Gatling / JMeter), routes, demo corpus | done |
| **M3** | AI layer: Spring AI wiring, skills and pipelines runtime, BRD → knowledge cards, Mem0-style reconcile, conflict questions, requirement → implementation links | done (ADR-013) |
| **M4** | Pluggable vector store (Neo4j + Qdrant), collections, outbox sync, hybrid retrieval, templates and guarded Cypher fallback, context generation, evaluation | done (ADR-014) |
| **M5** | History queries, snapshots and diffs, incremental update pipelines, relationship discovery, log trail, recovery | next |
| **M6** | MCP server, security, Claude Code skills (bootstrap, journey interview, answer questions) | |
| **M7** | Autonomous evolution (L1/L2/L3), isolated build/test, activation, recovery, full demo scenario | |

Each milestone ends with: tests actually executed (with results), docs + ADRs updated, a
status report (implemented / files / tests / migrations / config / known issues / next).
