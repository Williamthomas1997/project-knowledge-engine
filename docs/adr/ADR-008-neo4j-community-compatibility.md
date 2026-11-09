# ADR-008: Neo4j Community compatibility

Status: **accepted** (M1, 2026-11-09)

## Context

The M1 review asked for Community compatibility. Tests run against `neo4j:2026.10.1-community`. William's Neo4j
Desktop is Enterprise 2026.09, but deployments must not depend on Enterprise features. Enterprise adds property
existence constraints, property type constraints, node key constraints, multiple online databases and online
backup.

## Decision

1. The engine uses only **node property uniqueness constraints** and **range indexes**. `GraphSchemaInitializer`
   creates them at startup with `IF NOT EXISTS`:

   | Kind | Label | Property |
   |---|---|---|
   | unique | `Entity`, `Assertion`, `Evidence`, `EntityVersion`, `AssertionVersion`, `SchemaDefinition` | `pke_id` |
   | unique | `ChangeEvent` | `seq` |
   | unique | `Sequence` | `name` |
   | range index | `Entity` | `pke_type` |
   | range index | `Assertion` | `pke_subjectId`, `pke_objectId`, `pke_status` |
   | range index | `ChangeEvent` | `subjectId` |
   | range index | `SchemaDefinition` | `(kind, name)` |

2. **Required properties and property types are enforced by the schema validator** (`KnowledgeValidator`,
   `PropertyValues`) before any Cypher runs. No existence or type constraints are used.
3. **Dynamic labels and relationship types** (`$(...)`, Neo4j ≥ 5.26) are a Cypher language feature that is
   available in Community (`Neo4jDynamicLabelIT`).
4. **One database.** The engine assumes a single database (`NEO4J_DATABASE`, default `neo4j`). Test isolation uses
   Testcontainers or, on Enterprise Desktop, a separate `pketest` database (test support only).
5. **Backups** use `neo4j-admin database dump` / `load` (offline in Community). The runbook arrives with the
   recovery work in M5.

## Consequences

- If code bypasses the writer, Neo4j itself will not stop missing required properties. The single-writer rule
  (ADR-009, enforced by ArchUnit) is what keeps data valid.
- There are no relationship property indexes yet. Knowledge edges are found through their endpoints, and the
  integrity check scans `pke_relId` edges. Per-type relationship indexes (supported in Community) can be added when
  measurements justify them.
- The engine also runs on Enterprise without changes.
