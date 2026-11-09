# M2 report: deterministic ingestion and project dimensions

Date: 2026-11-09 · Implemented by: Claude (directly) · Prompt: `docs/prompts/M2-PROMPT.md` · No LLM calls

## 1. Summary

The engine now ingests sources deterministically.
- **Documents:** Markdown, Word, PDF, Excel and HTML become content-hashed source versions with searchable
  sections (no embeddings). An OCR extension point covers scanned PDFs.
- **Java repositories:** ingested through Git (JGit, committed content only), in full or incremental runs.
  Extracted: calls, injection, Spring endpoints, configuration, Kafka, JPA, tests, error codes and log
  statements. Relationships a commit removes are invalidated with history, and renames keep `pke_id`.
- **User journeys:** read from Postman, Java/TestNG, Gatling and JMeter into one journey model with data flow
  and suite coverage.
- **Routes:** gateway APIs are resolved to their handler methods from the real route export format.
- **Plan:** ADR-007 is folded into the plan.

Everything runs on a synthetic `demo/` corpus. The smoke run on two real Dukhan repositories completed
cleanly and idempotently.

## 2. Versions chosen

No changes to Boot, Spring AI or Neo4j. Now used: JavaParser 3.28.2, JGit 7.8.1, Tika 4.2.0 (`tika-bom`;
pdf, microsoft, html and text modules only), and CommonMark 0.32.0 (new). JSqlParser stays pinned but unused:
a small quote-aware `INSERT` reader covers the release and route scripts. All of this is recorded in ADR-000.

## 3. Environment

| Item | Value |
|---|---|
| OS / Java / Maven | Windows 11 · Oracle JDK 21.0.11 · Maven 3.9.11 |
| Docker | Yes, 29.4.1 |
| Neo4j for tests | 2026.10.1 community (Testcontainers) |
| Neo4j for smoke run | 2026.10.1 community, throwaway container (`pke-smoke`, port 7688, removed afterwards) |

## 4. Files created or changed (main ones)

```
pke-spi/.../spi/journey/       JourneySourceAdapter, JourneyContext, JourneyDefinition (SPI)
definitions/schema/            ApiOperation 2.0.0 (two layers), JavaClass 1.1.0, JavaMethod 1.1.0, CALLS 1.1.0;
                               new entities: JavaPackage ConfigProperty KafkaTopic DatabaseTable ErrorCode LogStatement
                               UserJourney JourneyStep TestSuite; new relationships: IN_PACKAGE DECLARES IMPORTS EXTENDS
                               IMPLEMENTS_INTERFACE INJECTS USES_CONFIGURATION CONSUMES_FROM PUBLISHES_TO MAPS_TO READS_FROM
                               WRITES_TO THROWS_ERROR LOGS HAS_STEP CALLS_API FEEDS COVERS   (42 definitions)
engine/kernel      PkeIds (source ids), ReservedNames, ChangeEventTypes (source/rename events)
engine/schema      breaking change allowed only for types without instances (SchemaService, store port)
engine/graph       KnowledgeWriter (aliases/renames, scoped evidence, scope retraction, FROM SourceVersion,
                   skipMissingEndpoints), SourceWriter, SourceQueryService, IngestionRunStore, CodeQueryService,
                   GraphSchemaInitializer (+5 constraints, 3 indexes, fulltext pke_section_text)
engine/source      DocumentStore, FileSystemDocumentStore, GitRepository
engine/extract     JavaSourceExtractor, ExtractedKnowledge, ApiOperations, SqlInserts, ErrorConfigScanner,
                   route/{RouteResolver, GatewayRouteExportResolver, SpringMappingRouteResolver}
engine/ingest      DocumentIngestionService, RepositoryIngestionService, KnowledgeBatchWriter, RouteResolutionService,
                   IngestionRunService, IngestionProperties, document/{DocumentParser, MarkdownDocumentParser,
                   TikaDocumentParser, OcrProvider}, journey/{Postman,Jmeter,JavaJourney}Adapter, JourneySupport,
                   JourneyKnowledgeMapper, TestSuiteReader
engine/api/rest    SourceController, IngestionController
demo/              BRD (.md, .docx, v2), transfer-service (+ c2, c3 change commits), qa-automation, routes
tests              JavaSourceExtractorTest, JourneyAdaptersTest, ExtractHelpersTest, DocumentIngestionIT,
                   RepositoryIngestionIT, JourneyIngestionIT, support/DemoCorpus; SchemaRegistryIT, KnowledgeValidatorTest updated
docs               ADR-011 (sources, incremental ingestion), ADR-012 (API identity, journeys, routes), ADR-010 and
                   ADR-000 updated, IMPLEMENTATION_PLAN (ADR-007 folded into §5/§7/§8/§13), REST.md, README, demo/README
```

