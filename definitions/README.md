# definitions/

Git-versioned metadata the engine loads at runtime (from M1 on):

- `meta/` — JSON Schema 2020-12 meta-schemas that every definition is validated against.
- `schema/` — entity types, relationship types and properties (YAML).
- `pipelines/`, `skills/`, `policies/`, `prompts/` — pipeline definitions, skills (Markdown + YAML front matter),
  relationship/conflict policies and prompt templates.

Git is the source of these definitions; the engine's schema registry decides which versions are active.
See `docs/IMPLEMENTATION_PLAN.md` §6 and §9.
