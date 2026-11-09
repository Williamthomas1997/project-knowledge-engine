# M3: AI layer, skills and pipelines runtime, knowledge cards (spec Phases 2 and 5, ADR-007 §4, §8, §9)

Precondition: M2 accepted. Study **Mem0** (`mem0ai/mem0`: its fact extraction and memory-update prompts and the
ADD/UPDATE/DELETE/NONE flow) before you start. Record what you borrowed, and how, in a new ADR.

## 1. AI wiring
- `ai` package ports: `ChatPort`, `EmbeddingPort`, `StructuredOutput<T>` (JSON Schema-validated), a cost ledger
  (`:ModelCall {provider, model, skill, tokensIn, tokensOut, costEstimate, executionId}`), and Micrometer metrics.
- Profiles: default = no model (AI steps report `SKIPPED_NO_MODEL`); `openai` = Spring AI OpenAI;
  `replay` = `ReplayChatModel` reading `src/test/resources/ai-fixtures/<skill>/<inputHash>.json`. Tests use
  `replay`. Add a `-Precord-ai` mode that records fixtures with a real key (synthetic data only).
- Never log prompts that contain source content at INFO. Never log keys.

## 2. Skill registry and runner
- Skills in `definitions/skills/<id>/SKILL.md` (Claude-style front matter plus our keys, ADR-007 §8), validated
  against `definitions/meta/skill.schema.json`, versioned, and stored as `:SkillDefinition` (active version decided by the registry).
- `SkillRunner`: renders instructions plus input, calls `ChatPort` with structured output, validates the output against
  `outputSchema`, and records evidence (skill id/version, model, prompt hash). A skill can only use its declared tools.
- Write these skills, each with eval cases: `brd-to-knowledge-cards`, `reconcile-knowledge`,
  `link-requirement-to-implementation`, `summarise-api`.

## 3. Pipeline registry and executor
- Pipelines in `definitions/pipelines/*.yaml`, versioned. Steps reference registered `PipelineStep` beans (SPI)
  or `skill:<id>`.
- The executor persists `:PipelineExecution` / `:StepExecution` (status, attempts, timeouts, idempotency key =
  hash(pipeline version, input source versions), error class TRANSIENT|PERMANENT|POLICY), supports
  resume-from-failed-step and cancellation, and sits behind an interface so a workflow engine can replace it later.
- Pipelines: `brd-ingest` (parse → sections → cards → reconcile → apply/question → link to implementation) and
  `code-ingest` (wrapping the M2 extractors).

## 4. Knowledge cards with Mem0-style reconciliation (ADR-007 §4)
- `KnowledgeCard` type: `text`, `kind`, `keyTerms`, `validAt/invalidAt`, `SUPPORTED_BY` the source section.
  Declare `embedding.indexFields: [text]` (vectors arrive in M4; until then reconcile finds candidates with
  fulltext plus graph neighbours behind a `SimilarCardFinder` port that M4 switches to vectors).
- Decisions ADD | UPDATE | INVALIDATE | NOOP | CONFLICT, applied through the M1 graph writer only.
- CONFLICT → `(:OpenQuestion {id, question, options, status})` linked to both evidences; nothing is saved until it is answered.
  REST: `GET /api/questions?status=open`, `POST /api/questions/{id}/answer`. The answer is stored as
  `HUMAN_VERIFIED` evidence and applied.
- Relationship policies (`definitions/policies/relationships.yaml`): `IMPLEMENTED_BY` from AI is
  `ACCEPT_AS_INFERRED` with evidence required; code-extracted edges are `AUTO_ACCEPT`; conflicts → question.

## 5. Tests (replay model, real Neo4j)
Demo BRD → the expected cards (assert specific requirement ids and kinds); a re-run makes zero LLM calls and
zero events (fingerprint check); BRD v2 with one changed limit → one UPDATE and one INVALIDATE with history;
BRD v3 contradicting v2 → an OpenQuestion and no change until it is answered, then the answer is applied;
requirement → implementation links on the demo service are `INFERRED_AI` with evidence; a skill whose output fails
its schema → step FAILED (PERMANENT), no graph writes; an interrupted pipeline resumes from the failed step;
`SKIPPED_NO_MODEL` without a model; the cost ledger is written.
