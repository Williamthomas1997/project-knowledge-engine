# Recovery runbook

Neo4j is the system of record for knowledge and its history (ADR-009). This runbook covers the three layers of
recovery PKE has, from cheapest to heaviest. Neo4j Community has no online backup (ADR-008), so database backups are
offline dumps.

## 1. Undo knowledge changes inside the graph (no downtime)

Every change has versions and events, so a bad import, migration or pipeline run can be reverted by writing its
inverse:

```bash
curl -H "Authorization: Bearer $PKE_API_KEY" -X POST localhost:8089/api/snapshots -H 'Content-Type: application/json' -d '{"label":"before risky change"}'
# ... the change goes wrong; find the seq to go back to (the snapshot's seq, or from /api/history/events)
curl -H "Authorization: Bearer $PKE_API_KEY" -X POST localhost:8089/api/history/restore -H 'Content-Type: application/json' \
     -d '{"seq": 1234, "reason": "bad import of repo X"}'
```

Entities and relationships added after the seq are invalidated, changed ones get their old properties back as a new
version, and invalidated ones are asserted again. Nothing is deleted; the restore has operation id `restore:<seq>:…`.
Data migrations (`POST /api/schema/migrations`) do this automatically: they take a checkpoint snapshot first and
restore it when a batch fails (`status: RESTORED`).

Limits: definitions (schema, skills, pipelines) are not reverted; register the previous version again. Vector records
follow automatically through the outbox.

## 2. Event export (durability beyond Neo4j)

Change events are exported nightly (`pke.history.export-cron`, default `0 0 2 * * *`) and on demand
(`POST /api/history/exports`) as JSONL to the document store (`<pke.data-dir>/blobs`, content-addressed). Each
export covers a seq range with no gaps (the exporter refuses to write a range with a missing seq) and is recorded as a
`:HistoryExport` node with its SHA-256.

The export says **what happened and why**, not the values (events carry no property values, by design). Use it to
audit, to find the seq to restore to, and to check a restored dump is complete:

```bash
# last exported seq vs the restored database
curl -H "Authorization: Bearer $PKE_API_KEY" localhost:8089/api/history/exports | jq '.[-1].toSeq'
curl -H "Authorization: Bearer $PKE_API_KEY" 'localhost:8089/api/history/events?afterSeq=<that seq>&limit=1'   # must be empty or newer events only
```

Copy `<pke.data-dir>/blobs` to backup storage together with the dumps.

## 3. Database dump and load (disaster recovery)

Community: stop the DBMS, dump, start again. With the compose file in `docker/`:

```bash
docker compose -f docker/docker-compose.yml stop neo4j
docker run --rm -v docker_pke-neo4j-data:/data -v "$PWD/backups:/backups" neo4j:2026.10.1-community \
  neo4j-admin database dump neo4j --to-path=/backups
docker compose -f docker/docker-compose.yml start neo4j
```

Restore into an empty or replaced database:

```bash
docker compose -f docker/docker-compose.yml stop neo4j
docker run --rm -v docker_pke-neo4j-data:/data -v "$PWD/backups:/backups" neo4j:2026.10.1-community \
  neo4j-admin database load neo4j --from-path=/backups --overwrite-destination=true
docker compose -f docker/docker-compose.yml start neo4j
```

Neo4j Desktop: stop the DBMS, then *Dump* / *Load from file* in the database menu (or `neo4j-admin` in the DBMS
terminal). `docker_pke-neo4j-data` is the volume the compose file creates when run from the repo root; check with `docker volume ls`.

After a load:

1. Start PKE: it re-creates missing constraints and indexes and refuses to start on an incompatible Neo4j.
2. `GET /api/integrity`: every current assertion has exactly one native edge.
3. Compare the last event seq with the last export (section 2). Events after the dump are lost from the graph; their
   sources are re-ingested by the normal pipelines (`code-on-commit` from the last processed commit, `brd-update`).
4. `POST /api/sync/reconcile`: rebuilds missing vector records (Qdrant or the Neo4j vector index).

## Tested

- `HistoryIT.aFailedMigrationRestoresTheCheckpoint`: a migration fails in its second batch; the graph state equals the
  checkpoint state, with the committed batch and its reversal both in history.
- `HistoryIT.changeEventsAreExportedAsJsonlExactlyOnce`: incremental, gap-checked exports.
- The dump/load procedure is not automated: Community cannot stop a database from inside the Testcontainers instance
  the tests use. It is the standard `neo4j-admin` procedure; rehearse it on Neo4j Desktop before relying on it.
