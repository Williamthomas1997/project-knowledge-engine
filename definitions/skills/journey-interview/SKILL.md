---
name: journey-interview
description: >-
  Interviews a person about one user journey until its ordered API calls are known: asks one question at a time,
  then returns the steps with the HTTP method and path each step calls. Use to capture journeys that no test or
  collection describes (the "interview" journey source).
version: 1.0.0
runtime: both
appliesTo: [UserJourney]
tools: []
evalCases: [evals/first-question.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [journey, transcript, apis]
  properties:
    journey:
      type: object
      additionalProperties: false
      required: [key, name]
      properties:
        key: { type: string }
        name: { type: string }
        description: { type: string }
    transcript:
      type: array
      items:
        type: object
        additionalProperties: false
        required: [question, answer]
        properties:
          question: { type: string }
          answer: { type: string }
    apis:
      type: array
      items:
        type: object
        properties:
          layer: { type: string }
          service: { type: string }
          method: { type: string }
          path: { type: string }
outputSchema:
  type: object
  additionalProperties: false
  required: [status]
  properties:
    status: { enum: [ASK, COMPLETE] }
    question: { type: string, minLength: 5, maxLength: 500 }
    description: { type: string, maxLength: 600 }
    steps:
      type: array
      maxItems: 30
      items:
        type: object
        additionalProperties: false
        required: [order, name, method, path]
        properties:
          order: { type: integer, minimum: 1 }
          name: { type: string, minLength: 2, maxLength: 120 }
          method: { enum: [GET, POST, PUT, PATCH, DELETE] }
          path: { type: string, pattern: "^/" }
---
# Journey interview

You are capturing one user journey: the APIs a client calls, in order, to complete a task (for example "send an
international transfer"). You get the journey's key and name, the interview so far (your questions and the
person's answers) and the APIs the knowledge graph already knows (layer, service, method, path).

Each turn, do exactly one of these:

- **ASK**: return `status: ASK` and one short, concrete `question`. Ask about what the user does first, what comes
  next, what each step needs from an earlier one, and where the journey ends. Ask about one thing at a time. Do not
  ask about values (amounts, account numbers, names).
- **COMPLETE**: when the answers name every step from start to end, return `status: COMPLETE`, a one-sentence
  `description` of the journey and the `steps` in order (`order` from 1), each with a short `name` and the `method`
  and `path` it calls. Prefer the gateway API (`layer: gateway`) the client calls when the person describes the
  client's view; use the paths of the listed APIs where one fits.

Use only what the person said and the listed APIs. Never invent a step the person did not describe. If a step's API
is unclear, still return your best method and path: the engine checks it against the graph and asks the person
when it matches no API or more than one.
