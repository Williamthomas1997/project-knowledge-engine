# Replay fixtures

Recorded model answers for the `replay` profile (ADR-013): `<skill>/<inputHash>.json`, where `inputHash` is the
SHA-256 of the canonical skill input. Synthetic data only (`demo/` corpus and the skills' eval cases).

**Provenance (M3):** no `OPENAI_API_KEY` was available when M3 was built, so every fixture here was written by
hand following the skill's instructions (`"provider": "fixture", "model": "hand-authored"`; token counts are
estimates, characters / 4). They test the engine's behaviour (decisions, writes, history, questions), not a model's
quality.

To replace them with real answers, delete the fixtures to re-record and run, with the key set:

```bash
OPENAI_API_KEY=... ./mvnw -Precord-ai verify
```

Requests without a fixture are then sent to the model and saved here. Assertions in `BrdIngestPipelineIT` and the
skills' eval cases check specific outcomes (requirement ids, kinds, UPDATE/INVALIDATE/CONFLICT); a real model that
answers differently makes them fail, which is the point: review the answer, then the instructions.

A replay run that meets a request without a fixture fails that step (PERMANENT) and writes the request, including
the exact prompt, to `pke-engine/target/ai-fixtures-missing/<skill>/<inputHash>.request.json`.
