---
name: write-cypher
description: >-
  Writes one read-only Cypher query that answers a question about the knowledge graph, when no query template fits.
  The engine checks the query (read-only, registered labels/types/properties only, EXPLAIN, LIMIT, timeout) before
  running it.
version: 1.0.0
runtime: engine
appliesTo: [retrieval]
tools: []
evalCases: [evals/kafka-topics.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [question, seeds, schema]
  properties:
    question: { type: string, minLength: 3 }
    seeds:
      type: array
      items:
        type: object
        required: [id, type, label]
        properties:
          id: { type: string }
          type: { type: string }
          label: { type: string }
    schema: { type: array, items: { type: string }, description: GraphSchemaMap records of the relevant types }
outputSchema:
  type: object
  additionalProperties: false
  required: [cypher, explanation]
  properties:
    cypher: { type: string, minLength: 10, maxLength: 2000 }
    explanation: { type: string, minLength: 1, maxLength: 400 }
---
# Write a read-only Cypher query

You get a question, **seed** entities found by search (`id`, `type`, `label`) and the relevant part of the graph's
**schema** (entity types with their properties and relationships). Write **one** Cypher query that answers the
question, starting from the seeds when they are relevant.

## Graph model

- Every entity node has the label `:Entity` plus its type label (for example `:JavaMethod`), and `pke_id`,
  `pke_type`, `pke_status` (`CURRENT` or `INVALID`).
- Relationships between entities are native edges with the registered type (for example `CALLS`, `IMPLEMENTED_BY`);
  only currently valid relationships exist as edges.
- Use the parameter `$seedIds` (a list of the seeds' `pke_id`s) instead of literal ids.

## Rules

- **Read only.** Never use `CREATE`, `MERGE`, `SET`, `DELETE`, `REMOVE`, `DROP`, `FOREACH`, `LOAD CSV` or procedures.
- Use only labels, relationship types and properties that appear in the schema.
- Return at most these columns: `id` (an entity's `pke_id`), `type`, `label` (something readable) and `detail`.
- End with `LIMIT 50`.
- Explain in one sentence what the query returns.
