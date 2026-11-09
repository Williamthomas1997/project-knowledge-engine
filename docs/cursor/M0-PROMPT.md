# M0: Build skeleton and verified versions

You are implementing milestone **M0** of the Project Knowledge Engine. Read `AGENTS.md`,
`docs/MASTER_SPEC.md` (sections 2, 24, 25, 28) and `docs/IMPLEMENTATION_PLAN.md` (sections 1, 2 and 13) first.
M0 builds the foundation only: no knowledge logic yet.

## 1. Resolve and verify versions (record everything)
Use Maven Central and the official docs to choose the **latest stable** versions:
- Java 21, and Spring Boot **4.0.x** (latest patch).
- The Spring AI BOM version that officially supports Spring Boot 4.0.x. Prefer GA. If only milestones
  support Boot 4, use the latest milestone from `https://repo.spring.io/milestone` and say so.
- Neo4j Java driver (managed by Boot), Testcontainers (with its `neo4j` module), ArchUnit (JUnit 5),
  JavaParser (`javaparser-symbol-solver-core`), Eclipse JGit, Apache Tika (`tika-core` + parsers-standard),
  `com.networknt:json-schema-validator`, swagger-parser v3, JSqlParser.
Put every version in parent `pom.xml` `<properties>` or import its BOM. Write
`docs/adr/ADR-000-technology-versions.md` with a table: library | version | source (BOM or explicit) |
why this version | compatibility evidence (link or doc reference).
Only the libraries needed in M0 become dependencies now. The others are just pinned as properties.

## 2. Maven multi-module layout
```
pom.xml                      parent: groupId com.omnicore.pke, artifactId pke-parent, version 0.1.0-SNAPSHOT, packaging pom
pke-spi/                     plain Java jar, no Spring Boot; package com.omnicore.pke.spi (one placeholder interface `PkePlugin` with id() and version())
pke-engine/                  Spring Boot application com.omnicore.pke.engine.PkeApplication
pke-plugins/pom.xml          aggregator (packaging pom), no modules yet
definitions/                 README.md only (holds schema/pipeline/skill YAML from M1 on)
demo/  eval/  docker/        README.md in each saying what goes there
```
Maven wrapper (`mvnw`, `mvnw.cmd`) at the root.

## 3. pke-engine contents
Packages under `com.omnicore.pke.engine`: `kernel, schema, graph, history, source, ingest, extract,
reconcile, retrieval, ai, pipeline, evolution, api.rest, api.mcp, ops`. Each gets a `package-info.java`
with a one-line responsibility taken from plan §1.

Dependencies: spring-boot-starter-web, -actuator, -validation, -data-neo4j (we use `Neo4jClient`, with no
SDN entity mapping for knowledge), spring-boot-starter-test, testcontainers neo4j + junit-jupiter, archunit-junit5.
Add the Spring AI BOM and the OpenAI starter. **The app must start with no OpenAI key**: chat and embedding
models are disabled by default (in Spring AI 1.x+ the properties are `spring.ai.model.chat=none` and
`spring.ai.model.embedding=none`; check the right properties for the version you chose) and are enabled
by an `openai` profile.

Config:
- `application.yml`: Neo4j from env vars `NEO4J_URI` (default `bolt://localhost:7687`), `NEO4J_USERNAME`,
  `NEO4J_PASSWORD`, `NEO4J_DATABASE` (default `neo4j`); actuator health + info.
- `application-openai.yml`: `OPENAI_API_KEY`.
- `.env.example` with placeholder values only; `.gitignore` for Java/Maven/IDE/.env/data/.

Code:
- `ops/Neo4jCapabilityCheck`: at startup it logs the Neo4j version and edition (`CALL dbms.components()`)
  and **fails fast with a clear message if the version is below 5.26**.
- `GET /api/system/info`: returns app version, Neo4j version and edition, active profiles, and whether a
  chat model and an embedding model are configured (true/false).

## 4. Tests (all must run in `mvn clean verify`)
- `ArchitectureTest` (ArchUnit): `api..` may not be accessed by core packages; `kernel` depends on no other
  engine package; `pke-spi` has no Spring dependency (check that in pke-spi's own test or by enforcer).
- `Neo4jDynamicLabelIT`: proves the core capability ADR-001 depends on. Create a node with a dynamic label
  and a dynamic relationship type passed as **parameters** (`CREATE (n:Entity) SET n:$($label)`,
  `MERGE (a)-[r:$($relType)]->(b)`), read them back, and assert. Also assert that an invalid identifier is
  rejected by a small `CypherIdentifiers` validator in `kernel` (unit-test the validator separately).
- `SystemInfoIT`: boots the full app without `OPENAI_API_KEY` and calls `/api/system/info`.

Neo4j for tests, in this order:
1. If `docker info` works, use Testcontainers with a Neo4j image ≥ 5.26 (pin the exact tag in properties).
2. Otherwise use Neo4j Desktop through env vars `NEO4J_TEST_URI/USERNAME/PASSWORD/DATABASE`. Use a
   **separate database** (for example `pketest`), never the dev one, and clean it before each test class.
   Run integration tests with failsafe (`*IT`). If neither Docker nor the env vars are available, the IT
   tests must be **skipped with a clear message**, not reported as passed.
Say in the report which option you used.

## 5. Docs and repo
- `README.md`: what the project is (2–3 lines), prerequisites, how to configure Neo4j Desktop (version ≥ 5.26
  and a `pketest` database) or Docker, env vars, `mvnw clean verify`, how to run the app, and the
  `/api/system/info` example.
- `docker/docker-compose.yml`: Neo4j (same pinned tag) for people who use Docker.
- Run `git init` if needed and make one commit: `M0: build skeleton and verified versions`.

## 6. Report: write `docs/cursor/reports/M0-REPORT.md`
Use exactly these sections:
1. **Summary**: 3–5 lines.
2. **Versions chosen**: the ADR-000 table, or a link to it.
3. **Environment**: OS, `java -version`, `mvn -v`, Docker yes/no, Neo4j version and edition, and the test DB option used.
4. **Files created**: a tree.
5. **Build and test results**: the exact command and the pasted Maven `BUILD SUCCESS/FAILURE` lines; tests
   run/failed/skipped per module (surefire **and** failsafe); the names of any skipped tests and why.
6. **App start check**: the command used and the actual `/api/system/info` JSON response.
7. **Deviations from the plan**: each one with its reason.
8. **Problems and open questions**.
9. **Git**: the commit hash.

Done means: `mvn clean verify` is BUILD SUCCESS, the integration tests actually ran against a real Neo4j
≥ 5.26 (not skipped), and the report is written.
