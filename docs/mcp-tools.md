# MCP tools (M6)

Endpoint: `http://localhost:8089/mcp` (streamable HTTP, `Authorization: Bearer <key>`), or the STDIO launcher
`bin/pke-mcp-stdio.sh` (`PKE_API_KEY`). Setup: `docs/claude-code-setup.md`. Server name `project-knowledge-engine`.

Every tool calls the **same application service** as its REST endpoint (ArchUnit: `api.mcp` uses no REST class and
nothing below the services), and returns the same JSON, as one text content item. `McpIT` checks the equivalence for
`get_entity`, `list_journeys`, `get_journey`, `list_skills`, `find_brd_sections` and `search_knowledge`.

- **Reading tools** need a READER key (any key: every role includes READER).
- **Changing tools** need CONTRIBUTOR, are written to the audit log (`:AuditEvent {channel: 'MCP', action: 'tool …',
  outcome: OK|DENIED|ERROR}`), and two of them are checked against the execution policy
  (`definitions/policies/execution.yaml`). No tool executes code.
- An error (unknown id, invalid input, missing role, policy refusal) is returned as a tool result with `isError: true`
  and the reason as text, e.g. `API key 'reader' does not have the CONTRIBUTOR role`.

Examples below are from the demo corpus (`demo/`), shortened with `…`. Ids are `pke_id`s (UUIDs).

| Tool | Role | Same as (REST) |
|---|---|---|
| `search_knowledge` | READER | `POST /api/retrieval/search` |
| `get_task_context` | READER | `POST /api/context` |
| `get_entity` | READER | `GET /api/entities/{id}` |
| `traverse_dependencies` | READER | `GET /api/entities/{id}/neighbourhood` |
| `find_affected_components` | READER | `POST /api/retrieval/templates/impact-of-method/run` |
| `list_journeys` | READER | `GET /api/journeys` |
| `get_journey` | READER | `GET /api/journeys/{idOrKey}` |
| `get_requirements` | READER | `POST /api/retrieval/templates/requirements-of-api/run` |
| `find_brd_sections` | READER | `GET /api/sources/sections/search` |
| `get_evidence` | READER | `POST /api/retrieval/templates/evidence-of/run` |
| `compare_snapshots` | READER | `GET /api/snapshots/diff` |
| `get_schema` | READER | `GET /api/schema/{entity-types,relationship-types}[/{name}]` |
| `list_skills` | READER | `GET /api/skills` |
| `list_open_questions` | READER | `GET /api/questions` |
| `explain_log_trail` | READER | `POST /api/logs/explain` |
| `answer_question` | CONTRIBUTOR | `POST /api/questions/{id}/answer` |
| `trigger_ingestion` | CONTRIBUTOR + policy | `POST /api/pipelines/{id}/executions` |
| `submit_evolution_request` | CONTRIBUTOR + policy | (MCP only; listed by `GET /api/evolution/requests`) |
| `start_journey_interview` | CONTRIBUTOR | (MCP only) |
| `answer_journey_interview` | CONTRIBUTOR | (MCP only) |

## Reading

### `search_knowledge`
Hybrid (keyword + vector) search. Arguments: `query` (required), `k` (default 10), `types` (entity types).
```json
{"query": "daily transfer limit", "k": 3}
```
```json
{"query": "daily transfer limit", "generation": "hashing-384-g1", "collections": ["KnowledgeCard_text", "LogStatement_template", "…"],
 "hits": [{"id": "6c1e…", "kind": "entity", "type": "KnowledgeCard", "label": "BR-TL-01",
           "text": "A customer cannot transfer more than QAR 50,000 per day.", "score": 0.0328,
           "sources": ["vector", "fulltext"], "details": {"…": "…"}}, "…"],
 "millis": 41, "warnings": []}
```

