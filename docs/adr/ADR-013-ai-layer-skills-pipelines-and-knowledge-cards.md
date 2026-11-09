# ADR-013: AI layer, skills and pipelines runtime, knowledge cards (Mem0-style reconciliation)

Status: **accepted** (M3, 2026-11-09). Implements ADR-007 §4, §8, §9 and MASTER_SPEC phases 2 and 5.

## 1. AI ports (`ai` package)

| Port | Implementations | Chosen by `pke.ai.mode` |
|---|---|---|
| `ChatPort` | `SpringAiChatPort` (any Spring AI `ChatModel`; OpenAI gets `response_format: json_object`), `ReplayChatPort`, `RecordingChatPort`, none | `auto` (default: the Spring AI model if one is configured, else none), `none`, `replay`, `record` |
| `EmbeddingPort` | Spring AI `EmbeddingModel`, none | used from M4 |
| `StructuredOutput<T>` | JSON Schema 2020-12 validation (networknt) of the answer, then mapping | — |
| `ModelCallLedger` | `:ModelCall {provider, model, skill, skillVersion, tokensIn, tokensOut, costEstimate, executionId, stepId, inputHash, promptHash, durationMs, replayed, outcome}` + Micrometer `pke.ai.calls`, `pke.ai.tokens`, `pke.ai.call.duration` | — |

- Profiles: default = no model (AI steps report `SKIPPED_NO_MODEL`); `openai` = Spring AI OpenAI (`OPENAI_API_KEY`);
  `replay` = fixtures from `classpath:ai-fixtures/<skill>/<inputHash>.json`. Integration tests run with `replay`.
- `mvn -Precord-ai verify` (with `OPENAI_API_KEY`) sets `pke.ai.mode=record`: existing fixtures are replayed, the
  rest are sent to the real model and saved as fixtures. Synthetic data only.
- The schema is in the system message and validated by the engine for every provider. Provider-side strict schemas
  were not used: OpenAI strict mode requires every property to be required and forbids optional keys.
- `costEstimate` = tokens × `pke.ai.pricing.<model>.{input,output}-per-million`; null when no price is configured
  (no prices are hard-coded: they change).
- Prompts are never logged above DEBUG, and then only their hash. Keys are never logged.

## 2. Skills (`definitions/skills/<name>/SKILL.md`)

- Claude-compatible front matter (`name`, `description`) plus our keys: `version`, `inputSchema`, `outputSchema`,
  `tools`, `appliesTo`, `evalCases`, `runtime`; validated against `definitions/meta/skill.schema.json`; the body is
  the instructions. The directory name must equal `name`.
- `SkillRegistry` stores every version as `:SkillDefinition {name, version, hash, active}`; a name + version is
  immutable; the active version is the highest found. Registration is operational (no change events): evidence
  records the skill version of every fact.
- `SkillRunner`: validate input → render (instructions + "answer with one JSON object matching this schema" + the
  canonical input) → `ChatPort` → validate against `outputSchema` → ledger. No model → `skipped`. Invalid input or
  answer → `PERMANENT`. Rate limit, timeout, server error → `TRANSIENT`.
- **Tools**: `SkillTool` beans (`find-similar-cards`, `search-implementation`). Steps call them through
  `SkillRunner.tool(skill, tool, args)`, which refuses undeclared tools (`POLICY`). In M3 tools gather context
  *before* the model call; model-driven tool calling is not enabled (deterministic, replayable, cheaper).
- Skills: `brd-to-knowledge-cards`, `reconcile-knowledge`, `link-requirement-to-implementation`, `summarise-api`,
  each with eval cases (`evals/*.json`, checked by `SkillEvalIT`).

## 3. Pipelines (`definitions/pipelines/*.yaml`)

- Validated against `definitions/meta/pipeline.schema.json`; steps reference `PipelineStep` beans (SPI in
  `pke-spi`: `PipelineStep`, `StepContext`, `StepFailure`) or `skill:<name>` with `forEach: <step>.<field>`
  (a list of `{key, input}`).
- `PipelineExecutor` (interface) / `LocalPipelineExecutor`: `(:PipelineExecution)-[:HAS_STEP]->(:StepExecution)`
  with status, attempts, error class (`TRANSIENT | PERMANENT | POLICY | CANCELLED`), output; per-step timeout
  (each attempt runs on its own thread), retry with backoff for TRANSIENT only, cancellation, resume from the first
  incomplete step (outputs of completed steps are reused), restart recovery (RUNNING → FAILED/TRANSIENT).
- `StepContext.memo(key, ...)` persists work inside a step (`:StepMemo`, one per model call), so a retry or resume
  never repeats a model call that already succeeded.
