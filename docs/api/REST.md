# REST API (M2)

Base URL `http://localhost:8080`. JSON unless stated. Errors are RFC 9457 problem details. Validation errors list
every problem in `violations: [{path, message}]`.

| Status | Meaning |
|---|---|
| 400 | Invalid input (schema, identifiers, values, evidence). Nothing was written |
| 404 | Entity, relationship or type not found |
| 409 | Conflict: an immutable schema version, an older version, or a breaking schema change |

Optional header `X-PKE-Actor: <name>` is recorded on change events as `api:<name>`. **There is no authentication
yet (M6).** Do not expose the service beyond localhost.

## Schema registry

| Method and path | Purpose |
|---|---|
| `GET /api/schema/entity-types` · `GET /api/schema/relationship-types` | Current definitions |
| `GET /api/schema/{entity-types\|relationship-types}/{name}` | One current definition |
| `GET /api/schema/{entity-types\|relationship-types}/{name}/versions` | All registered versions |
| `POST /api/schema/entity-types` · `POST /api/schema/relationship-types` | Register (body `application/yaml` or `application/json`, same format as `definitions/schema/`). `201` registered, `200` unchanged |
| `POST /api/schema/validate` | Dry run. Returns `{valid, violations, breakingChanges, currentVersion}` and writes nothing |

```bash
curl -X POST localhost:8080/api/schema/relationship-types -H 'Content-Type: application/yaml' --data-binary @- <<'EOF'
kind: RelationshipType
name: REGULATORY_DEPENDENCY
version: 1.0.0
description: Indicates a dependency on a regulatory control
category: business
sourceTypes: [BusinessFeature]
targetTypes: [RegulatoryControl]
properties: { rationale: { type: text } }
EOF
```

## Knowledge writes

| Method and path | Purpose |
|---|---|
| `POST /api/knowledge/changes` | `{entities[], relationships[], invalidations[], operationId?}` in **one transaction** |
| `POST /api/entities` | One entity write |
| `POST /api/relationships` | One relationship write |
| `POST /api/relationships/{id}/invalidate` | `{reason?, invalidAt?, evidence?}`. Ends current validity; history is kept |

Entity write: `{type, id?, properties, epistemic, validAt?, evidence[]}`. Relationship write: `{type, subject,
object, properties, epistemic, validAt?, evidence[]}`, where `subject`/`object` are `{id}` or
`{type, key: {<natural key>}}`. Evidence: `{sourceArtifactId, extractionMethod, sourceVersion?, location?,
contentHash?, pipelineVersion?, skillVersion?, modelProvider?, modelId?, excerpt? (≤2000 chars), verification?,
confidence? (0..1, stored as uncalibrated), epistemic?}`. `epistemic` is one of `OBSERVED,
EXTRACTED_DETERMINISTIC, INFERRED_AI, HUMAN_VERIFIED, CONTRADICTED, SUPERSEDED`. AI-inferred evidence needs `modelId`.

Writes state the **full current** domain properties: properties left out are removed (the previous values stay in
the version history). The response lists each item's outcome (`CREATED`, `UPDATED`, `UNCHANGED`, `EVIDENCE_ADDED`,
`INVALIDATED`, `REVALIDATED`), its version and its `seq`, plus `firstSeq`/`lastSeq`. Re-sending the same input
returns `UNCHANGED` and writes nothing.

## Reads

| Method and path | Purpose |
|---|---|
| `GET /api/entities?type=&offset=&limit=` | Page of entities (limit ≤ 200) |
| `GET /api/entities/{id}` | Entity, properties and evidence |
| `GET /api/entities/{id}/versions` | Version history (`validFromSeq`/`validToSeq`, property snapshot) |
| `GET /api/entities/{id}/relationships?includeInvalid=false` | Relationships where the entity is subject or object |
| `GET /api/relationships/{id}` · `GET /api/relationships/{id}/versions` | Assertion, evidence and history |
| `GET /api/history/events?afterSeq=0&limit=100&subjectId=` | Change events in `seq` order |
| `GET /api/integrity` | Assertion ↔ native edge consistency report |
| `GET /api/system/info` · `GET /actuator/health` | Versions and model availability; health |

## Sources (M2)

| Method and path | Purpose |
|---|---|
| `POST /api/sources/documents` | Multipart `file` (+ optional `key` = `doc:<slug>`, `name`). `201` new version, `200` unchanged/reparsed/reinstated. Returns the version, `parseStatus` (`PARSED`, `EMPTY`, `OCR_REQUIRED`) and section count |
| `POST /api/sources/repositories` | `{name, path, serviceName?}`: registers a local Git repository (`repo:<name>`). `serviceName` must match the gateway route's service (e.g. `international-transfer-service`) |
| `GET /api/sources?kind=` · `GET /api/sources/{key}` | Sources; one source with its current version |
| `GET /api/sources/{key}/versions` | Document versions (content hashes) or recorded commits |
| `GET /api/sources/{key}/sections?version=` | Sections of the current (or a given) document version |
| `DELETE /api/sources/{key}` | `{reason?}`: marks the source removed; versions, sections and evidence are kept |
| `GET /api/sources/sections/search?q=&limit=` | Fulltext search over current sections (not embedded) |

## Ingestion and journeys (M2)

| Method and path | Purpose |
|---|---|
| `POST /api/ingestion/runs` | `{kind: REPOSITORY\|ROUTES, target: <repository name>, mode: FULL\|INCREMENTAL}`. `202` with the run; `?wait=true` runs it before replying |
| `GET /api/ingestion/runs?target=&limit=` · `GET /api/ingestion/runs/{id}` | Runs: `status` (`PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`), commits, `statsJson` (outcome counts, journeys, routes, failures), `unresolvedJson` (first 200) |
| `POST /api/ingestion/runs/{id}/retry` | New attempt with the same parameters |
| `GET /api/journeys` | Journeys with step counts and sources |
| `GET /api/journeys/{idOrKey}` | Ordered steps with their gateway APIs, handler methods and per-source labels; data flow (`FEEDS`); covering suites |
