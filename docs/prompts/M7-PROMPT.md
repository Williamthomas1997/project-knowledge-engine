# M7: Autonomous evolution and the full demonstration (spec Phase 7, §15, §26, §29)

Precondition: M6 accepted.

## 1. Evolution service (plan §10)
- `EvolutionRequest` → classification **L1 metadata | L2 workflow | L3 code** by skill `plan-evolution`, using the
  engine's own knowledge of itself (ingest this repo as project `pke-self` via the M2/M3 pipelines).
- A structured plan: missing capability, affected schemas/pipelines/files/graph data, migrations, tests,
  deployment, recovery. Stored as `:EvolutionPlan`.
- L1/L2: registry change → validate → dry run on a **sandbox Neo4j** (Testcontainers or a second configured
  instance) with the demo corpus → integrity checks plus eval → activate per policy → snapshot.
- L3: a `git worktree` on branch `evolution/<id>`; the `CodeGenerationAgent` port (built-in Spring AI tool loop;
  optional external headless Claude Code adapter) may write **only** under `pke-plugins/**` and `definitions/**`;
  run `mvn -q verify` in an isolated process (no network, CPU/memory/time limits, its own temp dir); ArchUnit +
  eval; load the plugin in a test profile; validate; activate per policy (dev/test auto, prod approval).
- Recovery: keep diagnostics, remove the worktree/branch or revert the registry, restore the knowledge from the
  pre-change snapshot, and record `EVOLUTION_FAILED` / `RECOVERY_COMPLETED` events. A failure fingerprint
  (request + plan hash) blocks re-application unless the failure was TRANSIENT or the inputs changed.
- REST and MCP: submit, get the plan, get the generated changes (diff), test results, deployment status, recovery result.

## 2. Full demonstration (spec §26, items 1–18)
An executable `DemoScenarioIT` (and `docs/demo.md` with curl/MCP steps) that runs all 18 items on the synthetic
corpus with real persistence: ingest BRD → cards; analyse code; link requirement → implementation; index; answer the
transfer-flow question with citations; apply a code change commit; edges updated with history; snapshot + diff;
REST + MCP access; **item 16**: register a new metadata-only relationship type (for example
`REGULATORY_DEPENDENCY`) and populate it with an existing skill/template, with no Java change; **items 17–18**: an
evolution request that needs code (for example "ingest OpenAPI YAML specs as ApiOperation sources") generated as a
plugin, built and tested in isolation, activated, and a deliberately broken variant that fails, is recovered and is
not re-applied.

## 3. Final hardening
Go through spec §29 acceptance criteria one by one in `docs/acceptance.md` (criterion → test/evidence →
status). Complete the §27 documentation set and `docs/known-limitations.md`. The full `mvn clean verify` stays green.
