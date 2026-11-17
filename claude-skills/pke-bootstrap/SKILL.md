---
name: pke-bootstrap
description: >-
  Register a project with the Project Knowledge Engine (repositories, BRD folder, QA/journey project, gateway route
  export), run the ingestion pipelines, then go through the open questions with the user. Use when the user wants to
  set up PKE for a project or add a repository or document folder to it.
---
# Bootstrap a project in PKE

Requires a running PKE (default `http://localhost:8089`), the `pke` MCP server, and `PKE_API_KEY` set to a
CONTRIBUTOR key in the shell (`bin/pke-keys.sh create <name> CONTRIBUTOR`). Registration has no MCP tool, so this
skill uses the REST API with `curl`; everything else goes through MCP. Never print the key.

1. **Collect the project layout** from the user (ask only for what is missing): service repositories (local Git
   paths), the BRD folder, the QA/journey project, the gateway route export (`ocs_t_gateway_api_route` INSERT
   script). Confirm the list before registering anything.
2. **Register each repository** (read-only; PKE never writes to it):
   ```bash
   curl -sS -X POST "${PKE_URL:-http://localhost:8089}/api/sources/repositories" \
     -H "Authorization: Bearer $PKE_API_KEY" -H 'Content-Type: application/json' \
     -d '{"name": "<repo-name>", "path": "<local path>", "serviceName": "<service name>"}'
   ```
   The QA project is registered the same way, without `serviceName`. The route export is a server setting
   (`pke.ingestion.route-export-files`); if it is not set, tell the user how to set it and restart.
3. **Ingest the code**: for each repository call `mcp__pke__trigger_ingestion` with pipeline `code-ingest` and input
   `{"repository": "<repo-name>"}`. The execution runs in the background; report its id.
4. **Upload the BRDs** (one call per file; `.md`, `.docx`, `.pdf`):
   ```bash
   curl -sS -F file=@"<path>" -H "Authorization: Bearer $PKE_API_KEY" "${PKE_URL:-http://localhost:8089}/api/sources/documents"
   ```
   Then call `mcp__pke__trigger_ingestion` with `brd-ingest` and `{"documentKey": "doc:<key>"}` (the `key` in the upload response).
5. **Check progress** with `curl -H "Authorization: Bearer $PKE_API_KEY" .../api/pipelines/executions?limit=10`
   until every execution is `COMPLETED` (report `FAILED` ones with their error; do not retry blindly).
6. **Summarise**: `mcp__pke__list_journeys`, and `mcp__pke__search_knowledge` for one or two terms the user cares
   about, so they see what the graph knows.
7. **Open questions**: continue with the `pke-questions` skill.

If `trigger_ingestion` is refused by the execution policy, tell the user which policy profile is active and that an
operator must start that pipeline over REST.
