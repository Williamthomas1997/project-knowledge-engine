---
name: link-requirement-to-implementation
description: >-
  Decides which code elements (methods, APIs) implement a requirement or business rule, from a list of
  deterministic candidates. Use after requirement cards change or after code changes.
version: 1.0.0
runtime: engine
appliesTo: [KnowledgeCard, JavaMethod, ApiOperation]
tools: [search-implementation]
evalCases: [evals/currency-validation.json, evals/no-implementation.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [requirement, candidates]
  properties:
    requirement:
      type: object
      required: [text, kind]
      properties:
        text: { type: string }
        kind: { type: string }
        requirementId: { type: string }
        keyTerms: { type: array, items: { type: string } }
    candidates:
      type: array
      minItems: 1
      items:
        type: object
        required: [ref, type, label]
        properties:
          ref: { type: string, pattern: "^I[0-9]+$" }
          type: { enum: [JavaMethod, ApiOperation] }
          label: { type: string }
          detail: { type: string }
outputSchema:
  type: object
  additionalProperties: false
  required: [links]
  properties:
    links:
      type: array
      maxItems: 5
      items:
        type: object
        additionalProperties: false
        required: [candidate, rationale, confidence]
        properties:
          candidate: { type: string, pattern: "^I[0-9]+$" }
          rationale: { type: string, minLength: 1, maxLength: 400 }
          confidence: { type: number, minimum: 0, maximum: 1 }
---
# Link a requirement to its implementation

You get a business statement (a requirement or a rule) and **candidate code elements** found by a deterministic
search: methods (`Class#method(params)`) and HTTP APIs. Choose the candidates that **implement** the statement.

## Rules

- A method implements the statement when it **performs the check or the action** the statement describes:
  it throws the stated error code, applies the stated condition or executes the stated action.
- An API implements the statement when the statement's behaviour is reached by calling it and its handler
  performs it.
- Do **not** link helpers that are merely used on the way (converters, repositories, clients, loggers,
  configuration) unless the statement is about them.
- Link at most 5 candidates. Return `{"links": []}` when no candidate implements the statement; never guess.
- `confidence` (0–1): 0.9 when the method throws the stated error code or does exactly the stated action;
  0.6–0.8 when it clearly does it but the evidence is indirect; do not return links below 0.5.
- Give a one-sentence `rationale` that names the evidence (for example "throws 003040 when the currency is
  not supported").
- Use only the candidate ids you were given.
