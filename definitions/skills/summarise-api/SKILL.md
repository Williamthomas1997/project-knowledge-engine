---
name: summarise-api
description: >-
  Writes a short, search-friendly summary of one HTTP API from its structure (method, path, gateway headers,
  request/response field names, handler and the error codes it can raise). Use to fill ApiOperation.summary.
version: 1.0.0
runtime: both
appliesTo: [ApiOperation]
tools: []
evalCases: [evals/validation-api.json, evals/accounts-api.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [api]
  properties:
    api:
      type: object
      required: [httpMethod, pathTemplate]
      properties:
        httpMethod: { type: string }
        pathTemplate: { type: string }
        layer: { type: string }
        service: { type: string }
        requestFields: { type: array, items: { type: string } }
        responseFields: { type: array, items: { type: string } }
        handler: { type: string }
        calls: { type: array, items: { type: string } }
        errorCodes: { type: array, items: { type: string } }
        journeySteps: { type: array, items: { type: string } }
outputSchema:
  type: object
  additionalProperties: false
  required: [summary, keyTerms]
  properties:
    summary: { type: string, minLength: 20, maxLength: 400 }
    keyTerms: { type: array, maxItems: 10, items: { type: string } }
---
# Summarise an API

Write a **summary** (one to three sentences, at most 400 characters) of what the API does, for search: what a
client uses it for, the main input and output, and the business errors it can return. Use only the facts given
(path, fields, handler, calls, error codes, journey steps). Do not invent fields, rules or behaviour; when the
purpose is unclear, describe only what the structure shows. Never include values, tokens or sample data.

Also return 3–10 `keyTerms` a person would search for (business terms, the path's resource names, error codes).