## 5. Build and test results

Command: `mvn -B clean verify` (repo root, Docker running)

```
[INFO] Project Knowledge Engine ........................... SUCCESS [  0.784 s]
[INFO] PKE SPI ............................................ SUCCESS [  3.302 s]
[INFO] PKE Engine ......................................... SUCCESS [02:55 min]
[INFO] PKE Plugins ........................................ SUCCESS [  0.034 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  03:01 min
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire | 106 | 0 | 0 | 0 |
| pke-engine | failsafe (real Neo4j) | 48 | 0 | 0 | 0 |

New in M2: JavaSourceExtractorTest 6, JourneyAdaptersTest 6, ExtractHelpersTest 9, DocumentIngestionIT 4,
RepositoryIngestionIT 4, JourneyIngestionIT 6; SchemaRegistryIT now has 7. **No skipped tests.**

M2 prompt §8 requirements:

| Requirement | Test |
|---|---|
| Unchanged re-ingestion → zero events | `DocumentIngestionIT.reIngestingTheSameDocumentWritesNothing`, `RepositoryIngestionIT.reRunningWithoutChangesWritesNothing` (incremental and full), `JourneyIngestionIT.reIngestingTheJourneysWritesNothing` (full footprint including `seq` identical) |
| Changed BRD → new SourceVersion, old kept | `DocumentIngestionIT.aChangedDocumentIsANewVersionAndTheOldOneIsKept` |
| Specific CALLS, INJECTS, TESTED_BY, LOGS, THROWS_ERROR edges | `RepositoryIngestionIT.fullRunExtractsTheExpectedCodeKnowledge`, plus endpoints, EXPOSES_API, READS_FROM/WRITES_TO, Kafka, evidence `FROM` commit |
| Commit removes a call → INVALID with history, new call created | `RepositoryIngestionIT.aRemovedCallIsInvalidatedWithHistoryAndANewCallIsCreated` (versions CURRENT→INVALID, edge removed) |
| Rename keeps `pke_id` | `RepositoryIngestionIT.renamesKeepTheirIdentity` (class rename via Git rename, method rename via body hash, alias, `ENTITY_RENAMED`, relationship id unchanged) |
| Postman, Java and JMeter give the same journey | `JourneyAdaptersTest` (all four incl. Gatling: same 3 APIs), `JourneyIngestionIT.allFourSourcesDescribeTheSameJourney` |
| FEEDS correct | `dataFlowBetweenSteps` (1→2 `auid`, 2→3 `uuid`) |
| RouteResolver links each journey API to its handler | `routesLinkEveryJourneyApiToItsHandler` (3/3 via the route export, inactive row ignored) |
| Replaced or removed source version | `DocumentIngestionIT.aRemovedSourceKeepsItsHistoryAndCanBeReinstated` and the new-version test |

Bugs found by the tests and fixed:
1. Record accessors broke call resolution, so a deterministic declared-type fallback was added.
2. A request-body `Map.of` was taken as gateway headers in Java journeys.
3. JMeter top-level `hashTree`s were skipped.
4. A primitive `boolean` in `ChangeSet` broke JSON requests under Jackson 3.

## 6. App start check and real-repository smoke (manual, report only)

I ran the packaged jar against a throwaway Neo4j, using the real route export
`dkn-db-config-master/postgres/ocs_t_gateway_api_route.sql`, and did full runs of the read-only repositories.
Bootstrap registered 42 definitions. **Nothing from these repositories was committed.**

| | dkn-international-transfer-service | dkn-ocs-qa-automation |
|---|---|---|
| Status / time | COMPLETED / 160 s | COMPLETED / 27 s |
| Files extracted, parse problems, write failures | 653, 0, 0 | 90, 0, 0 |
| Created | 3,393 entities, 7,455 relationships | 819 entities, 1,712 relationships |
| Skipped (endpoint missing) | 107 | 14 |
| Journeys read | 7 Postman | 6 Postman, 6 Java journey, 4 Gatling, 8 TestNG suites |
| Gateway APIs / routed to a handler | 17 / 10 | 70 / 10 (other services not ingested) |

Graph after both: 2,290 JavaMethod, 685 JavaClass, 451 TestCase, 222 LogStatement, 136 JourneyStep,
116 ErrorCode, 91 ApiOperation, 86 ConfigProperty, 31 DatabaseTable and 21 UserJourney. Current
relationships: CALLS 3,205, TESTED_BY 481, INJECTS 168, CALLS_API 142, READS_FROM 124, HANDLED_BY 32,
FEEDS 30, COVERS 4 and THROWS_ERROR 1.

**Idempotency on real code:** a second FULL run and an INCREMENTAL run (0 files) wrote nothing.
The first smoke pass found one real problem: the two repositories' Postman collections describe the same
gateway APIs differently and overwrote each other (2 `ENTITY:UPDATED` per run). This is fixed. Gateway APIs
now merge into their stored properties, and after one merge pass alternating runs of both repositories write
nothing.

**Top unresolved items:**
- **Unresolved calls (transfer service): 2,667**, down from 4,413 after classifying static, external and
  accessor calls. Most are chained calls on library results: `build/0` 184, `thenReturn/1` 172,
  `builder/0` 123, `trim/0` 109, `body/1` 83, and Reactor `flatMap`/`map`/`onErrorResume`/`block`. They need
  the library jars on the symbol solver's path (next step).
- **THROWS_ERROR is only 1 in real code.** The service throws exceptions with symbolic constants
  (`LIMIT_ERR_VALIDATION_FAILED`) that map to codes at runtime. 116 `ErrorCode`s come from the error-config
  scripts. The mapping from constant to code is not in source and needs a configurable mapping.
- **Real Gatling simulations give 0 steps.** They go through a shared `GatewayLoad` helper, not inline
  `http(...)` chains.
- **The real `TransferJourney` resolves 1 step.** Its headers and paths come from `type.initiateService()`
  (polymorphic, reported as unresolved). Postman covers these journeys fully.
- **Gateway APIs whose service is not ingested** stay unrouted, with the reason recorded.

## 7. Deviations from the prompt

1. **`ApiOperation` identity** is method + path + `moduleId|subModuleId|serviceId|screenId`. `unit` and
   `channel` are stored but are not identity, because the real QA data sends different unit and channel values
   (MB, WEB, placeholder `ab`) for the same API. The identity headers are configurable. There are two layers:
   `gateway` (client view) and `service` (controller view), linked through `HANDLED_BY` to the same method.
2. **`ApiOperation` 2.0.0 is a breaking schema change.** It was allowed by a new rule: a breaking change is
   accepted only while the type has no instances (ADR-010). Declared migrations remain later work.
3. **Java journeys produce no FEEDS** (data passes through fields at runtime). Postman, Gatling and JMeter do.
4. **The prompt-interview journey adapter is M6**, as the README says. The SPI already supports it.
5. **JSqlParser is not used** (see §2). Spring Boot `application*.yml` and OpenAPI files are not ingested yet:
   the prompt listed config references from code (`@Value`, `@ConfigurationProperties`), which are done.
6. **Removed classes and methods keep their entity.** Only their relationships are invalidated. Entity
   retirement comes with the history work in M5.

## 8. Problems and open questions

- **Library classpath for symbol resolution** (biggest remaining source of unresolved calls). Proposal: resolve
  each repository's Maven dependencies (offline, read-only) and add the jars to the type solver. This is an
  architect decision, because it means running Maven on the target repositories.
- **Error-code mapping**: the real code raises symbolic constants. Should a constant-to-code mapping (from the
  services' error enums or properties) be configured or inferred? This is a product decision for M3 or M5.
- **Real Gatling and polymorphic journeys**: support for the `GatewayLoad` helper pattern and enum-switch
  polymorphism could be added deterministically. Otherwise the M6 journey interview fills the gaps.
- **Security TODO (from M1)**: still no authentication, and health shows details (M6).
- Ingestion runs execute one at a time in-process. A restart while a run is `RUNNING` leaves it `RUNNING`
  (recovery belongs to M5).

## 9. Git

Committed locally as `3fbb0fa` (`M2: deterministic ingestion - documents, Java code, Git, journeys, routes`) by
William Thomas <pkwilliamthomas@gmail.com>. **Not pushed:** Claude Code's permission check blocked `git push`;
William pushes with `git push origin main`.

## Next milestone

**M3: AI layer** (`docs/prompts/M3-PROMPT.md`): Spring AI wiring, skills and pipelines runtime,
BRD → knowledge cards, Mem0-style reconcile, conflict questions, and requirement → implementation links.
