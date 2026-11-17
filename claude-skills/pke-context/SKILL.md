---
name: pke-context
description: >-
  Before a coding task in a project known to the Project Knowledge Engine, fetch the task context (requirements,
  APIs, code, journeys, open questions, each with a citation) and summarise it. Use at the start of any change to
  code, APIs or business rules, or when the user asks what a change affects.
---
# Task context from PKE

Requires the `pke` MCP server (see `docs/claude-code-setup.md` in the project-knowledge-engine repository).

1. Call `mcp__pke__get_task_context` with the task in one sentence, as the user described it (add the API, class or
   journey they named). Use the default budget unless the task spans several services (then `budgetTokens: 3000`).
2. Summarise for the user, in this order and briefly:
   - **Verified and deterministic facts** that constrain the change (requirements, limits, error codes, the API and
     the handler method), each with its citation (`repo:...@commit#file:line` or the BRD section).
   - **Inferred links** (`INFERRED_AI`): say they are inferred and need checking.
   - **Conflicts and open questions**: list them; do not resolve them yourself. Offer the `pke-questions` skill.
   - **What else is affected**: for each method you expect to change, call `mcp__pke__find_affected_components` with
     its id and list the callers, APIs, journeys and tests.
3. If the context is thin, call `mcp__pke__search_knowledge` with the key terms, then `mcp__pke__get_entity` or
   `mcp__pke__get_evidence` for the hits that matter.

Rules: cite what you use; never present an inferred fact as verified; never invent ids; say when PKE has nothing on
a topic instead of guessing. Do not change the graph from this skill.