### `get_task_context`
Context for a coding task in four sections (verified, deterministic, inferred, conflicts) with citations and a
Markdown rendering. Arguments: `task` (required), `budgetTokens` (default 1500).
```json
{"task": "Add a currency check to transfer validation"}
```
```json
{"task": "Add a currency check to transfer validation", "budgetTokens": 1500, "usedTokens": 1210, "truncated": 0,
 "verified": [], "deterministic": [{"id": "…", "type": "JavaMethod", "label": "TransferService#validate(TransferRequest)",
   "epistemic": "EXTRACTED_DETERMINISTIC", "citations": ["repo:transfer-service@a1b2c3d#src/main/java/com/demo/transfer/TransferService.java:41"], "…": "…"}],
 "inferred": ["…"], "conflicts": [], "citations": ["…"], "templates": ["impact-of-method"],
 "markdown": "## Deterministic facts\n- TransferService#validate(TransferRequest) …", "millis": 380}
```

### `get_entity`
Arguments: `id`.
```json
{"id": "0b6f…"}
```
```json
{"id": "0b6f…", "type": "ApiOperation", "version": 1, "status": "CURRENT", "epistemic": "EXTRACTED_DETERMINISTIC",
 "createdSeq": 57, "updatedSeq": 57, "scope": "main",
 "properties": {"layer": "service", "service": "transfer-service", "httpMethod": "POST",
                "pathTemplate": "/api/v2/international-transfers/validation", "gatewayKey": ""},
 "evidence": [{"sourceArtifactId": "repo:transfer-service", "location": "…/TransferController.java:28", "…": "…"}]}
```

### `traverse_dependencies`
Entities within `depth` hops (1 to 3, default 1) and the relationships between them. Arguments: `id`, `depth`, `limit`.
```json
{"id": "0b6f…", "depth": 2}
```
```json
{"nodes": [{"id": "0b6f…", "type": "ApiOperation", "…": "…"}, {"id": "9a0d…", "type": "JavaMethod", "…": "…"}],
 "relationships": [{"id": "…", "type": "HANDLED_BY", "from": "0b6f…", "to": "9a0d…"}, "…"]}
```

### `find_affected_components`
What a change to a method affects: callers (3 hops), their APIs, journeys and tests (template `impact-of-method`).
Arguments: `methodId`, `limit`.
```json
{"methodId": "9a0d…"}
```
```json
{"cypher": "MATCH (m:JavaMethod {pke_id: $seedId}) …",
 "rows": [{"api": "POST /api/v2/international-transfers/validation", "journey": "international-transfer", "test": "…", "…": "…"}],
 "millis": 12}
```

### `list_journeys`
```json
{}
```
```json
[{"id": "4e2a…", "journeyKey": "international-transfer", "name": "International Transfer", "steps": 3,
  "sources": ["journey.gatling", "journey.java-journey", "journey.jmeter", "journey.postman"]}]
```

### `get_journey`
Arguments: `idOrKey`.
```json
{"idOrKey": "international-transfer"}
```
```json
{"id": "4e2a…", "journeyKey": "international-transfer", "name": "International Transfer",
 "steps": [{"order": 1, "id": "…", "labels": ["getAccounts (journey.java-journey)", "…"],
            "apis": [{"httpMethod": "POST", "pathTemplate": "/api/v1/get-ft-accounts", "…": "…"}], "handlers": ["…"]}, "…"],
 "feeds": [{"from": 1, "to": 2, "field": "accountId", "…": "…"}], "sources": ["…"], "coveredBy": ["…"]}
```

### `get_requirements`
Cards (requirements, rules) an API implements, directly or through its handler's calls. Arguments: `apiId`, `limit`.
```json
{"apiId": "0b6f…"}
```
```json
{"cypher": "…", "rows": [{"card": "BR-TL-01", "text": "A customer cannot transfer more than QAR 50,000 per day.",
  "via": "TransferService#validate(TransferRequest)", "epistemic": "INFERRED_AI"}], "millis": 9}
```

### `find_brd_sections`
Full-text search over document sections. Arguments: `query` (required), `limit` (default 10).
```json
{"query": "limit"}
```
```json
[{"id": "…", "artifactKey": "doc:international-transfer-brd", "version": "3f9c…", "order": 6,
  "headingPath": ["International Transfer BRD", "Transfer limits"], "heading": "Transfer limits",
  "text": "A customer cannot transfer more than …", "score": 1.42}]
```

