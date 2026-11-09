# Project Knowledge Engine: agent guide

Read before doing anything:

1. `docs/MASTER_SPEC.md`: the product requirements. Treat them as authoritative.
2. `docs/IMPLEMENTATION_PLAN.md`: the agreed architecture, ADRs and milestones. ADRs live in `docs/adr/`.
3. The latest milestone report in `docs/reports/`. It says what is actually done and what comes next.

Working loop (since 2026-11-05): Claude implements each milestone directly, runs `mvn clean verify` against a
real Neo4j, commits locally (`M<n>: <summary>`, never pushes) and writes `docs/reports/<M>-REPORT.md`.
The earlier Cursor prompt for M0 is kept in `docs/cursor/` for reference only.

The rules in `.cursor/rules/00-pke-working-agreement.mdc` apply to any agent working here.
