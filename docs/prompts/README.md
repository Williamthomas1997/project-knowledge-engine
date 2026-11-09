# Milestone prompts

Give these to Claude Code one at a time, in order. Start each in a fresh session from the
`project-knowledge-engine` folder with:

> Read AGENTS.md, then implement docs/prompts/M<n>-PROMPT.md. Finish only when mvn clean verify passes
> with the integration tests actually run and docs/reports/M<n>-REPORT.md is written.

| M | Scope |
|---|---|
| M1 | Foundation: schema registry, single graph writer, history in one transaction, REST |
| M2 | Deterministic ingestion: documents, Java code, Git, journeys (Postman / Java tests / Gatling / JMeter), routes, demo corpus |
| M3 | AI layer: Spring AI wiring, skills and pipelines runtime, BRD → knowledge cards, Mem0-style reconcile, conflict questions, requirement → implementation links |
| M4 | Pluggable vector store (Neo4j + Qdrant), collections, outbox sync, hybrid retrieval, templates and guarded Cypher fallback, context generation, evaluation |
| M5 | History queries, snapshots and diffs, incremental update pipelines, relationship discovery, log trail, recovery |
| M6 | MCP server, security, Claude Code skills (bootstrap, journey interview, answer questions) |
| M7 | Autonomous evolution (L1/L2/L3), isolated build/test, activation, recovery, full demo scenario |

Shared rules for every milestone (on top of `.cursor/rules/00-pke-working-agreement.mdc`):
- `docs/adr/ADR-007-dual-store-knowledge-design.md` is binding. In M2, fold it into
  `IMPLEMENTATION_PLAN.md` (milestone table and §5/§8) and keep the plan in sync afterwards.
- Real Dukhan repos under `C:\Code2025\Dukhan_Bank\BitBucket` may be **read** for smoke checks. Never modify
  them, and never commit their content, extracted data or secrets into this repo. Tests use the synthetic
  `demo/` corpus only.
- Every milestone updates `README.md` and the relevant docs (spec §27 list), adds ADRs for new decisions, writes
  `docs/reports/M<n>-REPORT.md` in the M0 format with real test counts, and commits locally as `M<n>: ...`.
- If a decision in the prompt turns out to be wrong in practice, choose the closest compliant option and record
  it as a deviation. Never deviate silently.