### `get_evidence`
The evidence behind an entity or relationship (template `evidence-of`). Arguments: `id`, `limit`.
```json
{"id": "0b6f…"}
```
```json
{"cypher": "…", "rows": [{"sourceArtifactId": "repo:transfer-service", "sourceVersion": "a1b2c3d…",
  "location": "src/main/java/com/demo/transfer/TransferController.java:28", "extractionMethod": "java.spring-mapping",
  "excerpt": "@PostMapping(TransferPaths.VALIDATION)", "section": null}], "millis": 5}
```

### `compare_snapshots`
Arguments: `fromSnapshotId`, `toSnapshotId` (from `GET /api/snapshots`).
```json
{"fromSnapshotId": "uat-…", "toSnapshotId": "pilot-…"}
```
```json
{"from": {"id": "…", "label": "uat", "seq": 120, "…": "…"}, "to": {"id": "…", "label": "pilot", "seq": 164, "…": "…"},
 "configuration": {"schema": {"changed": []}, "release": {"from": "v0.5.0", "to": "v0.6.0"}, "…": "…"},
 "entities": {"ADDED": {"JavaMethod": [{"id": "…", "label": "CurrencyPolicy#check(String)"}]}},
 "relationships": {"INVALIDATED": {"CALLS": ["…"]}, "ADDED": {"CALLS": ["…"]}},
 "counts": {"entitiesAdded": 1, "relationshipsAdded": 1, "relationshipsInvalidated": 1}}
```

### `get_schema`
Without arguments: both lists. With `kind` (`ENTITY_TYPE` or `RELATIONSHIP_TYPE`): one list. With `kind` and `name`:
one definition.
```json
{"kind": "ENTITY_TYPE", "name": "UserJourney"}
```
```json
{"kind": "ENTITY_TYPE", "name": "UserJourney", "version": "1.0.0", "status": "ACTIVE", "hash": "…",
 "definition": {"properties": {"journeyKey": {"type": "string", "required": true}, "…": "…"},
                "identity": {"strategy": "naturalKey", "naturalKey": ["journeyKey"]}, "…": "…"}}
```

### `list_skills`
```json
{}
```
```json
[{"name": "journey-interview", "version": "1.0.0", "description": "Interviews a person about one user journey …",
  "tools": [], "appliesTo": ["UserJourney"], "evalCases": ["evals/first-question.json"], "runtime": "both"}, "…"]
```

### `list_open_questions`
Arguments: `status` (`open` default, `answered`, `all`), `limit`.
```json
{}
```
```json
[{"id": "7d3e…", "kind": "CARD_CONFLICT", "status": "OPEN",
  "question": "The BRD now says QAR 50,000 per day; the existing card says QAR 30,000. Which is right?",
  "optionsJson": "[{\"id\":\"keep-existing\",…},{\"id\":\"use-new\",…},{\"id\":\"keep-both\",…}]",
  "documentKey": "doc:international-transfer-brd", "concerns": ["…"], "citedSections": ["…"], "…": "…"}]
```

### `explain_log_trail`
Arguments: `logLine` (required), `apiId`.
```json
{"logLine": "2026-11-17 10:15:02 INFO  TransferService - Validating transfer to B-1001"}
```
```json
{"line": "2026-11-17 10:15:02 INFO  TransferService - Validating transfer to B-1001",
 "match": {"id": "…", "label": "INFO Validating transfer to {}", "method": "com.demo.transfer.TransferService#validate(TransferRequest)"},
 "api": "POST /api/v2/international-transfers/validation",
 "nextExpected": [{"label": "WARN Transfer above the daily limit", "…": "…"}, {"label": "ERROR Currency {} is not supported", "…": "…"}],
 "likelyFailingMethod": "com.demo.transfer.TransferService#validate(TransferRequest)", "errorCodes": ["003040", "003041"],
 "explanation": {"explanation": "The request stopped inside validate …", "likelyCause": "…", "checkNext": ["…"]},
 "explanationStatus": "OK"}
```

