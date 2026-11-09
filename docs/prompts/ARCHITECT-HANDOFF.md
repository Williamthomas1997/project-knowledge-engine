# Handoff from the architect session (Claude in Cowork, 19 Oct – 9 Nov 2026)

These are the decisions William made in the design discussions. They aren't written anywhere else. Where this file
and a milestone prompt disagree, the **milestone prompt + ADR-007 win**, except for item 1.

1. **Git identity and remote.** This repo is William's **personal** project: author `William Thomas
   <pkwilliamthomas@gmail.com>`, remote `https://github.com/Williamthomas1997/project-knowledge-engine` (private).
   Never commit with the company identity, and never push to Bitbucket.
2. **Review loop.** After each milestone, William asks the architect to review the code and the report. Push to
   GitHub only after a milestone is complete and `mvn clean verify` is green.
3. **OpenAI** is approved for real Dukhan repos and documents. Tests still use only the synthetic `demo/` corpus.
4. **Neo4j:** Community-compatible features only. Tests use Testcontainers (Docker Desktop on this PC) or Neo4j
   Desktop (Enterprise 2026.09) in external mode with the `pketest` database.
5. **Reference projects:** Mem0 (`mem0ai/mem0`) for fact extraction and ADD/UPDATE/DELETE/NOOP memory updates,
   and Graphiti (`getzep/graphiti`) for temporal validity and hybrid search. Borrow ideas, not code.
6. **Vector store:** pluggable. Neo4j is the default and Qdrant the second adapter. William has no preference
   beyond "whatever is best".
7. **BRDs are never embedded raw.** The LLM rewrites them into search-friendly knowledge cards. Conflicts must be
   **asked** (in Claude Code chat via MCP/REST), never guessed. No web UI. There is also an update pipeline for new
   BRD versions.
8. **Journeys:** the real QA project (`dkn-ocs-qa-automation`) is Java (Rest Assured + TestNG + Gatling Java DSL)
   with Postman collections. It has **no JMeter files**. Journeys also need to come from a **prompt interview**,
   where the LLM asks questions until the journey is complete. William describes this as "orchestrating the user
   journey in terms of APIs", which is how the graph comes to know the whole user journey.
9. **API identity is the full API:** method, path, parameters, gateway headers (moduleId / subModuleId / serviceId
   / screenId / unit / channel), and the request/response field structure. Values are never stored.
10. **Gateway routes:** William is fine either way. The current design (a resolver, no route nodes) stands unless
    it proves awkward.
11. **Shared logic** must be mapped (APIs sharing the same underlying logic). New relationship types may be
    created dynamically through a pipeline. Utilities, logging and config never count as shared business logic.
12. **Log trail** (code log statements in order, per API) is good to have and **low priority**. It is parsed from
    code only, with no environment config.
13. **Graph queries:** templates first, LLM-written Cypher only as a guarded read-only fallback (agreed).
14. **William's style:** concise reports and explanations, mapped to Spring terms where useful. Ask him only
    about real product decisions. Decide routine technical matters yourself and record them.
