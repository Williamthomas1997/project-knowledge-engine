# definitions/

Git-versioned metadata the engine loads at runtime. Format and promotion rules: `docs/adr/ADR-010-schema-definitions-and-identity.md`.

- `meta/` — JSON Schema 2020-12 meta-schemas that every definition is validated against.
- `schema/` — entity types, relationship types and properties (YAML).
- Adding a file under `schema/` and restarting (or `POST /api/schema/...`) registers it. A name + version is
  immutable: change the content, bump the version. Only backwards-compatible changes are accepted for now.
- `pipelines/`, `skills/`, `policies/`, `prompts/` (later milestones) — pipeline definitions, skills (Markdown + YAML front matter),
  relationship/conflict policies and prompt templates.

Git is the source of these definitions; the engine's schema registry decides which versions are active.
See `docs/IMPLEMENTATION_PLAN.md` §6 and §9.
