# definitions/

Git-versioned metadata the engine loads at runtime. Format and promotion rules: `docs/adr/ADR-010-schema-definitions-and-identity.md`.

- `meta/` — JSON Schema 2020-12 meta-schemas that every definition is validated against.
- `schema/` — entity types, relationship types and properties (YAML).
- Adding a file under `schema/` and restarting (or `POST /api/schema/...`) registers it. A name + version is
  immutable: change the content, bump the version. Only backwards-compatible changes are accepted for now.
- `skills/<name>/SKILL.md` — Claude-compatible skills (front matter: `meta/skill.schema.json`) with `evals/*.json`.
  A name + version is immutable; the highest version is active. Format and runtime: `docs/adr/ADR-013-*.md`.
- `pipelines/*.yaml` — pipelines (`meta/pipeline.schema.json`): steps are `PipelineStep` beans or `skill:<name>`.
- `policies/relationships.yaml` — relationship policies (`meta/relationship-policies.schema.json`).
- `queries/*.yaml` — read-only graph query templates for retrieval (`meta/query-template.schema.json`), checked by the
  Cypher guard at startup (ADR-014).

Git is the source of these definitions; the engine's schema registry decides which versions are active.
See `docs/IMPLEMENTATION_PLAN.md` §6 and §9.
