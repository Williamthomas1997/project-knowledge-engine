# M1 report: foundation (schema registry, single graph writer, history in one transaction, REST)

Date: 2026-11-09 · Implemented by: Claude (directly) · Prompt: `docs/prompts/M1-PROMPT.md`

## 1. Summary

Entity and relationship types are YAML definitions, validated by JSON Schema 2020-12 meta-schemas plus semantic
checks, versioned immutably and promoted from Git into a registry stored in Neo4j. One writer
(`KnowledgeWriter` → `GraphTransactions`) saves entity + assertion + native edge + evidence + version +
`ChangeEvent` in one transaction, with a gap-free global `seq`. Re-runs are no-ops, a failure at any stage rolls
back everything, and an integrity service checks assertion ↔ edge consistency. ArchUnit enforces the single-writer
rule. REST covers schema registration, entity and relationship writes and reads, invalidation, history and
integrity. Only Community features are used.

## 2. Versions chosen

No version changes; see ADR-000. Added: `com.networknt:json-schema-validator` 3.0.9 (already pinned in M0) and
`tools.jackson.dataformat:jackson-dataformat-yaml` (Boot BOM, 3.1.7). **Jackson 3 check (review item 5):** 3.0.9
depends on `tools.jackson` 3.x, so it runs on Boot 4's Jackson. No isolated Jackson 2 mapper or alternative
validator is needed. Recorded in ADR-000.

## 3. Environment

| Item | Value |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| Java / Maven | Oracle JDK 21.0.11 / Maven 3.9.11 |
| Docker | Yes, Docker Desktop 29.4.1 |
| Neo4j used by tests | 2026.10.1 community (Testcontainers, `PKE_TEST_NEO4J_MODE=auto`) |
| Neo4j for app check | 2026.10.1 community (`docker/docker-compose.yml`) |

## 4. Files created or changed

```
definitions/meta/{entity-type,relationship-type}.schema.json          JSON Schema 2020-12 meta-schemas
definitions/schema/entities/*.yaml       BusinessRequirement BusinessFeature Service ApiOperation JavaClass JavaMethod TestCase
definitions/schema/relationships/*.yaml  IMPLEMENTS_REQUIREMENT PART_OF_FEATURE DEPENDS_ON_FEATURE CALLS EXPOSES_API HANDLED_BY TESTED_BY RELATED_TO
pke-engine/src/main/java/com/omnicore/pke/engine/
  kernel/   PkeIds (UUIDv5/v7) CanonicalJson EpistemicStatus ReservedNames ChangeEventTypes Violation
            ValidationFailedException NotFoundException ConflictException
  schema/   PropertyType PropertyDefinition DefinitionKind LifecycleStatus TypeDefinition EntityTypeDefinition
            RelationshipTypeDefinition DefinitionDocument MetaSchemaValidator SchemaDefinitionParser SchemaRegistry
            SchemaDefinitionStore (port) SchemaCompatibility DefinitionSource SchemaService SchemaBootstrap
            PropertyValues KnowledgeValidator
  graph/    GraphSessions GraphReader (read-only) GraphTransactions (the only write path) WriteStage
            WriteFaultInjector PendingEvent GraphSchemaInitializer KnowledgeChanges KnowledgeWriter
            Neo4jSchemaDefinitionStore KnowledgeQueryService GraphIntegrityService
  api/rest/ SchemaController KnowledgeController ApiExceptionHandler Actors
  ops/      Neo4jCapabilityCheck (now via GraphReader)
pke-engine/src/test/java/...  ArchitectureTest (rewritten) ArchitectureRuleFixturesTest archfixture/RogueWriter
            graph/RogueGraphWriter, unit tests PkeIdsTest CanonicalJsonTest SchemaDefinitionParserTest
            SchemaCompatibilityTest KnowledgeValidatorTest, ITs KnowledgeWriterIT WriteRollbackIT ConcurrentWritesIT
            GraphIntegrityIT SchemaRegistryIT RestApiIT SystemInfoIT (now on the shared base),
            support/KnowledgeIT TestFaultInjector Demo
pke-engine/pom.xml            json-schema-validator, jackson-dataformat-yaml; definitions/ packaged as classpath:definitions/
docs/adr/ADR-008-neo4j-community-compatibility.md
docs/adr/ADR-009-single-writer-and-history-consistency.md
docs/adr/ADR-010-schema-definitions-and-identity.md
docs/adr/ADR-000 (Jackson result) · docs/IMPLEMENTATION_PLAN.md (R3/R4/R7, §3 note) · docs/api/REST.md · README.md · definitions/README.md
```

