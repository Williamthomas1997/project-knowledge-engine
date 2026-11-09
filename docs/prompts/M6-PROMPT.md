# M6: MCP server, security and Claude Code integration (spec Phase 6, §18, §21)

Precondition: M5 accepted.

## 1. MCP server
Spring AI MCP server starter (WebMVC, streamable HTTP) plus a STDIO launcher. Tools call the **same application
services** as REST (ArchUnit: `api.mcp` uses only application services). Stable, descriptive names and input schemas:
`search_knowledge`, `get_task_context`, `get_entity`, `traverse_dependencies`, `find_affected_components`,
`get_journey`, `list_journeys`, `get_requirements`, `find_brd_sections`, `get_evidence`, `compare_snapshots`,
`get_schema`, `list_skills`, `list_open_questions`, `answer_question`, `trigger_ingestion` (policy-checked),
`explain_log_trail`, `submit_evolution_request` (policy-checked; never executes code directly).
Document every tool in `docs/mcp-tools.md` with an example call and response.

## 2. Prompt-driven journey capture
`JourneySourceAdapter` "interview": skill `journey-interview` driven over MCP. The tool returns the next question,
William answers in chat, and the session persists in `:InterviewSession` until the journey is complete. It then
writes UserJourney/steps through the graph writer as `HUMAN_VERIFIED` and links the steps to known ApiOperations,
or asks when a step is ambiguous.

## 3. Claude Code skills (in `claude-skills/`, installable into `.claude/skills/`)
- `pke-bootstrap`: register a project (repos, BRD folder, QA/journey folder, route export path), run the ingest
  pipelines, then walk William through the open questions.
- `pke-context`: before a coding task, call `get_task_context` and summarise it.
- `pke-questions`: list and answer open questions in chat.
- `pke-journey`: start a journey interview.
Plus `.mcp.json` / Claude Code MCP config examples in `docs/claude-code-setup.md`.

## 4. Security
API-key authentication (keys hashed in Neo4j, created via CLI) with roles READER, CONTRIBUTOR, SCHEMA_ADMIN,
EVOLUTION_OPERATOR on REST and MCP; an audit log of mutating calls (`:AuditEvent`); actuator locked down
(health details only for authenticated roles, the M1 TODO); execution policies per profile (dev/test/prod) in
`definitions/policies/execution.yaml`; secrets only from env vars; a test that greps logs and reports for key patterns.

## 5. Tests
An MCP client test (the official MCP Java SDK client) calls each tool against the demo corpus; REST and MCP return
equivalent results for the same service call; unauthorised calls are rejected; the journey interview completes a
journey in a scripted conversation (replay model); `answer_question` applies the decision.