## Changing (CONTRIBUTOR, audited)

### `answer_question`
Applies the chosen option through the single writer as `HUMAN_VERIFIED`, with the comment as evidence.
Arguments: `questionId`, `option`, `comment`.
```json
{"questionId": "7d3e…", "option": "use-new", "comment": "The limits summary is the approved value"}
```
```json
{"question": {"id": "7d3e…", "status": "ANSWERED", "answer": "use-new", "answeredBy": "key:claude-code", "…": "…"},
 "applied": {"operationId": "…", "items": [{"kind": "entity", "id": "…", "type": "KnowledgeCard", "outcome": "UPDATED",
   "version": 3, "seq": 171}], "firstSeq": 171, "lastSeq": 171}}
```

### `trigger_ingestion`
Starts a registered pipeline in the background, if the execution policy of this profile allows it. Arguments:
`pipeline`, `input` (the pipeline's input object), `force`.
```json
{"pipeline": "code-on-commit", "input": {"repository": "transfer-service", "tag": "uat"}}
```
```json
{"id": "019a…", "pipelineId": "code-on-commit", "pipelineVersion": "1.0.0", "status": "PENDING",
 "actor": "key:claude-code", "…": "…"}
```
Refused (profile `prod`, pipeline `reindex`): `isError: true`, `The prod execution policy does not allow starting
pipeline 'reindex' through MCP; allowed: [code-ingest, code-on-commit, brd-ingest, brd-update]`.

### `submit_evolution_request`
Records a request for the evolution pipeline (M7); nothing runs. Arguments: `kind` (`NEW_RELATIONSHIP_TYPE`,
`NEW_ENTITY_TYPE`, `NEW_EXTRACTOR`, `NEW_SKILL`, as the policy allows), `subject`, `reason`, `details`.
```json
{"kind": "NEW_ENTITY_TYPE", "subject": "FeatureFlag", "reason": "Journeys depend on feature flags",
 "details": {"properties": ["flagKey"]}}
```
```json
{"id": "a51c…", "kind": "NEW_ENTITY_TYPE", "subject": "FeatureFlag", "status": "OPEN"}
```

### `start_journey_interview` and `answer_journey_interview`
The `journey-interview` skill asks one question per turn; relay it to the user and send their answer. When the draft
is complete, each step's API is matched against the graph (same method; exact path, then path suffix, then path
containment). One match is linked; several or none become a numbered question. When every step is resolved, the
journey is written as `HUMAN_VERIFIED` (`UserJourney -HAS_STEP-> JourneyStep -CALLS_API-> ApiOperation`, evidence
method `journey.interview`). The session is kept in `:InterviewSession` between turns.

```json
{"journeyKey": "transfer-interview", "name": "Transfer by interview"}
```
```json
{"sessionId": "019a…", "journeyKey": "transfer-interview", "status": "ASKING",
 "question": "What does the user do first when they start this journey?", "steps": []}
```
```json
{"sessionId": "019a…", "answer": "They validate the transfer first, then they execute it. Nothing else."}
```
```json
{"sessionId": "019a…", "status": "ASKING",
 "question": "Step 1 'Validate the transfer' (POST /api/v2/international-transfers/validation) matches 2 APIs. Which API does it call? 1) POST /api/v2/international-transfers/validation [gateway gateway] 2) POST /api/v2/international-transfers/validation [service transfer-service]. Answer with the number, or 'none' to keep the step without an API.",
 "steps": [{"order": 1, "name": "Validate the transfer", "method": "POST", "path": "/api/v2/international-transfers/validation"},
           {"order": 2, "name": "Execute the transfer", "method": "POST", "path": "/api/v3/international-transfers/execute"}]}
```
```json
{"sessionId": "019a…", "answer": "1"}
```
After the last answer:
```json
{"sessionId": "019a…", "status": "COMPLETED", "journeyId": "…", "eventsWritten": 11,
 "steps": [{"order": 1, "…": "…", "apiId": "…"}, {"order": 2, "…": "…", "apiId": "…"}]}
```
