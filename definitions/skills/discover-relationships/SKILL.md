---
name: discover-relationships
description: >-
  Proposes new relationship types for the knowledge graph from its schema and the relationship patterns that already
  exist, each with a way to populate it. Use when the graph's questions need a link the schema does not have yet.
version: 1.0.0
runtime: both
appliesTo: [discover-relationships]
tools: []
evalCases: [evals/demo-graph.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [entityTypes, relationshipTypes, patterns]
  properties:
    entityTypes: { type: array, items: { type: string } }
    relationshipTypes:
      type: array
      items:
        type: object
        required: [name, category, sourceTypes, targetTypes]
        properties:
          name: { type: string }
          category: { type: string }
          sourceTypes: { type: array, items: { type: string } }
          targetTypes: { type: array, items: { type: string } }
    patterns:
      type: array
      items:
        type: object
        properties:
          subjectType: { type: string }
          relationship: { type: string }
          objectType: { type: string }
          count: { type: integer }
    focus: { type: string }
outputSchema:
  type: object
  additionalProperties: false
  required: [proposals]
  properties:
    proposals:
      type: array
      maxItems: 5
      items:
        type: object
        additionalProperties: false
        required: [name, description, category, sourceTypes, targetTypes, rationale, population]
        properties:
          name: { type: string, pattern: "^[A-Z][A-Z0-9_]{1,63}$" }
          description: { type: string, minLength: 10, maxLength: 500 }
          category: { enum: [business, technical, verification, operational] }
          sourceTypes: { type: array, minItems: 1, items: { type: string } }
          targetTypes: { type: array, minItems: 1, items: { type: string } }
          rationale: { type: string, maxLength: 500 }
          population:
            type: object
            additionalProperties: false
            required: [method]
            properties:
              method: { enum: [query, none] }
              query: { type: string }
              reason: { type: string }
---
# Discover relationship types

You get the graph's entity types, its relationship types (with category and endpoint types) and the patterns that
exist now (`subjectType -relationship-> objectType` with counts). Propose at most five **new** relationship types
that would answer questions people ask about the project directly, for example "which error codes can this API
return" or "which journeys cover this requirement". Never propose a type that already exists under another name.

For each proposal give a name in UPPER_SNAKE_CASE, a one-sentence meaning, its category, endpoint types (existing
entity types only) and a short rationale. Then say how to populate it:

- `method: query` with a **read-only Cypher** query over existing labels, relationship types and properties that
  returns `subjectId` and `objectId` (the `pke_id`s of the endpoints) and optionally `evidence` (a short text).
  Use `MATCH`, `WHERE`, `WITH` and `RETURN DISTINCT` only.
- `method: none` with a `reason` when no query over existing relationships can populate it (it needs a new extractor
  or a model); the engine then files an evolution request instead.

Rules: a **business** relationship must be backed by at least one business relationship (requirements, cards,
features). Two APIs or features are never business-related just because they call the same code, utility, logging or
configuration; such links are technical at most. Do not invent labels, relationship types or properties.
