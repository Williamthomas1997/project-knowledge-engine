# Project Knowledge Engine

A Spring Boot + Spring AI + Neo4j engine that builds and maintains an evidence-backed, versioned knowledge graph
of a software project (requirements, code, APIs, tests, history), and serves focused context to people and
coding agents over REST and MCP.

Status: **M6.1, hardening** (ADR-018): calls in reactive code (method references, Mono/Flux and WebClient chains,
lambdas, injected interfaces with one implementation) are resolved, and every unresolved call is listed with a reason;
`SHARES_LOGIC_WITH` links service APIs only and ignores helpers and error-response plumbing (`shared-logic` pipeline
migrates existing edges). Before that: **M6, MCP and security.** An MCP server (streamable HTTP on `/mcp`, plus a STDIO launcher) with 20 tools
over the same services as REST (`docs/mcp-tools.md`); API keys with roles READER, CONTRIBUTOR, SCHEMA_ADMIN and
EVOLUTION_OPERATOR on REST, MCP and actuator, an audit log, and per-profile execution policies
(`definitions/policies/execution.yaml`); journeys captured by interview in chat (`journey-interview` skill); Claude
Code skills in `claude-skills/` and setup in `docs/claude-code-setup.md`. PKE now listens on port **8089**.
Earlier: **M5, history and updates.** State as of any `seq`, snapshots (seq plus every version that produced the
knowledge) and their diffs, commit → knowledge, nightly JSONL export of change events, restore-by-inverse-writes and
data migrations with a checkpoint (`docs/versioning.md`, `docs/recovery.md`); update pipelines `code-on-commit`
(snapshot on `uat`/`pilot`), `brd-update` and `reindex`; `SHARES_LOGIC_WITH` between APIs (utilities excluded);
relationship types proposed by a skill and registered as metadata with a population query, or filed as evolution
requests; the log trail of an API and `POST /api/logs/explain`. Proposals (ADR-015) keep KGP knowledge out of main
until approved. Earlier: **M4, retrieval.** Pluggable vector store (Neo4j vector index by default, Qdrant as second adapter) with
collections derived from the schema, a transactional outbox and reconciliation; hybrid retrieval (catalog routing,
vector + fulltext, RRF, eight graph query templates, a guarded LLM-Cypher fallback); task context in four sections with
citations (`POST /api/context`); an evaluation runner (`mvn -Peval verify`). Earlier: **M3, AI layer.** On top of M1 (schema registry, single graph writer with history) and M2 (documents,
Java code through Git, journeys, routes): provider-neutral AI ports with a cost ledger; Claude-compatible skills
(`definitions/skills/`) and YAML pipelines (`definitions/pipelines/`) with a persisted, resumable executor; BRD
sections rewritten into knowledge cards and reconciled Mem0-style (ADD / UPDATE / NOOP / INVALIDATE, with history);
contradictions become open questions answered over REST; requirement cards linked to the code that implements
them (`INFERRED_AI`, with evidence). Vector retrieval and context generation arrive in M4. See `docs/IMPLEMENTATION_PLAN.md` §13 and ADR-007 for the
milestones, and `docs/reports/` for what each milestone actually delivered.

## Prerequisites

- JDK 21 (Maven 3.9+ optional: use `./mvnw`)
- A Neo4j 5.26 or newer, from either:
  - **Docker** (recommended): the tests start their own throwaway Neo4j with Testcontainers, and
    `docker/docker-compose.yml` runs one for development.
  - **Neo4j Desktop**: a DBMS of version 5.26/2025.x or newer. For the tests, use a separate database (for example
    `pketest`). The tests create it if the DBMS is Enterprise (Desktop is), and **delete all of its data**.
- An OpenAI API key is **not** needed to build, test or run. It is only used with the `openai` profile.
  Embeddings default to a deterministic local model (`hashing-384`); the `openai` profile uses OpenAI embeddings.
- Docker runs the Qdrant contract tests too (`qdrant/qdrant:v1.18.0`). Qdrant is optional at runtime
  (`--pke.vector.provider=qdrant --pke.vector.qdrant.host=...`).
  Tests replay recorded model answers (`pke-engine/src/test/resources/ai-fixtures`); without a model, AI pipeline
  steps report `SKIPPED_NO_MODEL` and write nothing.

## Configure

```bash
cp .env.example .env   # then edit; .env is git-ignored
```

