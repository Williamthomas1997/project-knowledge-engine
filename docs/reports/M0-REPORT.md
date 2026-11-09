# M0 report: build skeleton and verified versions

Date: 2026-11-05 · Implemented by: Claude (directly, no longer via Cursor)

## 1. Summary

The Maven multi-module skeleton (`pke-spi`, `pke-engine`, `pke-plugins`) builds and its tests pass on Java 21,
Spring Boot 4.1.2 and Spring AI 2.0.2. The app connects to Neo4j through `Neo4jClient`, logs the server version,
and refuses to start below 5.26. It serves `GET /api/system/info` and starts with no OpenAI key. The
dynamic-label/type capability that ADR-001 relies on is proven against a real Neo4j 2026.10.1. Package boundaries
are enforced by ArchUnit, and `pke-spi` is kept Spring-free by the enforcer plugin. No knowledge features yet,
as planned.

## 2. Versions chosen

See [`docs/adr/ADR-000-technology-versions.md`](../adr/ADR-000-technology-versions.md). Headline:
Boot **4.1.2** (not 4.0.x as the plan assumed: Spring AI 2.0.2 is built against 4.1.2), Spring AI **2.0.2**,
Neo4j driver 6.1.0, Testcontainers 2.0.5 and JUnit 6.0.3 (all Boot-managed), ArchUnit 1.5.1, test/compose image
`neo4j:2026.10.1-community`.

## 3. Environment

| Item | Value |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| Java | `java version "21.0.11" 2026-04-21 LTS` (Oracle HotSpot) |
| Maven | Apache Maven 3.9.11 (plus the wrapper `./mvnw`, pinned to 3.9.11) |
| Docker | Yes: Docker Desktop 29.4.1 (linux engine). It was started for this run. |
| Neo4j used by tests | 2026.10.1, community edition (Testcontainers) |
| Neo4j used for app start check | 2026.10.1, community edition (`docker/docker-compose.yml`) |
| Test DB option | **Testcontainers** (`PKE_TEST_NEO4J_MODE=auto` with Docker running) |

## 4. Files created

```
.gitattributes  .gitignore  .env.example  README.md  AGENTS.md (rewritten)  mvnw  mvnw.cmd  .mvn/wrapper/
pom.xml                                   parent; Boot 4.1.2 parent; Spring AI BOM; pinned versions; failsafe
pke-spi/pom.xml                           enforcer: no org.springframework*
pke-spi/src/main/java/.../spi/PkePlugin.java
pke-spi/src/test/java/.../spi/PkePluginTest.java
pke-plugins/pom.xml                       empty aggregator
pke-engine/pom.xml
pke-engine/src/main/java/com/omnicore/pke/engine/
  PkeApplication.java
  {kernel,schema,graph,history,source,ingest,extract,reconcile,retrieval,ai,pipeline,evolution,api/rest,api/mcp,ops}/package-info.java
  kernel/CypherIdentifiers.java, kernel/InvalidIdentifierException.java
  ai/ModelAvailability.java
  ops/Neo4jCapabilityCheck.java, ops/Neo4jServerInfo.java, ops/SystemInfo.java, ops/SystemInfoService.java
  api/rest/SystemController.java
pke-engine/src/main/resources/application.yml, application-openai.yml
pke-engine/src/test/java/com/omnicore/pke/engine/
  ArchitectureTest.java, SystemInfoIT.java
  graph/Neo4jDynamicLabelIT.java, kernel/CypherIdentifiersTest.java, ops/Neo4jServerInfoTest.java
  support/Neo4jTestDatabase.java, support/RequiresNeo4j.java
definitions/ demo/ eval/ docker/          README.md each; docker/docker-compose.yml
docs/adr/ADR-000-technology-versions.md
docs/reports/M0-REPORT.md
docs/MASTER_SPEC.md                       restored: the stored copy was cut off at §10 ("<!-- PART2 -->")
docs/IMPLEMENTATION_PLAN.md               updated: versions, environment, R1/R2 resolved
.cursor/rules/00-pke-working-agreement.mdc  report path updated
```

## 5. Build and test results

Command: `./mvnw -B clean verify` (repo root, Docker running)

