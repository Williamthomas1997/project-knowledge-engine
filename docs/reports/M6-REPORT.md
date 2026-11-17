# M6 report: MCP server, security, Claude Code integration

Date: 2026-11-17 · Implemented by: Claude (directly) · Prompt: `docs/prompts/M6-PROMPT.md` plus the M5 review items ·
ADR-017

## 1. Summary

- **MCP server** (Spring AI 2.0.2 MCP server starter, WebMVC, streamable HTTP on `/mcp`, sync): 20 tools in
  `api.mcp.KnowledgeTools`: the 18 from the prompt plus `start_journey_interview` and `answer_journey_interview`.
  Each tool calls the same application service as REST and returns the same JSON. A STDIO launcher is also provided:
  profile `mcp-stdio` and `bin/pke-mcp-stdio.sh`. Every tool is documented in `docs/mcp-tools.md`.
- **Security**:
  - **API keys**: Spring Security, stateless. Keys are `pke_` + 256 random bits; only the SHA-256 is stored in
    `:ApiKey`. They are created, listed and revoked with the key CLI `bin/pke-keys.sh`.
  - **Roles**: READER < CONTRIBUTOR < SCHEMA_ADMIN, and EVOLUTION_OPERATOR includes CONTRIBUTOR. They apply to REST,
    MCP and actuator.
  - **Audit**: every mutating REST call and every changing MCP tool call writes an `:AuditEvent`, including refused
    calls.
  - **Actuator**: anonymous callers get the health status only; details need a key.
  - **Execution policies**: one per profile (`definitions/policies/execution.yaml`, `dev`/`test`/`prod`).
  - **Secrets**: read from environment variables only. A test checks that no key appears in logs or reports.
- **Journey interviews**: the `journey-interview` skill asks one question per turn. When the draft is complete, the
  engine matches each step to an ApiOperation deterministically and asks the user when a step matches several APIs or
  none. The journey is written as `HUMAN_VERIFIED`, and the session is kept in `:InterviewSession`.
- **Claude Code**: four skills in `claude-skills/` (`pke-bootstrap`, `pke-context`, `pke-questions`, `pke-journey`).
  `docs/claude-code-setup.md` covers `.mcp.json` over HTTP and STDIO, key creation, skill installation, the KGP key
  and the execution policy.
- **M5 review items**:
  1. `POST /api/history/restore`, `POST /api/schema/migrations` and schema registration now need SCHEMA_ADMIN. A
     CONTRIBUTOR key gets 403 (tested).
  2. PKE now listens on **8089**. The `kgp` CONTRIBUTOR key was created with the key CLI in the development Neo4j
     (`pke-neo4j`) and stored in the user environment variable `PKE_API_KEY`. The key is not in any file. The setup
     is documented in `docs/claude-code-setup.md` §4. KGP itself was not edited.
  3. A read-only smoke on the real repository is in §6.

## 2. Versions chosen

| Dependency | Version | Why |
|---|---|---|
| `spring-ai-starter-mcp-server-webmvc` | 2.0.2 (Spring AI BOM) | Same Spring AI line; brings the MCP Java SDK `io.modelcontextprotocol.sdk:mcp` 2.1.0 (server, and the client used by the tests) |
| `spring-boot-starter-security` | 4.1.2 (Boot BOM, Spring Security 7.1.2) | The platform's security; no extra library for API keys |

## 3. Environment

| Item | Value |
|---|---|
| OS / Java / Maven | Windows 11 · Oracle JDK 21.0.11 · Maven 3.9.11 |
| Docker / Neo4j / Qdrant for tests | 29.4.1 · `neo4j:2026.10.1-community` · `qdrant/qdrant:v1.18.0` (Testcontainers) |
| Model | None; hashing embeddings. Replay fixtures, hand-authored as in M3–M5 (3 new, `journey-interview`) |

## 4. Files created or changed (main ones)

