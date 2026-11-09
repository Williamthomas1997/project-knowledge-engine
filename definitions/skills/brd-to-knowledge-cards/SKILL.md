---
name: brd-to-knowledge-cards
description: >-
  Rewrites one section of a business document (BRD, change request, policy) into atomic, self-contained knowledge
  cards that can be searched and linked to code. Use for each changed section of a business document.
version: 1.0.0
runtime: both
appliesTo: [document, Section, KnowledgeCard]
tools: []
evalCases: [evals/requirement-with-error-code.json, evals/error-code-table.json, evals/document-metadata.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [document, section]
  properties:
    document:
      type: object
      additionalProperties: false
      required: [key]
      properties:
        key: { type: string }
        title: { type: string }
    section:
      type: object
      additionalProperties: false
      required: [headingPath, heading, text]
      properties:
        headingPath: { type: array, items: { type: string } }
        heading: { type: string }
        text: { type: string, minLength: 1 }
outputSchema:
  type: object
  additionalProperties: false
  required: [cards]
  properties:
    cards:
      type: array
      maxItems: 30
      items:
        type: object
        additionalProperties: false
        required: [text, kind, keyTerms]
        properties:
          text: { type: string, minLength: 10, maxLength: 600 }
          kind: { enum: [requirement, rule, constraint, definition, exception] }
          keyTerms: { type: array, maxItems: 12, items: { type: string, minLength: 2 } }
          requirementId: { type: [string, "null"] }
---
# Business document section → knowledge cards

You organise business knowledge. You get **one section** of a business document (its heading path, heading and
text). Rewrite what the section states into **knowledge cards**: short statements that a developer, tester or
analyst can find with search and understand without reading the document.

## Rules

1. **Atomic.** One card states one fact: one requirement, one rule, one limit, one definition or one exception.
   Keep a condition together with its consequence ("when X, the system rejects Y with error Z" is one card).
2. **Self-contained.** Name the subject in every card ("An international transfer ...", "The daily limit for
   international transfers ..."). Never write "it", "this", "the above" or "as described".
3. **Exact.** Keep numbers, amounts, currencies, error codes, ids and names exactly as written. Never round,
   convert, translate or invent anything. If the section does not say it, the card does not say it.
4. **Kind:**
   - `requirement`: something the system shall do (usually "shall", or a numbered requirement).
   - `rule`: a business rule or validation the system applies (conditions, checks, rejections).
   - `constraint`: a limit, threshold, scope boundary or fixed value.
   - `definition`: what a term or code means (glossary entries, error code tables).
   - `exception`: a stated exception to another rule.
5. **requirementId:** when the section belongs to a numbered requirement (for example the heading
   "FR-2 Transfer limit"), set it on every card of that section (`FR-2`). Otherwise `null`.
6. **keyTerms:** 2–8 domain terms a person would search for (for example "beneficiary currency",
   "daily limit", "OTP", "003041"). Include error codes and requirement ids.
7. **Skip** document metadata: document ids, version numbers, authors, dates, change history, tables of contents,
   and sentences that only say the document is synthetic or confidential. A section with only such content gives
   `{"cards": []}`.
8. A table gives one card per meaningful row (for example one `definition` card per error code).
9. Write cards in the language of the section.

## Example

Section "FR-7 Beneficiary limit": "The system shall reject adding a beneficiary when the customer already has
20 beneficiaries. The customer sees error 004120."

```json
{"cards": [
  {"text": "Adding a beneficiary is rejected with error 004120 when the customer already has 20 beneficiaries.",
   "kind": "rule", "keyTerms": ["beneficiary", "beneficiary limit", "004120", "FR-7"], "requirementId": "FR-7"},
  {"text": "A customer can have at most 20 beneficiaries.",
   "kind": "constraint", "keyTerms": ["beneficiary", "maximum beneficiaries", "FR-7"], "requirementId": "FR-7"}
]}
```
