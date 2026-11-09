# M3 report: AI layer, skills and pipelines runtime, knowledge cards

Date: 2026-11-09 · Implemented by: Claude (directly) · Prompt: `docs/prompts/M3-PROMPT.md` · ADR-013

## 1. Summary

- **AI layer:** provider-neutral `ChatPort`, `EmbeddingPort` and `StructuredOutput<T>` (answers validated
  against JSON Schema). Modes are none (default), `openai`, `replay` (tests) and `record` (`-Precord-ai`). A cost
  ledger (`:ModelCall`) and Micrometer metrics record every call. Prompts are never logged.
- **Skills:** Claude-compatible `SKILL.md` files with schemas, declared tools and eval cases: `brd-to-knowledge-cards`,
  `reconcile-knowledge`, `link-requirement-to-implementation` and `summarise-api`. They are versioned and stored
  as `:SkillDefinition`. A step can only use the tools its skill declares.
- **Pipelines:** YAML definitions (`brd-ingest`, `code-ingest`) run by a persisted executor. Each step has a
  timeout and retries for TRANSIENT failures. Failures are classified (TRANSIENT, PERMANENT, POLICY), and the
  executor supports cancellation, resume from the failed step and idempotency keys. The executor sits behind an
  interface.
- **Knowledge cards, reconciled Mem0-style:** BRD sections become typed cards. Each candidate gets ADD, UPDATE,
  NOOP, INVALIDATE (with history) or CONFLICT. A CONFLICT becomes an `OpenQuestion` that is answered over REST and
  applied as `HUMAN_VERIFIED`. Unchanged sections never reach a model.
- **Requirement → implementation:** `IMPLEMENTED_BY` links are `INFERRED_AI`, with evidence (model, prompt hash,
  rationale, confidence). They pass relationship policies (`definitions/policies/relationships.yaml`) and are
  re-linked after code changes.

## 2. Versions chosen

No dependency changes. Spring AI 2.0.2's OpenAI module is now used: `OpenAiChatModel` with the JSON-object
response format. New meta-schemas: `skill`, `pipeline` and `relationship-policies`.

## 3. Environment

| Item | Value |
|---|---|
| OS / Java / Maven | Windows 11 · Oracle JDK 21.0.11 · Maven 3.9.11 |
| Docker / Neo4j for tests | 29.4.1 · 2026.10.1 community (Testcontainers) |
| Model | **none available**: no `OPENAI_API_KEY` in this environment (see §7 deviation 1) |

## 4. Files created or changed (main ones)

```
pke-spi/.../spi/pipeline/      PipelineStep, StepContext (incl. memo), StepFailure (SPI)
definitions/meta/              skill.schema.json, pipeline.schema.json, relationship-policies.schema.json
definitions/schema/            KnowledgeCard (entity, embedding.indexFields [text]), IMPLEMENTED_BY (relationship)   (44 definitions)
definitions/skills/            4 skills, each SKILL.md + evals/*.json (10 eval cases)
definitions/pipelines/         brd-ingest.yaml, code-ingest.yaml
definitions/policies/          relationships.yaml
engine/ai                      AiProperties, AiConfiguration, ChatPort/ChatRequest/ChatResult, SpringAiChatPort,
                               ReplayChatPort, RecordingChatPort, NoModelChatPort, EmbeddingPort, JsonSchemas,
                               StructuredOutput, ModelCallLedger, AiCallException, ModelAvailability
engine/pipeline                PipelineDefinition, PipelineRegistry, PipelineExecutor, LocalPipelineExecutor,
                               PipelineBootstrap, SourceVersionResolver, PipelineFaultInjector (test hook), Hashes
engine/pipeline/skill          SkillDefinition, SkillDefinitionParser, SkillRegistry, SkillRunner, SkillResult, SkillTool
engine/pipeline/steps          brd-parse, brd-sections (fingerprints, long-section parts), cards-reconcile, cards-apply,
                               requirements-link, repository-ingest; tools find-similar-cards, search-implementation
engine/reconcile               SimilarCardFinder + FulltextSimilarCardFinder, ReconcileDecisions, KnowledgeCards,
                               QuestionService, RelationshipPolicies
engine/graph                   KnowledgeWriter (entity invalidation/revalidation, promptHash, evidence CITES Section),
                               ModelCallStore, RuntimeDefinitionStore, PipelineExecutionStore, QuestionStore,
                               FingerprintStore, CardQueryService; GraphSchemaInitializer (+12 constraints/indexes)
engine/api/rest                PipelineController, QuestionController, SkillController; StepFailure → 422/503
demo/brd                       v1 states a QAR 50,000 limit "same as local transfers"; v2 changes it to 30,000;
                               new v3 contradicts it in a "Limits summary"
tests                          PipelineExecutorIT, BrdIngestPipelineIT, NoModelPipelineIT, SkillEvalIT, EntityInvalidationIT;
                               ReconcileDecisionsTest, StructuredOutputTest, SkillDefinitionParserTest,
                               RelationshipPoliciesTest, BrdSectionsStepTest; 31 replay fixtures; KnowledgeIT (replay profile)
docs                           ADR-013, REST.md, README, IMPLEMENTATION_PLAN (M3 done, §9), definitions/README, demo/README
```