```
engine/security     Role, Caller, ApiKeyService, Access, ExecutionPolicies, SecurityConfiguration (filter chain,
                    API-key filter, audit filter), ApiKeyCli, StdioLogin
engine/api/mcp      KnowledgeTools (20 @McpTool methods)
engine/graph        ApiKeyStore, AuditStore, InterviewSessionStore; CodeQueryService.apiCatalogue;
                    GraphSchemaInitializer (+3 constraints, +1 index)
engine/ingest       journey/JourneyInterviewService
engine/api/rest     Actors (key-based actor, X-PKE-Actor = on behalf of); SkillController (describe moved to
                    SkillRegistry, shared with MCP)
engine              PkeApplication (no generated user); application.yml (port 8089, MCP server, health details
                    when authorized, policy profile); application-mcp-stdio.yml
definitions         skills/journey-interview (+ eval); policies/execution.yaml; meta/execution-policy.schema.json
claude-skills       pke-bootstrap, pke-context, pke-questions, pke-journey
bin                 pke-keys.sh, pke-mcp-stdio.sh; pke-code-on-commit.sh sends PKE_API_KEY
tests               McpIT (6), SecurityIT (6), JourneyInterviewMatchingTest (2); KnowledgeIT creates an admin key
                    per test and authorizes HTTP calls; 8 existing ITs use it; ArchUnit rule for api.mcp
docs                ADR-017, mcp-tools.md, claude-code-setup.md, REST.md (auth, roles), recovery.md, README,
                    IMPLEMENTATION_PLAN (M6 done), definitions/README, ai-fixtures/README, .env.example
```

## 5. Build and test results

Command: `mvn -B clean verify` (repo root, Docker running; integration tests ran against Testcontainers Neo4j)

```
[INFO] PKE SPI ............................................ SUCCESS [  3.329 s]
[INFO] PKE Engine ......................................... SUCCESS [06:55 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  06:59 min
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire (incl. ArchUnit, +1 rule) | 133 | 0 | 0 | 0 |
| pke-engine | failsafe (real Neo4j, Qdrant, replay model) | 120 | 0 | 0 | 0 |

Compared with M5: +3 unit tests (2 interview matching, 1 ArchUnit rule) and +12 ITs (McpIT 6, SecurityIT 6). All 108
earlier ITs still pass with keys: they now authorize their HTTP calls, and their actor assertions expect `key:test-admin/<header>`.

New tests, mapped to the prompt's test list:

| Prompt requirement | Test |
|---|---|
| MCP client (official MCP Java SDK) calls each tool on the demo corpus | `McpIT.everyToolAnswersAgainstTheDemoCorpus`: all 20 tools are listed with descriptions and schemas, and the 15 reading tools are called. The 5 changing tools are called in the tests below. |
| REST and MCP return equivalent results | `McpIT.restAndMcpReturnTheSameResults`: `get_entity`, `list_journeys`, `get_journey`, `list_skills` and `find_brd_sections` return identical JSON; `search_knowledge` returns the same hits in the same order |
| Unauthorised calls are rejected | `SecurityIT.callsWithoutAValidKeyAreRejected` (401 with no key, an unknown key or a revoked key); `SecurityIT.rolesDecideWhatAKeyMayDo` (403; restore and migrations need SCHEMA_ADMIN); `McpIT.readersCannotChangeAnythingAndAnonymousCallersAreRejected` (MCP refuses to connect without a valid key; a READER cannot call changing tools, and the refusal is audited as DENIED) |
| Journey interview completes a journey in a scripted conversation (replay) | `McpIT.aJourneyInterviewCompletesAJourneyLinkedToKnownApis`: 2 model turns, then 2 ambiguity questions (gateway or service API) and one invalid answer that is asked again. Result: `HUMAN_VERIFIED` journey with 2 steps linked to the gateway APIs. The session cannot be answered after completion. |
| `answer_question` applies the decision | `McpIT.answerQuestionAppliesTheDecisionAsHumanVerified` |
| Policy-checked `trigger_ingestion` / `submit_evolution_request` | `McpIT.triggerIngestionAndEvolutionRequestsFollowTheExecutionPolicy` (`test` profile: `reindex` and `RUN_SHELL` are refused; `code-on-commit` and `NEW_ENTITY_TYPE` are accepted and audited) |
| Audit log, actuator, key CLI, no keys in logs/reports | `SecurityIT.mutatingCallsAreAuditedIncludingRefusedOnes`, `actuatorShowsHealthDetailsOnlyToAuthenticatedCallers`, `theKeyCliCreatesListsAndRevokesKeys`, `keysNeverAppearInLogsOrReports` (captured output plus a scan of docs, skills, definitions and surefire/failsafe reports for PKE and OpenAI key patterns) |

## 6. Real-repository smoke (read-only, throwaway database)

Setup:
- The packaged jar ran on port 18089 against a throwaway `neo4j:2026.10.1-community` container (removed afterwards).
- Inputs were the real route export (`dkn-db-config-master/postgres/ocs_t_gateway_api_route.sql`) and
  `dkn-international-transfer-service` at `0130e0a`. The repository was read through Git only; its 5 pre-existing
  local changes were left untouched.
- No model was used and nothing was committed.

The SCHEMA_ADMIN key for the run was created with `bin/pke-keys.sh`-style CLI arguments, and also confirmed the
security changes:
- anonymous `GET /api/journeys` returned 401, and the same call with the key returned 200;
- anonymous `/actuator/health` showed the status only, and the call with the key showed details;
- the application log contained 0 key patterns.

| Check | Result |
|---|---|
| `POST /api/pipelines/code-on-commit?wait=true` `{repository, tag: uat}` | COMPLETED in 138 s. `ingest` (full, 653 files) → `shared-logic` (145 created) → `populate` (no derived types) → `link` (0 cards, no BRD) → `snapshot` (created, tag `uat`, seq 11431). |
| Graph | 1,875 JavaMethod, 587 JavaClass, 394 TestCase, 175 LogStatement, 116 ErrorCode, 86 ConfigProperty, 38 ApiOperation (21 service, 17 gateway), 7 UserJourney (Postman). Current relationships: 2,893 CALLS, 175 LOGS, 145 SHARES_LOGIC_WITH, 46 CALLS_API. |
| `SHARES_LOGIC_WITH` | 145 edges between 19 APIs, 3.7 shared methods on average (max 12). Only **47** link two service APIs. The other 98 repeat those links for the gateway APIs, which resolve to the same handlers (see finding 1). |
| Log-trail explanation | The line `INFO ChannelRoutingService - intl.remittance.routing.decision \| caller=validation \| phase=START ...` matched the correct statement in `ChannelRoutingService#selectIndiaLinkRouting`, which is also the likely failing method. It returned no API, no next-expected logs and no error codes (see finding 3), and the explanation was `SKIPPED_NO_MODEL`. |