| Variable | Used by | Default |
|---|---|---|
| `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, `NEO4J_DATABASE` | the app | `bolt://localhost:7687`, `neo4j`, empty, `neo4j` |
| `PKE_TEST_NEO4J_MODE` | integration tests | `auto`: Testcontainers if Docker runs, otherwise external |
| `NEO4J_TEST_URI`, `NEO4J_TEST_USERNAME`, `NEO4J_TEST_PASSWORD`, `NEO4J_TEST_DATABASE` | integration tests in `external` mode | database `pketest`; `neo4j` and `system` are refused |
| `OPENAI_API_KEY` | `openai` profile only | none |
| `PKE_PORT` | the app | `8089` |
| `PKE_API_KEY` | `bin/pke-code-on-commit.sh`, `bin/pke-mcp-stdio.sh`, MCP clients, KGP | none (create with `bin/pke-keys.sh`) |
| `PKE_POLICY_PROFILE` | execution policy (`definitions/policies/execution.yaml`) | `dev` (also `test`, `prod`) |

## Build and test

```bash
./mvnw clean verify          # Windows: mvnw.cmd clean verify
```

Unit tests run with surefire and integration tests (`*IT`) with failsafe. If neither Docker nor
`NEO4J_TEST_URI` is available, the integration tests are reported as **skipped**, with the reason. They are
never reported as passed.

Record new model fixtures (synthetic data only; needs `OPENAI_API_KEY`): `./mvnw -Precord-ai verify`. Fixtures
that exist are replayed; requests without one go to the real model and are saved.

Run the integration tests against Neo4j Desktop even when Docker is running:

```bash
PKE_TEST_NEO4J_MODE=external NEO4J_TEST_URI=bolt://localhost:7687 NEO4J_TEST_PASSWORD=... ./mvnw verify
```

## Run

```bash
# Neo4j via Docker (or start your Neo4j Desktop DBMS instead)
docker compose -f docker/docker-compose.yml --env-file .env up -d

# the app (reads NEO4J_* from the environment)
java -jar pke-engine/target/pke-engine-0.1.0-SNAPSHOT.jar
# with OpenAI chat + embeddings
java -jar pke-engine/target/pke-engine-0.1.0-SNAPSHOT.jar --spring.profiles.active=openai
```

The app refuses to start if Neo4j is older than 5.26. At startup it creates its constraints and indexes
(Community features only, ADR-008) and registers the definitions in `definitions/schema/` that are not registered
yet (ADR-010). A restart registers nothing new.

```bash
curl http://localhost:8089/api/system/info
```
```json
{"appVersion":"0.1.0-SNAPSHOT","neo4jVersion":"2026.10.1","neo4jEdition":"community","activeProfiles":[],"chatModelConfigured":false,"chatModel":"none","embeddingModelConfigured":false}
```

Health: `GET /actuator/health` (details, including Neo4j, for authenticated callers only).

### Try it

```bash
# write two methods and a CALLS relationship (natural-key references, so no ids are needed)
curl -X POST localhost:8089/api/knowledge/changes -H 'Content-Type: application/json' -d '{
  "entities": [
    {"type":"JavaMethod","properties":{"repository":"demo","fqcn":"com.demo.TransferService","signature":"transfer(TransferRequest)"},"epistemic":"EXTRACTED_DETERMINISTIC"},
    {"type":"JavaMethod","properties":{"repository":"demo","fqcn":"com.demo.CurrencyConverter","signature":"convert(Money,String)"},"epistemic":"EXTRACTED_DETERMINISTIC"}],
  "relationships": [
    {"type":"CALLS",
     "subject":{"type":"JavaMethod","key":{"repository":"demo","fqcn":"com.demo.TransferService","signature":"transfer(TransferRequest)"}},
     "object":{"type":"JavaMethod","key":{"repository":"demo","fqcn":"com.demo.CurrencyConverter","signature":"convert(Money,String)"}},
     "properties":{"callCount":1},"epistemic":"EXTRACTED_DETERMINISTIC",
     "evidence":[{"sourceArtifactId":"repo:demo","sourceVersion":"c1","location":"TransferService.java:42","extractionMethod":"extract.java"}]}]}'
curl localhost:8089/api/history/events
curl localhost:8089/api/integrity
```

### Ingest a repository and a document

```bash
# register a local Git repository (read-only; analysis reads committed content) and ingest it
curl -X POST localhost:8089/api/sources/repositories -H 'Content-Type: application/json' \
     -d '{"name":"transfer-service","path":"/path/to/repo","serviceName":"transfer-service"}'
curl -X POST 'localhost:8089/api/ingestion/runs?wait=true' -H 'Content-Type: application/json' \
     -d '{"kind":"REPOSITORY","target":"transfer-service","mode":"FULL"}'      # later: "INCREMENTAL"
# upload a document version
curl -F file=@demo/brd/international-transfer-brd.md localhost:8089/api/sources/documents
curl 'localhost:8089/api/sources/sections/search?q=currency'
curl localhost:8089/api/journeys
```

### Turn a BRD into knowledge cards (needs the `openai` profile)