## 5. Build and test results

Command: `mvn -B clean verify` (repo root, Docker running)

```
[INFO] PKE SPI ............................................ SUCCESS [  3.287 s]
[INFO] PKE Engine ......................................... SUCCESS [03:32 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  03:36 min
```

| Module | Plugin | Run | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| pke-spi | surefire | 2 | 0 | 0 | 0 |
| pke-engine | surefire | 122 | 0 | 0 | 0 |
| pke-engine | failsafe (real Neo4j, replay model) | 66 | 0 | 0 | 0 |

New in M3: PipelineExecutorIT 7, BrdIngestPipelineIT 7, NoModelPipelineIT 1, SkillEvalIT 1 (10 eval cases),
EntityInvalidationIT 2, ArchitectureTest +1 (only `ai` uses Spring AI), and 15 unit tests. **No skipped tests.**

M3 prompt §5 requirements:

| Requirement | Test |
|---|---|
| Demo BRD → expected cards (requirement ids, kinds) | `BrdIngestPipelineIT.theDemoBrdBecomesTheExpectedCards...`: FR-1 `rule`, FR-2 `constraint` QAR 50,000 + "same as local" `rule`, FR-3 `requirement`, error codes `definition`, no card for document metadata; every card's evidence cites its section with model, prompt hash, skill and pipeline version |
| Re-run: zero LLM calls, zero events | `reRunningTheSameVersionMakesNoModelCallsAndNoEvents`: same execution returned (idempotency key); forced re-run selects 0 sections (fingerprints), ledger and footprint unchanged |
| BRD v2 changed limit → one UPDATE and one INVALIDATE with history | `aChangedLimitUpdatesOneCardAndInvalidatesAnotherWithHistory`: limit card v1 50,000 → v2 30,000 (version history); "same as local" card INVALID with `invalidAt`; versions CURRENT→INVALID; FR-4 added |
| v3 contradicting v2 → OpenQuestion, no change until answered, then applied | `aContradictionBecomesAQuestion...`: knowledge footprint unchanged; `GET /api/questions?status=open` → 1 question citing the section and the existing evidence; `POST .../answer {use-new}` → card v3 = 50,000, `HUMAN_VERIFIED`, actor `api:william` on the event; answering again → 409 |
| Requirement → implementation links INFERRED_AI with evidence | `requirementsAreLinkedToTheirImplementation...`: FR-1 → `validateCurrency(String)`, FR-2 → `validate(TransferRequest)`, no helper (converter) linked; evidence has model, prompt hash, rationale, confidence; `code-ingest` without new commits re-links nothing (no model call) |
| Skill output failing its schema → step FAILED (PERMANENT), no writes | `anAnswerOutsideTheOutputSchemaFailsPermanentAndWritesNothing`: 1 attempt, footprint unchanged, ledger outcome `INVALID_OUTPUT` |
| Interrupted pipeline resumes from the failed step | `anInterruptedPipelineResumesFromTheFailedStep...`: crash before `apply`; resume → COMPLETED, no new model calls, `cards` 1 attempt, `apply` 2 |
| `SKIPPED_NO_MODEL` without a model | `NoModelPipelineIT`: `PARTIAL`, AI steps `SKIPPED_NO_MODEL`, nothing written, no ledger entries |
| Cost ledger written | first BRD test: every `:ModelCall` has provider, model, skill, skill version, tokens, prompt and input hash, step; `GET /api/ai/calls` |
| Executor: timeouts, retries, cancellation, idempotency | `PipelineExecutorIT` (timeout → TRANSIENT retried then FAILED; transient retried; PERMANENT not retried; resume; cancel; same input → same execution unless forced; invalid input rejected) |

