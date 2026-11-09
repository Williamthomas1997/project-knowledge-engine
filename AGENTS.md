# Project Knowledge Engine: agent guide

Read before doing anything:

1. `docs/MASTER_SPEC.md`: the product requirements. Treat them as authoritative.
2. `docs/IMPLEMENTATION_PLAN.md`: the agreed architecture, ADRs and milestones. ADRs live in `docs/adr/`.
3. `docs/prompts/ARCHITECT-HANDOFF.md` and `docs/prompts/README.md`: decisions from the architect sessions and the
   milestone order. The milestone prompts are `docs/prompts/M<n>-PROMPT.md`.
4. `docs/adr/ADR-007-dual-store-knowledge-design.md` (binding) and the other ADRs in `docs/adr/`.
5. The latest milestone report in `docs/reports/`. It says what is actually done and what comes next.

Working loop (since 2026-11-05): Claude implements each milestone directly, runs `mvn clean verify` against a
real Neo4j, writes `docs/reports/<M>-REPORT.md`, commits as William Thomas <pkwilliamthomas@gmail.com>
(`M<n>: <summary>`) and pushes to GitHub `origin` main. Each milestone is reviewed before the next one starts.
The earlier Cursor prompt for M0 is kept in `docs/cursor/` for reference only.

The rules in `.cursor/rules/00-pke-working-agreement.mdc` apply to any agent working here.
