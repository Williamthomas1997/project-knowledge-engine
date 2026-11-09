# ADR-010: Schema definition format, promotion rules and identity

Status: **accepted** (M1, 2026-11-09). Implements plan §4 (ADR-002) and §6 (ADR-004).

## Definition format

- YAML (or JSON) files under `definitions/schema/entities/` and `definitions/schema/relationships/`, one type
  per file, versioned in Git and packaged into the jar as `classpath:definitions/**`. Set `pke.definitions.dir` to
  read a working copy instead.
- Validated against JSON Schema 2020-12 meta-schemas, `definitions/meta/entity-type.schema.json` and
  `relationship-type.schema.json`, using `com.networknt:json-schema-validator` 3.0.9. That version runs on
  **Jackson 3**, so it shares Boot 4's Jackson and needs no second ObjectMapper (ADR-000).
- Semantic checks in code (`SchemaDefinitionParser`): the natural key, discriminator, `embedding.indexFields`
  and retrieval fields must be declared properties. Natural-key and discriminator properties must be required.
  `indexFields` must be string or text. `enum` and `pattern` are only allowed on textual properties, and patterns
  must compile. Names reserved by the engine (`Entity`, `Evidence`, `SUPPORTED_BY` …, see `ReservedNames`) and
  `pke_*` property names are rejected. Registry checks in `SchemaService`: relationship endpoints must be
  registered entity types, or `*`.
- Entity type keys: `name`, `version` (semver), `displayName`, `description`, `category`, `status`
  (`active|deprecated|retired`), `properties` (`string|text|integer|number|boolean|datetime|string-list`, plus
  `required`, `enum`, `pattern`, `deprecated`), `identity`, `evidence {required, minItems}`, `embedding
  {indexFields, collection}` (stored only; ADR-007 behaviour arrives in M4), `extraction` (free-form hints),
  `retrieval {displayProperty, searchFields}`.
- Relationship type keys add `category` (required, so business and technical relationships are always told apart),
  `sourceTypes`, `targetTypes`, `identity.discriminator`, `temporal.validity`, `policy {discovery, approval}` and
  `queryHints {traverse, direction, weight}`.

## Promotion: Git to the running registry

1. At startup, `SchemaBootstrap` loads the current definitions from Neo4j into the in-memory `SchemaRegistry`, then
   registers definitions from Git that aren't there yet. Entity types are registered before relationship types.
   REST registration (`POST /api/schema/...`) follows the same path.
2. Rules: **a name + version is immutable**. Re-registering the same content is a no-op; the same version with
   different content is `409`. A new version must be higher than the current one (a Git file older than a version
   registered through REST is skipped and logged), and **backwards compatible** (`SchemaCompatibility`): no
   removed properties, type changes, optional→required changes, narrowed enums, new patterns, new required
   properties, identity changes or narrowed endpoints. Breaking changes are rejected with the reasons, because
   declared migrations are not implemented yet (spec §13.2, planned later).
3. Each registration is a `:SchemaDefinition` node (all versions kept, one `current: true`) plus a
   `SCHEMA_REGISTERED` event, written in one transaction through the single writer.
4. Lifecycle: `deprecated` types still accept writes. `retired` types reject writes but stay readable, so historical
   knowledge remains interpretable (§13.4).

## Identity

| Strategy | Entity id | Notes |
|---|---|---|
| `naturalKey` | UUIDv5(ENTITY ns, type ␟ canonical-JSON(key values in declared order)) | Deterministic. Callers can refer to an entity by `{type, key}` without a lookup. A supplied id must match |
| `explicit` | supplied id (`^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$`) | For external canonical ids |
| `generated` | UUIDv7, or a supplied id | Not idempotent unless the caller supplies the id |

- Relationship id = UUIDv5(REL ns, type ␟ subjectId ␟ objectId ␟ discriminator values). If an endpoint changes, it is
  a different relationship. The old one is invalidated, never rewritten (plan §4).
- Evidence id = UUIDv5(EVIDENCE ns, subject id ␟ sourceArtifactId ␟ sourceVersion ␟ location ␟ extractionMethod ␟
  contentHash). Version id = UUIDv5(VERSION ns, subject ␟ version).
- Content hashes detect change and are **never** identity. Values are hashed as canonical JSON, so `1` and `"1"`
  are different keys.
- Rename and move detection (aliases, `ENTITY_RENAMED`) and AI-assisted resolution arrive with Java ingestion (M2)
  and reconciliation (M3).
