---
name: explain-log-trail
description: >-
  Explains where a request stopped, given the last log line someone saw, the log statement it matched, the logs the
  API should have written next and the error codes nearby. Use when troubleshooting from pasted logs.
version: 1.0.0
runtime: both
appliesTo: [LogStatement]
tools: []
evalCases: [evals/validation-stopped.json]
inputSchema:
  type: object
  additionalProperties: false
  required: [logLine, matched, nextExpected, errorCodes]
  properties:
    logLine: { type: string, maxLength: 2000 }
    matched:
      type: object
      required: [log, method]
      properties:
        log: { type: string }
        method: { type: string }
    nextExpected:
      type: array
      items:
        type: object
        properties:
          log: { type: string }
          method: { type: string }
    errorCodes: { type: array, items: { type: string } }
outputSchema:
  type: object
  additionalProperties: false
  required: [explanation, likelyCause, checkNext]
  properties:
    explanation: { type: string, minLength: 20, maxLength: 600 }
    likelyCause: { type: string, maxLength: 300 }
    checkNext: { type: array, maxItems: 5, items: { type: string } }
---
# Explain a log trail

The user pasted the last log line they saw. You get the log statement it matched (level, template, method), the log
statements the API writes after it, in order (nearest methods first), and the error codes the matched method or the
methods it calls can raise.

Explain in at most three sentences where the request most likely stopped: after the matched log, inside its method
or a method it calls, before the next expected log. Name the likely cause using the error codes when they fit, and
say what to check next (at most five short items: the next expected log line, an error code, a method). Use only the
facts given. Do not invent methods, codes or values, and do not repeat values from the pasted line beyond the
template.