```
[INFO] Project Knowledge Engine ........................... SUCCESS [  0.376 s]
[INFO] PKE SPI ............................................ SUCCESS [  2.560 s]
[INFO] PKE Engine ......................................... SUCCESS [ 35.024 s]
[INFO] PKE Plugins ........................................ SUCCESS [  0.008 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  38.310 s
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire (`CypherIdentifiersTest` 37, `Neo4jServerInfoTest` 9, `ArchitectureTest` 4) | 50 | 0 | 0 | 0 |
| pke-engine | failsafe (`Neo4jDynamicLabelIT` 2, `SystemInfoIT` 2) | 4 | 0 | 0 | 0 |

No tests were skipped in this run. **Skip path also verified**: with `PKE_TEST_NEO4J_MODE=none`, failsafe reports
`Tests run: 4, Failures: 0, Errors: 0, Skipped: 4` with the message
`Neo4j integration tests disabled (PKE_TEST_NEO4J_MODE=none).`

One failure was found and fixed during the milestone: `Neo4jDynamicLabelIT` initially compared labels using
`Value::toString`, which includes quotes. The test was wrong, not Neo4j, and now uses `Value::asString`.

## 6. App start check

```bash
NEO4J_PASSWORD=<local> docker compose -f docker/docker-compose.yml up -d
java -jar pke-engine/target/pke-engine-0.1.0-SNAPSHOT.jar --server.port=8089   # OPENAI_API_KEY unset
curl http://localhost:8089/api/system/info
```
```json
{"appVersion":"0.1.0-SNAPSHOT","neo4jVersion":"2026.10.1","neo4jEdition":"community","activeProfiles":[],"chatModelConfigured":false,"embeddingModelConfigured":false}
```
Log: `Connected to Neo4j 2026.10.1 (community edition)`. `/actuator/health` returned UP, with Neo4j UP. The app and
the container were stopped afterwards.

## 7. Deviations from the plan

1. **Spring Boot 4.1.2 instead of 4.0.x.** Spring AI 2.0.2 (latest GA) is built against Boot 4.1.2, and 4.1.2 is
   the latest Boot GA.
2. **`spring-boot-starter-webmvc`** instead of `spring-boot-starter-web`, the Boot 4 name.
3. **Test Neo4j selection has an explicit switch** (`PKE_TEST_NEO4J_MODE=auto|testcontainers|external|none`) on
   top of the planned auto-detection. The reason: Testcontainers ignores a bogus `DOCKER_HOST`, so the "no Neo4j → skipped"
   path could not otherwise be tested. The switch also lets you force Neo4j Desktop while Docker runs.
4. In external mode the tests run `CREATE DATABASE <pketest> IF NOT EXISTS`, so they work on Desktop Enterprise
   without manual setup. They refuse to use `neo4j` or `system`.
5. Reports live in `docs/reports/` rather than `docs/cursor/reports/`, because the Cursor loop has ended.
6. Extra ArchUnit rules beyond the prompt: `kernel` is framework-free, and `api..` never touches Neo4j directly.

## 8. Problems and open questions

- **External (Neo4j Desktop) test mode is implemented but has not been run yet.** The Desktop DBMS was not running
  and needs your password. To check it, start the 2026.09 DBMS and run
  `PKE_TEST_NEO4J_MODE=external NEO4J_TEST_URI=bolt://localhost:7687 NEO4J_TEST_PASSWORD=… ./mvnw verify`.
- **Port clash**: the compose Neo4j and Neo4j Desktop both use 7687. Run one at a time.
- **Jackson 3**: Boot 4 uses Jackson 3. `json-schema-validator` (M1) and `swagger-parser` (M2) may bring
  Jackson 2. This must be checked when they are added.
- **R7 (bank data to an external LLM) is still open.** Confirm what may be sent to OpenAI before any real Dukhan
  repository or BRD is ingested. Until then the demo stays synthetic.

## 9. Git

M0 code commit: `78bacff` ("M0: build skeleton and verified versions"). This report is committed separately.
Nothing pushed (no remote).

## Next milestone

**M1 (Phase 1 foundation):** kernel IDs and epistemic status; the YAML schema registry with a JSON Schema
2020-12 meta-schema; the `graph` writer (entity + assertion + native edge + evidence + version + `ChangeEvent`
in one transaction, global `seq`); REST for schema registration, entities and relationships; idempotent re-runs.