Bugs found by the tests and fixed:
1. A YAML description containing a comma broke `KnowledgeCard` (same trap as in M2).
2. Card ids come from the first text, so a later card that states an old value again (v3 "50,000") would have had
   the id of the card that had been updated to 30,000, and an ADD would have overwritten it. Now a salted id is used.
3. `SystemInfoIT` assumed no chat model; under the replay profile there is one. System info now reports which
   (`chatModel: none | replay | openai | record(openai)`).

## 6. Real-repository smoke (manual, report only, no model)

Packaged jar, throwaway Neo4j, real route export. **Nothing from these sources was committed.**

| Check | Result |
|---|---|
| Startup | 44 definitions, 4 skills, 2 pipelines registered; `chatModel: none` |
| `code-ingest` FULL on `dkn-international-transfer-service` | COMPLETED in 145 s; it wraps the M2 run (653 files, 3,395 entities created, 7 Postman journeys); `link`: 0 cards |
| Same request again (HEAD unchanged) | the same execution is returned (idempotency key = pipeline version + HEAD) |
| `brd-ingest` on `Dukhan_Bank_Account_API_Documentation.docx` | PARSED, then `PARTIAL` (`SKIPPED_NO_MODEL`); nothing written |

**Found and fixed:** that Word document has no heading styles, so it parses into **one 59 KB section**, which would
have been one huge prompt. Long sections are now sent in paragraph-bounded parts of at most 8,000 characters
(7 parts here), each fingerprinted separately. Parts never invalidate cards because of absence (ADR-013 §4).

Startup note: `/actuator/health` is UP before the startup runners (schema, skills, pipelines) finish. Clients and
probes should wait for `/actuator/health/readiness` or for `GET /api/pipelines` to list pipelines.

## 7. Deviations from the prompt

1. **Fixtures are hand-authored, not recorded.** No `OPENAI_API_KEY` was available, so the 31 replay fixtures (demo
   corpus and eval cases) are answers I wrote following each skill's instructions (`model: hand-authored`, tokens
   estimated). The `-Precord-ai` mode is implemented, and `ai-fixtures/README.md` explains how to re-record. The
   tests prove the engine's behaviour, not a model's quality: **no prompt has been tried against a real model yet.**
2. **`ReplayChatModel` is a `ChatPort`** (`ReplayChatPort`), not a Spring AI `ChatModel`. Fixtures are keyed by
   skill + input hash, which the provider-level `ChatModel` interface does not see. Production calls still go
   through Spring AI.
3. **Tools are called by steps before the model call** (declared tools only, POLICY otherwise). The model cannot call
   tools itself in M3. This keeps runs deterministic and replayable.
4. **`summarise-api` is not used by a pipeline yet** (skill + eval cases + `POST /api/skills/summarise-api/run`). Filling
   `ApiOperation.summary` belongs with the M4 collections that embed it.
5. **Cards link to code with `IMPLEMENTED_BY` from `KnowledgeCard`.** `BusinessRequirement` entities are not
   derived from cards yet; the type is allowed as a source for later use.
6. **Skill and pipeline registrations produce no change events** (operational, like ingestion runs). Evidence
   records the skill and pipeline version of every fact.

## 8. Problems and open questions

- **Try the prompts on a real model** (architect decision on cost): run `OPENAI_API_KEY=... mvn -Precord-ai verify`
  after deleting the fixtures, and review the cards for the demo BRD and one real BRD. Cost is visible in
  `GET /api/ai/calls` once `pke.ai.pricing.<model>` is configured. No prices are hard-coded.
- **Word documents without heading styles** give poor structure: the parts are cut by size, not by meaning.
  Options: detect bold/numbered paragraphs as headings in the Tika parser, or accept parts.
- **Cross-section reconciliation within one run** is not done (candidates are compared with stored cards only).
- **Earlier open items unchanged:** library classpath for symbol resolution, the error-constant mapping, real
  Gatling/polymorphic journeys, and authentication (M6).

## 9. Git

Committed locally as `M3: AI layer, skills and pipelines runtime, knowledge cards` by William Thomas
<pkwilliamthomas@gmail.com>; push status in the commit summary to William.

## Next milestone

**M4** (`docs/prompts/M4-PROMPT.md`): pluggable vector store (Neo4j + Qdrant), collections, outbox sync, hybrid
retrieval, templates and guarded Cypher fallback, context generation, evaluation.
