---
name: reconcile-knowledge
description: >-
  Compares new candidate knowledge cards with existing cards and decides, per candidate, ADD, UPDATE, NOOP or
  CONFLICT, and which existing cards of the same section to INVALIDATE. Use when a new or changed document section
  produced candidate cards and similar cards already exist.
version: 1.0.0
runtime: engine
appliesTo: [KnowledgeCard]
tools: [find-similar-cards]
evalCases: [evals/changed-limit.json, evals/contradiction-elsewhere.json, evals/unchanged-requirement.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [document, section, candidates, existing]
  properties:
    document:
      type: object
      required: [key]
      properties: { key: { type: string } }
    section:
      type: object
      required: [headingPath, heading]
      properties:
        headingPath: { type: array, items: { type: string } }
        heading: { type: string }
    candidates:
      type: array
      items:
        type: object
        required: [ref, text, kind]
        properties:
          ref: { type: string, pattern: "^C[0-9]+$" }
          text: { type: string }
          kind: { type: string }
          keyTerms: { type: array, items: { type: string } }
          requirementId: { type: string }
    existing:
      type: array
      minItems: 1
      items:
        type: object
        required: [ref, text, kind, documentKey, sameSection, humanVerified]
        properties:
          ref: { type: string, pattern: "^E[0-9]+$" }
          text: { type: string }
          kind: { type: string }
          requirementId: { type: string }
          documentKey: { type: string }
          sameSection: { type: boolean }
          humanVerified: { type: boolean }
outputSchema:
  type: object
  additionalProperties: false
  required: [decisions]
  properties:
    decisions:
      type: array
      items:
        type: object
        additionalProperties: false
        required: [decision, reason]
        properties:
          decision: { enum: [ADD, UPDATE, NOOP, INVALIDATE, CONFLICT] }
          candidate: { type: [string, "null"], pattern: "^C[0-9]+$" }
          target: { type: [string, "null"], pattern: "^E[0-9]+$" }
          reason: { type: string, minLength: 1, maxLength: 500 }
          question: { type: [string, "null"], maxLength: 600 }
---
# Reconcile new knowledge with existing knowledge

A new version of a document section produced **candidate** cards (`C1`, `C2`, ...). You also get **existing**
cards (`E1`, `E2`, ...): the cards this section produced before (`sameSection: true`) and similar cards from
elsewhere (`sameSection: false`). Decide how the knowledge base changes. You never write the cards yourself;
the engine applies your decisions and keeps the full history.

## Decisions

Give **every candidate exactly one** of these decisions:

- `ADD`: the candidate states something no existing card states. No `target`.
- `NOOP`: an existing card already states the same thing, possibly in other words. Set `target` to it.
- `UPDATE`: the candidate is the **new version of the same statement** as an existing card of the **same section**
  (same requirement, same rule or same limit) and its content changed or became more precise. Set `target`.
  A changed number in the same requirement is an UPDATE, not a CONFLICT: the document's author changed it.
- `CONFLICT`: the candidate **contradicts** an existing card that comes from **another section or another
  document**, or that is `humanVerified`. Both are stated by sources, so it is unclear which is right. Set
  `target` and write one `question` a business analyst can answer, quoting both statements and naming both
  places. Never guess which one is right.

Additionally, for an existing card with `sameSection: true` that **no candidate restates** and that is **no
longer true** in the new version (for example it depends on a value that changed), add an `INVALIDATE` decision
with that `target` and no `candidate`. Do not invalidate cards from other sections, and do not invalidate a card
only because the candidates do not mention it.

## Rules

- Use only the ids you were given. Never create new ids.
- Statements about different cases (for example different customer segments) do not contradict each other.
- Give a short `reason` for every decision.

## Example

Existing: `E1` (sameSection) "The daily limit for international transfers is QAR 40,000."
`E2` (sameSection) "International transfers have no limit on weekends."
Candidates: `C1` "The daily limit for international transfers is QAR 25,000."

```json
{"decisions": [
  {"decision": "UPDATE", "candidate": "C1", "target": "E1", "reason": "Same limit, value changed from 40,000 to 25,000."},
  {"decision": "INVALIDATE", "target": "E2", "reason": "The section no longer states a weekend exception."}
]}
```