```bash
curl -F file=@demo/brd/international-transfer-brd.md localhost:8089/api/sources/documents
curl -X POST 'localhost:8089/api/pipelines/brd-ingest/executions?wait=true' -H 'Content-Type: application/json' \
     -d '{"documentKey":"doc:international-transfer-brd"}'
curl 'localhost:8089/api/entities?type=KnowledgeCard'
curl 'localhost:8089/api/questions?status=open'            # conflicts to answer:
curl -X POST localhost:8089/api/questions/<id>/answer -H 'Content-Type: application/json' -H 'X-PKE-Actor: william' \
     -d '{"option":"keep-existing","comment":"FR-2 is the approved value"}'
# after code changes: ingest the repository and link every requirement card again
curl -X POST 'localhost:8089/api/pipelines/code-ingest/executions?wait=true' -H 'Content-Type: application/json' \
     -d '{"repository":"transfer-service"}'
curl localhost:8089/api/ai/calls                              # cost ledger
```

### Ask for context (M4)

```bash
curl -X POST localhost:8089/api/context -H 'Content-Type: application/json' \
     -d '{"task":"Modify beneficiary currency validation in the transfer flow.","budgetTokens":2000}'
curl -X POST localhost:8089/api/retrieval/search -H 'Content-Type: application/json' -d '{"query":"daily limit"}'
curl localhost:8089/api/sync/status                    # what is not processed, stale or pending
curl -X POST localhost:8089/api/eval/runs -H 'Content-Type: application/json' -d '{}'
```

### History, snapshots and updates (M5)

```bash
curl -X POST localhost:8089/api/snapshots -H 'Content-Type: application/json' -d '{"label":"before release"}'
bin/pke-code-on-commit.sh transfer-service "" uat     # ingest new commits, derive links, snapshot (tag uat/pilot)
curl 'localhost:8089/api/snapshots/diff?from=<id>&to=<id>'                  # what changed, grouped by type
curl localhost:8089/api/commits/<sha>/knowledge-changes                     # what a commit changed
curl 'localhost:8089/api/history/state?seq=120&type=CALLS'                  # the graph as of a seq
curl -X POST localhost:8089/api/logs/explain -H 'Content-Type: application/json'      -d '{"line":"INFO TransferService - Validating transfer to B-1001"}'  # where did it stop?
curl -X POST 'localhost:8089/api/pipelines/discover-relationships/executions?wait=true' -H 'Content-Type: application/json' -d '{}'
```

Recovery (undo inside the graph, event export, dump/load): [`docs/recovery.md`](docs/recovery.md).

Gateway route exports (the `ocs_t_gateway_api_route` INSERT scripts) are read from
`pke.ingestion.route-export-files`; see `IngestionProperties` for all ingestion settings.

The full endpoint list is in [`docs/api/REST.md`](docs/api/REST.md).

### API keys and MCP (M6)

Every `/api` and `/mcp` call needs a key (`Authorization: Bearer <key>`). The examples above leave it out: add
`-H "Authorization: Bearer $PKE_API_KEY"`. Create keys with the CLI (only the hash is stored; the key is printed once):

```bash
mvn -DskipTests package
export PKE_API_KEY=$(bin/pke-keys.sh create william SCHEMA_ADMIN)   # roles: READER, CONTRIBUTOR, SCHEMA_ADMIN, EVOLUTION_OPERATOR
bin/pke-keys.sh list
bin/pke-keys.sh revoke william
```

Claude Code connects to `http://localhost:8089/mcp` with the key; the `.mcp.json`, the skills in `claude-skills/`
and the KGP key are described in [`docs/claude-code-setup.md`](docs/claude-code-setup.md), and every tool in
[`docs/mcp-tools.md`](docs/mcp-tools.md). Mutating calls are audited (`:AuditEvent`). `/actuator/health` is open
but shows details only to a key.

## Layout

```
pke-spi/       plugin contracts (plain Java, no Spring, enforced)
pke-engine/    the Spring Boot application (packages listed in IMPLEMENTATION_PLAN §1)
pke-plugins/   Level-3 plugins, one sub-module each
definitions/   Git-versioned definitions: meta/ (JSON Schema 2020-12), schema/ (entity + relationship types),
               skills/ (SKILL.md + evals), pipelines/ (YAML), policies/ (relationship and execution policies), queries/ (graph templates)
demo/          synthetic corpus used by all tests: BRD (.md/.docx), transfer service + change commits, QA project, routes
eval/          evaluation datasets (eval/datasets/*.yaml; mvn -Peval verify)
docker/        compose file for a local Neo4j
bin/           pke-code-on-commit.sh, pke-keys.sh (API keys), pke-mcp-stdio.sh (STDIO MCP launcher)
claude-skills/ Claude Code skills (pke-bootstrap, pke-context, pke-questions, pke-journey); copy into .claude/skills/
docs/          spec, plan, ADRs (adr/), REST reference (api/), mcp-tools.md, claude-code-setup.md, versioning.md,
               recovery.md, milestone prompts and reports
```