Five example links (service ↔ service, ranked by shared methods; methods shown as `Class#method`):

| API | API | Shared methods |
|---|---|---|
| `POST /api/v2/international-transaction/receipt` | `POST /api/v2/international-standing-orders/receipt` | 12: `ReactiveTransferErrorSupport#resolve`, `#toReceiptApiError`, `#explicitHeadersOrNull`, `TransferErrorResponse#apiError`, `#okApiError`, `#badRequestApiError`, `ErrorResponseBridge#resolve`, `RequestErrorContext#bind`, `#fromHttpHeaders` |
| `POST /api/v2/international-transfers/validation` | `POST /api/v2/international-transfers/update-details` | 9: `ReactiveTransferErrorSupport#resolve`, `#mapHttpStatus`, `#toCommonApiError`, `ErrorResponseBridge#resolve`, `#fallback`, `RequestErrorContext#bind`, `ServiceErrorResponseResolver#resolve`, … |
| `POST /api/v1/international-transaction/receipt` | `POST /api/v2/international-standing-orders/receipt` | 5: `TransferErrorResponse#apiError`, `#okApiError`, `#badRequestApiError` |
| `POST /api/v2/international-standing-orders/execute` | `POST /api/v2/international-standing-orders/validation` | 5: `ErrorResponseBridge#resolve`, `#fallback`, `RequestErrorContext#bind`, `#fromHttpHeaders`, `ServiceErrorResponseResolver#resolve` |
| `POST /api/v2/international-standing-orders/execute` | `POST /api/v2/international-standing-orders/receipt` | 3: `StandingOrderTransferControllerV2#standingOrderMatchesCustomer` (business), `RequestErrorContext#bind`, `#fromHttpHeaders` |

