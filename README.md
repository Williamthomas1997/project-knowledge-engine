# Project Knowledge Engine

A Spring Boot + Spring AI + Neo4j engine that builds and maintains an evidence-backed, versioned knowledge graph
of a software project (requirements, code, APIs, tests, history), and serves focused context to people and
coding agents over REST and MCP.

Status: **M1, foundation.** The schema registry (YAML definitions validated by JSON Schema 2020-12), the single
graph writer (entities, relationships, evidence, versions and change events in one transaction) and REST APIs are
done. Ingestion, retrieval and AI arrive in M2–M4. See `docs/IMPLEMENTATION_PLAN.md` §13 and ADR-007 for the
milestones, and `docs/reports/` for what each milestone actually delivered.

## Prerequisites

- JDK 21 (Maven 3.9+ optional: use `./mvnw`)
- A Neo4j 5.26 or newer, from either:
  - **Docker** (recommended): the tests start their own throwaway Neo4j with Testcontainers, and
    `docker/docker-compose.yml` runs one for development.
  - **Neo4j Desktop**: a DBMS of version 5.26/2025.x or newer. For the tests, use a separate database (for example
    `pketest`). The tests create it if the DBMS is Enterprise (Desktop is), and **delete all of its data**.
- An OpenAI API key is **not** needed to build, test or run. It is only used with the `openai` profile.

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

## Build and test

```bash
./mvnw clean verify          # Windows: mvnw.cmd clean verify
```

Unit tests run with surefire and integration tests (`*IT`) with failsafe. If neither Docker nor
`NEO4J_TEST_URI` is available, the integration tests are reported as **skipped**, with the reason. They are
never reported as passed.

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
curl http://localhost:8080/api/system/info
```
```json
{"appVersion":"0.1.0-SNAPSHOT","neo4jVersion":"2026.10.1","neo4jEdition":"community","activeProfiles":[],"chatModelConfigured":false,"embeddingModelConfigured":false}
```

Health: `GET /actuator/health` (includes Neo4j).

### Try it

```bash
# write two methods and a CALLS relationship (natural-key references, so no ids are needed)
curl -X POST localhost:8080/api/knowledge/changes -H 'Content-Type: application/json' -d '{
  "entities": [
    {"type":"JavaMethod","properties":{"repository":"demo","fqcn":"com.demo.TransferService","signature":"transfer(TransferRequest)"},"epistemic":"EXTRACTED_DETERMINISTIC"},
    {"type":"JavaMethod","properties":{"repository":"demo","fqcn":"com.demo.CurrencyConverter","signature":"convert(Money,String)"},"epistemic":"EXTRACTED_DETERMINISTIC"}],
  "relationships": [
    {"type":"CALLS",
     "subject":{"type":"JavaMethod","key":{"repository":"demo","fqcn":"com.demo.TransferService","signature":"transfer(TransferRequest)"}},
     "object":{"type":"JavaMethod","key":{"repository":"demo","fqcn":"com.demo.CurrencyConverter","signature":"convert(Money,String)"}},
     "properties":{"callCount":1},"epistemic":"EXTRACTED_DETERMINISTIC",
     "evidence":[{"sourceArtifactId":"repo:demo","sourceVersion":"c1","location":"TransferService.java:42","extractionMethod":"extract.java"}]}]}'
curl localhost:8080/api/history/events
curl localhost:8080/api/integrity
```

The full endpoint list is in [`docs/api/REST.md`](docs/api/REST.md). **There is no authentication before M6, and
`/actuator/health` shows details.** Keep the service on localhost.

## Layout

```
pke-spi/       plugin contracts (plain Java, no Spring, enforced)
pke-engine/    the Spring Boot application (packages listed in IMPLEMENTATION_PLAN §1)
pke-plugins/   Level-3 plugins, one sub-module each
definitions/   Git-versioned definitions: meta/ (JSON Schema 2020-12), schema/ (entity + relationship types)
demo/ eval/    synthetic demo project and evaluation datasets (M2, M3)
docker/        compose file for a local Neo4j
docs/          spec, plan, ADRs (adr/), REST reference (api/), milestone prompts and reports
```