## 5. Build and test results

Command: `mvn -B clean verify` (repo root, Docker running)

```
[INFO] Project Knowledge Engine ........................... SUCCESS [  0.539 s]
[INFO] PKE SPI ............................................ SUCCESS [  3.103 s]
[INFO] PKE Engine ......................................... SUCCESS [01:07 min]
[INFO] PKE Plugins ........................................ SUCCESS [  0.005 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  01:11 min
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire | 85 | 0 | 0 | 0 |
| pke-engine | failsafe (real Neo4j) | 33 | 0 | 0 | 0 |

Unit: CypherIdentifiersTest 37, SchemaDefinitionParserTest 13, Neo4jServerInfoTest 9, ArchitectureTest 6,
KnowledgeValidatorTest 6, PkeIdsTest 4, SchemaCompatibilityTest 4, CanonicalJsonTest 3, ArchitectureRuleFixturesTest 3.
Integration: KnowledgeWriterIT 9, WriteRollbackIT 6, SchemaRegistryIT 6, GraphIntegrityIT 4, RestApiIT 3,
SystemInfoIT 2, Neo4jDynamicLabelIT 2, ConcurrentWritesIT 1. **No skipped tests.** No Cypher deprecation warnings in
the log.

Required M1 tests (review item 4), all against a real Neo4j:

| Requirement | Test |
|---|---|
| Unchanged re-run → zero events, zero versions | `KnowledgeWriterIT.reRunningUnchangedInputProducesZeroEventsAndZeroVersions` (full footprint incl. `seq` identical), `…reRunningTheSchemaBootstrapProducesZeroEvents` |
| Failure after the edge, before the event, rolls back everything | `WriteRollbackIT` fails at **every** `WriteStage` (incl. AFTER_EDGE and BEFORE_EVENTS): entities, edges, assertions, evidence, versions, events and `seq` all unchanged; a clean retry then succeeds |
| ≥ 8 concurrent writers → gap-free, strictly ordered `seq` | `ConcurrentWritesIT`: 8 threads × 10 change sets on a contended entity; 160 events, contiguous `seq`, one contiguous block per transaction, event times non-decreasing, 80 versions of the shared entity |
| Every current assertion has exactly one native edge, and vice versa | `GraphIntegrityService` + `GraphIntegrityIT` (clean graph passes; missing, duplicate, stray and stale edges detected) |
| Unknown labels, types and properties rejected before any Cypher | `KnowledgeWriterIT.unknownLabelsTypesAndPropertiesAreRejectedBeforeAnyCypherRuns`: 7 invalid change sets, `pke.graph.write.transactions` counter unchanged, graph unchanged |

Bugs found and fixed during the milestone: (1) `PkeIds` hashed `1` and `"1"` to the same id (strings weren't
JSON-encoded), which `PkeIdsTest` caught and is fixed; (2) version snapshots read small integers back as `Integer`
rather than `Long`, now read as `Long` to match live properties.

## 6. App start check

```bash
NEO4J_PASSWORD=<local> docker compose -f docker/docker-compose.yml up -d
java -jar pke-engine/target/pke-engine-0.1.0-SNAPSHOT.jar --server.port=8089     # no OPENAI_API_KEY
```
Log: `Connected to Neo4j 2026.10.1 (community edition)` → `Ensured 14 Neo4j constraints and indexes` →
`Schema bootstrap: 15 registered, 0 unchanged`. A `POST /api/knowledge/changes` with two `JavaMethod`s and a
`CALLS` returned `CREATED` ×3 with `seq` 16–18. Re-posting returned `200`/`UNCHANGED`. An unknown type returned
`400` with `entity type 'Spaceship' is not in the active schema`. `GET /api/integrity` →
`{"currentAssertions":1,"knowledgeEdges":1,"violations":[]}`. **After a restart:** `Schema bootstrap: 0 registered,
15 unchanged`, and the events were still `16..18`. The app and container were stopped afterwards.

## 7. Deviations from the plan or prompt

1. **Push to GitHub.** The M1 prompt and the old working agreement say "never push". The architect handoff
   (item 1) and William's instructions say to push to `github.com/Williamthomas1997/project-knowledge-engine`
   after each green milestone. The handoff and the instructions win, and `.cursor/rules` and `AGENTS.md` were
   updated. Commits were re-authored to `William Thomas <pkwilliamthomas@gmail.com>`.
2. **The writer uses the Neo4j `Driver` directly** (`executeWrite` with managed retries), not `Neo4jClient`. That
   gives explicit control of the one-transaction protocol and the fault hooks. `spring-boot-starter-data-neo4j`
   stays for driver auto-configuration, health and the database property. `ops` now reads through `GraphReader`.
3. **Breaking schema changes are rejected** (409, with reasons) instead of migrated. Declared migrations (§13.2) are
   later work. Additive changes, new versions and deprecation/retirement work.
4. **Evidence → source link:** evidence stores `sourceArtifactId`/`sourceVersion` as properties.
   `-[:FROM]->(:SourceVersion)` arrives with source nodes in M2.
5. **Assertions carry `pke_validAt`/`pke_invalidAt`** (ADR-007 §5) already, alongside the `seq`-based versions.
6. **Event time** is the JVM clock read after taking the sequence lock, not the DB clock, so times follow `seq` order.

## 8. Problems and open questions

- **Push not done yet.** `git remote add origin … && git push` was **blocked by Claude Code's permission
  classifier** ("Remote Repoint"). Commits are local and correctly authored. William needs to allow it, or run
  the commands himself (below).
- **Security TODO for M6 (review item 6):** `/actuator/health` uses `show-details: always`, and there is no
  authentication on any endpoint. Both must be locked down in M6. The actor header is informational only.
- **Schema cache is per instance.** `SchemaRegistry` is reloaded from Neo4j at startup and updated on registration.
  If several engine instances run, one needs a reload trigger (single instance assumed for now).
- **ApiOperation identity will change in M2** (handoff item 9: gateway headers + field structure). Changing a
  natural key is a breaking change. Since no real data exists yet, M2 should redefine ApiOperation as 2.0.0 and
  reset dev databases, or add the migration mechanism first. Flagged for the architect.
- **No relationship property indexes yet.** The integrity check scans knowledge edges; fine at demo scale (ADR-008).
- The app logs "Started" before the startup runners (capability check, constraints, bootstrap) finish. Readiness
  should depend on them (M6 security/ops work).

## 9. Git

Committed as `M1: schema registry, single graph writer with history in one transaction, REST` by
William Thomas <pkwilliamthomas@gmail.com> (hash in `git log`). Also committed before M1:
`Add ADR-007, milestone prompts and architect handoff; push policy to GitHub`. **Not pushed** (see §8). To push:

```bash
git remote add origin https://github.com/Williamthomas1997/project-knowledge-engine.git
git push -u origin main
```

## Next milestone

**M2: deterministic ingestion** (`docs/prompts/M2-PROMPT.md`): document store, Tika/Markdown, sections and
chunks, Java (JavaParser), Git (JGit), journeys (Postman / Java tests / Gatling), routes, and the synthetic demo
corpus. Fold ADR-007 into the plan.
