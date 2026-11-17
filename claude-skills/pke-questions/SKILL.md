---
name: pke-questions
description: >-
  List the Project Knowledge Engine's open questions (conflicting requirements, links to review) and answer them
  with the user in chat. Use when the user asks about open questions or conflicts, after an ingestion, or when task
  context shows a conflict.
---
# Open questions in PKE

Requires the `pke` MCP server with a CONTRIBUTOR key (answering changes the graph).

1. Call `mcp__pke__list_open_questions`. If there are none, say so and stop.
2. Take the questions one at a time. For each, show:
   - the question and why it was asked (kind: card conflict or link review);
   - the options, numbered, with their labels exactly as given;
   - the evidence: call `mcp__pke__get_evidence` with the entity ids the question is about, and
     `mcp__pke__find_brd_sections` with the key words, so the user sees the original wording and its source.
3. Ask the user which option applies and why. Never choose for them, and never answer a question the user skipped.
4. Call `mcp__pke__answer_question` with the question id, the chosen option id and the user's reason as `comment`
   (it is kept as evidence). Report what was applied (the change result) in one line.
5. Continue with the next question until the user stops or none are left.

The answer is written as `HUMAN_VERIFIED` knowledge and recorded in the audit log under the key's name.
