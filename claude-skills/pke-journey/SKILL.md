---
name: pke-journey
description: >-
  Capture a user journey (the ordered API calls a client makes to complete a task) by interview, and write it to the
  Project Knowledge Engine. Use when the user wants to describe or record a journey that no test or collection
  covers, or asks to "orchestrate a journey in terms of APIs".
---
# Journey interview

Requires the `pke` MCP server with a CONTRIBUTOR key.

1. Ask the user for the journey's name if they have not given one. Derive a key: lower case, words joined by `-`
   (e.g. `international-transfer`). Check `mcp__pke__list_journeys`: if the key exists, ask whether to extend that
   journey or use a new key.
2. Call `mcp__pke__start_journey_interview` with `journeyKey`, `name` and, if the user gave one, `description`.
3. Relay the returned `question` to the user word for word. Send their answer, in their words, with
   `mcp__pke__answer_journey_interview` (`sessionId`, `answer`). Repeat while `status` is `ASKING`.
   - When the question lists numbered APIs (a step matched several APIs or none), show the list and send the number
     the user picks, or `none` to keep the step without an API.
   - Do not answer questions on the user's behalf and do not add steps they did not describe.
4. When `status` is `COMPLETED`, call `mcp__pke__get_journey` with the key and show the steps with their APIs.
   Mention that the journey is stored as `HUMAN_VERIFIED` and that steps without an API can be completed later by
   running the interview again.

If a call fails because no chat model is configured, tell the user the engine needs its `openai` profile for
interviews.
