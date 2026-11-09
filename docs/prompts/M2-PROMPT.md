# M2: Deterministic ingestion and project dimensions (spec Phase 2)

Precondition: M1 is accepted. Read ADR-007 first (§3 and §6 are this milestone's core) and fold it into
`IMPLEMENTATION_PLAN.md`. **No LLM calls in M2.** Everything here is deterministic.

## 1. Sources and documents
- `DocumentStore` port + `FileSystemDocumentStore` (content-addressed by SHA-256, immutable, under `pke.data-dir`).
- `SourceArtifact` / `SourceVersion` registry: a logical key, a content hash, a Git commit (if any) and a document
  version. A non-Git upload gets its own version identity (spec §9.3).
- Parsers: Tika (PDF, DOCX, XLSX, HTML) and CommonMark (Markdown/text) → `Section` nodes (heading path, page,
  order, text, hash). Fulltext index on sections. **No embeddings** (ADR-007 §4). Define an OCR extension point
  for scanned PDFs (interface + "not configured" status, no OCR engine yet).
- Ingestion is idempotent: same content + same parser version → zero events.

## 2. Java code extractor (JavaParser + symbol solver)
Extract with code-location evidence (repo, commit, file, line range): packages, types, methods (erased signature
as natural key), imports, extends/implements, `CALLS` (resolved where possible; unresolved calls are recorded
with `resolution: unresolved`, never guessed), annotations, Spring endpoints (`@*Mapping` → `ApiOperation`),
injection (`INJECTS`), `@Value`/`@ConfigurationProperties` → `USES_CONFIGURATION`, Kafka listener/template,
JPA `@Entity/@Table` → `READS_FROM/WRITES_TO` where determinable, tests → targets (`TESTED_BY`), error codes
(configurable patterns, for example constants or `OCS_T_ERROR_CONFIG` inserts in release scripts) → `THROWS_ERROR`,
and **log statements** (`LOGS {order}` → `LogStatement {level, template, loggerClass}`, from code only).
Register every entity and relationship type used here as YAML in `definitions/schema/` (no hard-coded type lists).

## 3. Git (JGit) and incremental processing
- Register a repository (local path), record commits, changed files and renames.
- Incremental run: only changed files plus the reverse-dependency set for call edges into changed types.
  Relationships no longer supported by the code become `INVALID` (history kept). New ones are created.
- Rename/move detection keeps `pke_id` (ADR-002) and adds an alias.

## 4. Journeys (`JourneySourceAdapter` SPI, in `pke-spi`)
Implement three adapters now; the prompt-interview adapter comes in M6:
- **Postman**: collection folders/requests → `UserJourney` / ordered `JourneyStep` / `ApiOperation`;
  `pm.environment.set(...)` in test scripts plus later `{{var}}` use → `FEEDS {field}`. Strip auth values and
  sample bodies; keep field names and types only (no customer data).
- **Java journey tests and Gatling simulations**: classes named `*Journey`, `*E2ETest` and `*Simulation` (configurable);
  method/step order, client calls → endpoint constants/enums → `ApiOperation`, gateway headers (moduleId,
  subModuleId, serviceId, screenId) when declared in enums. Record unresolved steps instead of guessing.
- **JMeter `.jmx`**: Transaction Controller = step, HTTP sampler = call, JSON/regex extractor → `FEEDS`. Use a
  synthetic `.jmx` fixture (the real QA project has none today).
Reference project to read (read-only) for the patterns: `C:\Code2025\Dukhan_Bank\BitBucket\dkn-ocs-qa-automation`
(Postman under `api-reference/postman`, journeys under `src/test/java/.../tests/*/*Journey.java`, Gatling
under `performance/`; all Java: Rest Assured + TestNG + Gatling Java DSL).
`ApiOperation` must carry the **full API definition**: HTTP method, full normalised path template, path and query
parameters, gateway service headers (moduleId, subModuleId, serviceId, screenId, unit, channel), other required
headers (names only, never values), and the request and response body **field structure** (names, types, nesting;
no values). Identity = HTTP method + path template + gateway service headers. TestNG suite XMLs
(`src/test/resources/suites/*.xml`) give suite → test-class grouping and order: map each suite to a `TestSuite` node
that `COVERS` its journeys.

## 5. Routing without a routing graph (ADR-007 §6)
`RouteResolver` port with two implementations: (a) a gateway route export reader (configured file path; inspect
`dkn-db-config-master` read-only to support its real export format), and (b) Spring mapping matching. The output is
`ApiOperation -[:HANDLED_BY]-> JavaMethod` with evidence pointing at the route row/file or the controller. Do
not create route nodes.

## 6. Synthetic demo corpus (`demo/`), used by all tests
Mirrors spec §26: `demo/brd/international-transfer-brd.md` (+ a `.docx` version) with numbered requirements;
`demo/transfer-service/` (a small Spring service: controller, service, shared `CurrencyConverter`, an external API
client, tests, log statements, error codes, as its own Git repo created by the test setup); a Postman collection,
a Java journey class and a `.jmx` for the transfer journey; and a route export file. No Dukhan data.

## 7. REST
`POST /api/sources/documents` (multipart), `POST /api/sources/repositories`, `POST /api/ingestion/runs`
(full|incremental), `GET /api/ingestion/runs/{id}`, `POST /api/ingestion/runs/{id}/retry`,
`GET /api/journeys`, `GET /api/journeys/{id}` (steps + APIs + handlers). Validation, consistent errors, pagination.

## 8. Tests (real Neo4j)
Unchanged re-ingestion → zero events; changed BRD → new SourceVersion, old one kept; demo Java → expected
endpoints, CALLS, INJECTS, TESTED_BY, LOGS and THROWS_ERROR edges (assert specific ones); a commit that removes a
call → that edge becomes INVALID with history, and a new call is created; rename keeps `pke_id`; Postman, Java and
JMeter adapters produce the same transfer journey (same steps and ApiOperations); FEEDS edges are correct;
RouteResolver links each journey API to its handler; a replaced or removed source version behaves as specified.
**Real-repo smoke (manual, report only):** run the extractor and journey adapters read-only on
`dkn-international-transfer-service` and `dkn-ocs-qa-automation` into a throwaway database; report the counts and
the top unresolved items in the M2 report. Commit nothing from them.