**Findings and suggestions. None of these was applied.**

1. **Suggested extra exclusions for error-response plumbing.** Almost every shared method belongs to error-handling
   infrastructure. Counted per pair: `platform.error.RequestErrorContext` 284, `ErrorResponseBridge` 94,
   `platform.ReactiveTransferErrorSupport` 80, `ServiceErrorResponseResolver` 46 and `TransferErrorResponse` 25.
   Proposed setting:

   ```
   pke.discovery.shared-logic.exclude=(?i).*(util|config|constant|logging|logger|dto|mapper|properties|generated|helper|\.platform\.error\.|errorsupport|errorresponse|errorcontext|logsupport).*
   ```

   With that exclusion, **1 of the 47** service pairs remains: standing-order execute ↔ receipt, through
   `standingOrderMatchesCustomer`. `Helper` matched no class in this repository, but keep it for other OCS services.
   `*LogSupport` classes (e.g. `AccountTransferRoutingLogSupport`) are logging wrappers that today's `logging`
   pattern misses.
2. **Gateway duplicates.** Gateway APIs resolve to the same handlers as their service APIs, so each logical pair is
   repeated up to 3 × 3 times (service–service 47, mixed 73, gateway–gateway 25). Suggestion for M7 or a small fix:
   derive `SHARES_LOGIC_WITH` between service APIs only. Gateway APIs reach the link through their route.
3. **Shallow call graph.** Only 6 of 175 log statements are reachable from any handler within 4 `CALLS` hops. The
   reactive code (lambdas, `WebClient` chains) leaves many calls unresolved. This limits the log trail and shared
   logic, and is the same symbol-resolution open item as M2–M5. The explained log line was in an unreachable method,
   so the engine reported the match and the method but no API, no next-expected logs and no codes. It did not guess.

## 7. Deviations from the prompt

1. **The interview is a service, not a `JourneySourceAdapter`.** That SPI reads files; an interview has none.
   `JourneyInterviewService` writes the same graph shape through the writer (ADR-017).
2. **Two extra tools**, `start_journey_interview` and `answer_journey_interview`, so the interview can be driven over
   MCP.
3. **No MCP tool for registering repositories or documents** (not in the prompt's list). `pke-bootstrap` uses REST
   with `curl` and the same key for those two steps.
4. **The STDIO launcher is not covered by an automated test.** The tests use the HTTP transport. STDIO shares the
   tools and the key check (`StdioLogin` refuses to start without a valid key) but was not started in this
   milestone.
5. **Execution policies cover MCP `trigger_ingestion` and `submit_evolution_request`.** REST pipeline starts are
   governed by roles only.
6. **Fixtures are hand-authored** (no `OPENAI_API_KEY`): the `journey-interview` eval case and the two model turns
   of the scripted interview.

## 8. Problems and open questions

- **KGP must change before it can call PKE.** Its `PkeClient` sends no key and defaults to port 8085 (KGP D-53).
  Until it sends `Authorization: Bearer $PKE_API_KEY` to `http://localhost:8089`, every call gets 401. This needs a
  KGP-side change; this repository does not edit KGP.
- **Shared-logic exclusions and the layer restriction** (§6 findings 1 and 2) are product decisions. Both are
  configuration or small changes.
- **Key lifecycle**: keys have no expiry and are revoked by name; audit events have no retention policy.
- Earlier open items are unchanged: real embeddings and prompts (needs the key), library classpath and reactive call
  resolution (finding 3), error-constant mapping, Word documents without headings.

## 9. Git

Committed as `M6: MCP server, API-key security, audit, journey interviews, Claude Code skills` by William Thomas
<pkwilliamthomas@gmail.com> and pushed to `origin` main. No key, real-repository content or company identity is in
the commit; the smoke used a throwaway database and the scratchpad.

## Next milestone

**M7** (`docs/prompts/M7-PROMPT.md`): autonomous evolution (L1/L2/L3), isolated build and test, activation,
recovery, and the full demo scenario. It consumes the evolution requests from M5 and `submit_evolution_request`.
