# ADR-017: MCP server, API-key security, audit, execution policies and journey interviews

Status: **accepted** (M6, 2026-11-17). Implements plan phase 6, MASTER_SPEC §18 and §21, and the M5 review items
(restore and migrations need SCHEMA_ADMIN; a key for KGP).

## Decisions

### MCP
- **Spring AI MCP server starter (WebMVC), streamable HTTP on `/mcp`, sync server.** Tools are `@McpTool` methods
  on one component, `api.mcp.KnowledgeTools`, which calls the same application services as the REST controllers and
  returns the same records (equivalence tested by `McpIT`). ArchUnit: `api.mcp` may not depend on `api.rest`, the
  Neo4j driver, or `GraphReader`/`GraphTransactions`/`GraphSessions`/`KnowledgeWriter`; it is the only package
  outside the adapters allowed to use `org.springframework.ai` (for the annotations).
- **STDIO launcher**: profile `mcp-stdio` (no web server, banner and console logging off, logs to a file) and
  `bin/pke-mcp-stdio.sh`. The process authenticates once at startup with `PKE_API_KEY` (`security.StdioLogin`) and
  refuses to start without a valid key.
- Tool names are stable snake_case verbs from the M6 prompt, plus `start_journey_interview` and
  `answer_journey_interview`. Skill-level read-only/destructive hints are set on every tool.

### Security
- **Spring Security, stateless, API keys only.** `Authorization: Bearer <key>` or `X-API-Key`. No sessions, no CSRF
  (no browser clients, no cookies), no HTTP Basic, no generated user (`UserDetailsServiceAutoConfiguration` is
  excluded, so no generated password is ever logged).
- **Keys**: `pke_` + 43 base64url characters (256 random bits). Stored as `(:ApiKey {name, keyHash, prefix, roles,
  createdAt, createdBy, revokedAt})`; `keyHash` is SHA-256 (unique constraint). A fast hash is enough because the
  keys are random, not chosen by people; a slow password hash would only add latency to every request. The key is
  printed once by the CLI (`bin/pke-keys.sh create|list|revoke`, the engine jar with `pke.cli=keys` and no web
  server) and never logged (`CreatedKey.toString` hides it).
- **Roles** (higher includes lower): READER < CONTRIBUTOR < SCHEMA_ADMIN; EVOLUTION_OPERATOR includes CONTRIBUTOR.
  URL rules: every GET and the read-only POSTs (search, context, template runs, log explanation, schema validation)
  need READER; schema registration, `POST /api/history/restore` and `POST /api/schema/migrations` need SCHEMA_ADMIN;
  every other `/api` call needs CONTRIBUTOR; `/mcp` needs READER, and the changing tools check CONTRIBUTOR
  themselves (`security.Access`, which reads the same `SecurityContext`; the sync MCP server runs tools on the
  request thread).
- **Actor**: change events record `key:<name>`; the `X-PKE-Actor` header is kept as who the key acts for
  (`key:kgp/william`). The header alone no longer identifies anyone.
- **Audit**: one `:AuditEvent {at, actor, roles, channel, action, outcome}` per mutating `/api` call (servlet filter,
  final status, also 401/403) and per changing MCP tool call (`OK`, `DENIED`, `ERROR`). Operational records, no change
  events (like `:ModelCall`). Reads are not audited.
- **Actuator**: `/actuator/health` is open and shows details only to an authenticated READER
  (`show-details: when-authorized`); every other actuator endpoint needs READER. This closes the M1 TODO.
- **Execution policies** (`definitions/policies/execution.yaml`, validated by `meta/execution-policy.schema.json`,
  profile from `pke.security.policy-profile` / `PKE_POLICY_PROFILE`, default `dev`): which pipelines
  `trigger_ingestion` may start and which evolution-request kinds `submit_evolution_request` accepts. `prod` keeps
  `reindex` and `discover-relationships` for operators over REST. Roles are checked first, then the policy.
- **Secrets only from environment variables**: Neo4j password, OpenAI key, `PKE_API_KEY`. `SecurityIT` checks that
  keys never appear in captured logs, and scans docs, skills, definitions and test reports for PKE and OpenAI key
  patterns.
- **Port 8089** by default (`PKE_PORT`): 8080 is taken on the development machine and KGP calls PKE on 8089.

### Journey interviews
- **The interview is a service, not a file adapter.** `JourneySourceAdapter` reads files from a repository; an
  interview has no file. `ingest.journey.JourneyInterviewService` produces the same graph shape as the adapters
  (`UserJourney -HAS_STEP {order}-> JourneyStep -CALLS_API-> ApiOperation`) through the single writer, as
  `HUMAN_VERIFIED`, with evidence method `journey.interview` scoped to the session.
- **The model asks, the engine links.** The `journey-interview` skill returns either one question or the complete
  draft with each step's method and path. Each step is matched against the graph's ApiOperations deterministically
  (same method; exact path, then suffix, then containment). One match is linked; several or none become a numbered
  question to the person ("none" keeps the step without an API). The model never chooses between APIs.
- **Session state** (`:InterviewSession {journeyKey, status, stateJson}`): transcript, draft, resolved APIs and the
  pending question, so an interview survives restarts and can be resumed by id. Not knowledge, no change events.

## Consequences
- Every client needs a key, including KGP, `bin/pke-code-on-commit.sh` and the tests (`KnowledgeIT` creates an
  admin key per test). KGP's current client sends no key and targets port 8085; it gets 401 until KGP is updated
  (documented in `docs/claude-code-setup.md`; KGP is not changed from this repository).
- There is no key expiry or rotation schedule; revocation is by name. Keys are per client, not per person.
- Registration of repositories and documents has no MCP tool; `pke-bootstrap` uses REST with `curl`.
- Audit events grow without bound; retention is an operational task (not automated in M6).