- **Idempotency key** = hash(pipeline id@version, current versions of the input's sources, input). A COMPLETED or
  running execution with the same key is returned; `force=true` starts a new one.
- A step that returns `_status: SKIPPED_NO_MODEL` skips the rest; the execution ends `PARTIAL` and can be resumed
  once a model is configured.
- Pipelines: `brd-ingest` (parse → sections → cards → reconcile → apply/question → link) and `code-ingest`
  (M2 repository ingestion → link every requirement card again).

## 4. Knowledge cards and Mem0-style reconciliation

What we took from **Mem0** (`mem0ai/mem0`, `mem0/configs/prompts.py`, `memory/main.py`), as ideas, not code:

| Mem0 | PKE | Difference and why |
|---|---|---|
| Fact extraction prompt → `{"facts": [...]}`, empty list for nothing relevant | `brd-to-knowledge-cards` → `{"cards": [...]}` with `kind`, `keyTerms`, `requirementId`; metadata sections give `[]` | Cards must be self-contained and typed, because they are linked to code and embedded (ADR-007 §4) |
| Update prompt: ADD / UPDATE / DELETE / NONE against retrieved memories | `reconcile-knowledge`: ADD / UPDATE / NOOP / INVALIDATE / **CONFLICT** | DELETE becomes INVALIDATE (history kept, Graphiti); CONFLICT is new: contradictions between sources are asked, never resolved by the model |
| Existing memories shown with integer ids instead of UUIDs; "use only input ids" | Candidates `C1..`, existing cards `E1..`; `ReconcileDecisions` rejects unknown ids, missing or duplicate decisions | Enforced in code (PERMANENT failure), not only asked in the prompt |
| Similar memories found by vector search | `SimilarCardFinder` port: fulltext over card text + cards with the same requirement id (M3); vectors in M4 | Plus the cards this section produced before (`sameSection`) |
| Hash of memory text to skip duplicates | Section fingerprint = hash(document, heading path, text, skill versions), checked before any model call | Re-running unchanged input makes zero calls and zero events |

Rules added on top:

- **Policy guard** (code, after the model): an UPDATE of a `HUMAN_VERIFIED` card or of a card from another document
  becomes a CONFLICT; an INVALIDATE of such a card is dropped and reported.
- **Long sections** (over 8,000 characters, e.g. a Word document without heading styles, which parses into one
  section) are sent to the card skill in paragraph-bounded parts, each with its own fingerprint and citing the
  same section. A part does not see the section's earlier cards as its own (`sameSection: false`), and INVALIDATE
  decisions for parts are dropped: absence in one part says nothing about the others.
- **No existing cards** → candidates are ADDed without a model call. **Section removed** from the new version →
  its cards are invalidated deterministically.
- Applied per section in one change set through `KnowledgeWriter`: ADD (new `KnowledgeCard`, `INFERRED_AI`),
  UPDATE (new version of the target), NOOP (target gains evidence from the new version), INVALIDATE
  (`EntityInvalidation`: `pke_status = INVALID`, `invalidAt` = the new version's time, history kept).
- Card id = UUIDv5(document, normalised first text); a card keeps its id when updated. A new card whose text-derived
  id belongs to a card with different current text gets a salted id, so it can never overwrite that card.
- Evidence: `(card)-[:SUPPORTED_BY]->(:Evidence {extractionMethod: skill:<name>, skillVersion, modelProvider,
  modelId, promptHash, pipelineVersion, contentHash: section hash})-[:CITES]->(:Section)` and `-[:FROM]->(:SourceVersion)`.

**Conflicts.** `(:OpenQuestion {kind: CARD_CONFLICT, question, options, status, payload})-[:CONCERNS]->(card)`,
`-[:CITES]->` the candidate's section and the existing card's evidence. Nothing is written to knowledge.
`POST /api/questions/{id}/answer {option, comment}` applies `keep-existing`, `use-new` or `keep-both` through the writer
with `HUMAN_VERIFIED` evidence (`extractionMethod: human-answer`) and the answering actor on the change events.
The same conflict always gets the same question id, so re-runs do not ask twice.

## 5. Requirement → implementation links and relationship policies

- `search-implementation` (deterministic): methods that throw an error code quoted in the card (strongest), then
  methods / classes / API paths matching key terms. Utilities, configuration, constants, DTOs, mappers, logging,
  exceptions and tests are excluded (ADR-007 §6).
- `link-requirement-to-implementation` chooses among the candidates; `IMPLEMENTED_BY` (new relationship type,
  `KnowledgeCard|BusinessRequirement → ApiOperation|JavaMethod|JavaClass`) is written with scoped evidence
  (`scope: card:<id>`) carrying rationale, model, prompt hash and reported confidence; links a later run does not
  propose again are invalidated (scope retraction). A fingerprint (card content, candidate ids, skill and policy
  version) skips unchanged cards.
- `definitions/policies/relationships.yaml` (meta-schema `relationship-policies.schema.json`): first matching rule
  (relationship or `*`, origin `deterministic | ai | manual`) decides `AUTO_ACCEPT | ACCEPT_AS_INFERRED | QUESTION |
  REJECT`, with `evidenceRequired` and `minConfidence`; otherwise the relationship type's schema policy decides
  (`discovery`, `approval`). IMPLEMENTED_BY from AI: ACCEPT_AS_INFERRED, evidence required, confidence ≥ 0.5.
  QUESTION creates a `LINK_REVIEW` question (`accept | reject`).

## 6. Writer changes

- `ChangeSet.entityInvalidations` (`EntityInvalidation`): `ENTITY_INVALIDATED`; writing an invalid entity again
  makes it current (`ENTITY_REVALIDATED`). Entities now carry `pke_status` (missing = CURRENT) and versions carry `status`.
- `EvidenceInput.promptHash`; evidence whose `location` is a `:Section` id is linked to it (`CITES`).

## Consequences

- Tests need no key: fixtures for the demo corpus and eval cases are committed. In M3 they were **hand-authored**
  (no key was available); `mvn -Precord-ai verify` replaces them with real recordings once a key is set.
- Cross-section reconciliation within one run is not done: candidates of one section are not compared with cards
  ADDed by another section of the same run (as in Mem0, which reconciles per message).
- A timed-out step attempt is interrupted, but a step blocked in I/O can finish later; writes are idempotent, so a
  retry is safe.
- Sections are deleted and recreated when a document is reparsed with a new parser version; their `CITES` links are
  then lost (evidence keeps the section id in `location`).
