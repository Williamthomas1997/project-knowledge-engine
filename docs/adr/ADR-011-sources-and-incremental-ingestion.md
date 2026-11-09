# ADR-011: Sources, documents and incremental code ingestion

Status: **accepted** (M2, 2026-11-09)

## Sources and versions

```
(:SourceArtifact {key, kind: document|repository, status: ACTIVE|REMOVED})-[:HAS_VERSION]->(:SourceVersion {version})
(:SourceArtifact)-[:CURRENT_VERSION]->(:SourceVersion)          (none while REMOVED)
(:SourceVersion {kind: 'document'})-[:HAS_SECTION]->(:Section {order, headingPath, heading, page, text, hash})
(:Evidence {sourceArtifactId: key, sourceVersion})-[:FROM]->(:SourceVersion)
```

- Keys: documents `doc:<slug>` (default: the file name without extension, so a new upload of the same file is
  a new version); repositories `repo:<name>`. Ids: `SourceArtifact` = UUIDv5(key), `SourceVersion` =
  UUIDv5(key, version).
- **Document version = SHA-256 of the content** (non-Git sources have their own version identity, spec §9.3).
  The original bytes go to the content-addressed, immutable `DocumentStore` (`FileSystemDocumentStore` under
  `pke.data-dir/blobs/sha256/..`; an object-store adapter can implement the port later).
- **Repository version = Git commit.** Commits processed by a run are recorded as `SourceVersion {kind:
  commit, parents, changedFiles, renames}`; evidence links to its commit through `FROM`.
- Same bytes + same parser version → nothing written. A new parser version reparses the version
  (`SECTIONS_REPARSED`). Re-uploading an older version makes it current again (`REINSTATED`). Removing a source
  (`DELETE /api/sources/{key}`) clears `CURRENT_VERSION` and marks it `REMOVED`; versions, sections and
  evidence stay so history and citations remain resolvable.
- Events: `SOURCE_REGISTERED`, `DOCUMENT_VERSION_ADDED`, `SECTIONS_REPARSED`, `COMMITS_RECORDED`,
  `SOURCE_REMOVED` (`ABOUT` the artifact), written by `graph.SourceWriter` through the single write path.
- Sections are searchable with a Community fulltext index (`pke_section_text`); they are **not embedded**
  (ADR-007 §4). Scanned PDFs without text get status `OCR_REQUIRED` via the `OcrProvider` extension point.

## Scoped evidence and retraction (how code changes become history)

- Extractors re-read a whole **scope** (a file) every time. Their evidence carries `scope`, and is identified
  by (subject, artifact, scope, extraction method), so seeing an unchanged fact again at a new commit adds
  nothing: the first observation (with its commit) is kept. Unscoped evidence keeps the M1 identity.
- A change set may carry `ScopeRetraction(artifact, scope, method)`: CURRENT relationships supported by that
  scope, not written again in the change set, and **not supported by any other scope or by unscoped evidence**,
  are invalidated (`RELATIONSHIP_INVALIDATED`, native edge removed, history kept).
- Ingestion writes in two passes (`KnowledgeBatchWriter`): all entities of the run merged by natural key
  (first properties win, evidence combined), in batches; then per scope its relationships plus its retraction.
  Deleted or renamed-away files get retraction-only change sets.
- Shared entities written from several scopes or sources (`ErrorCode`, `KafkaTopic`, `DatabaseTable`,
  `ConfigProperty`, `Service`) carry **only their key properties**; everything else goes to evidence, so
  writers never overwrite each other.
- Change sets from extractors set `skipMissingEndpoints`: a relationship whose endpoint entity does not exist
  is reported as `SKIPPED` instead of failing the whole file.

## Incremental runs

- `IngestionRun {kind, target, mode, status, fromVersion, toVersion, statsJson, unresolvedJson}` (operational,
  no events). Status is `COMPLETED` only when every file parsed and every write succeeded, else `PARTIAL`
  (failures listed) or `FAILED`; retry creates a new attempt. Change events of a run carry the run id as
  `operationId`.
- The run exports the target commit's tree to a temporary directory (JGit), so the working tree never affects
  results. `full`: every Java/SQL file. `incremental`: since the last COMPLETED/PARTIAL run's commit, the
  touched files plus the **reverse-dependency set** (scopes of current relationships pointing into the changed
  files' types and methods). Journeys and suites are always re-read (cheap, cross-file).
- **Renames keep `pke_id`** (ADR-002): for a renamed file, types are paired in order (old fqcn → new fqcn) and
  methods by signature; within a modified file, a removed and an added method with the same body hash are
  paired when the match is unique. The entity is updated in place, gains an `(:Alias {pke_id: <new key id>})`
  (`HAS_ALIAS`) and an `ENTITY_RENAMED` event. The writer resolves aliases before computing relationship and
  evidence ids, so relationships to a renamed method keep their id.

## Java extraction rules (deterministic, never guessed)

- Symbol solver first. When it fails, static dispatch on the receiver's **declared** type (this, a local, a
  parameter, a field, or a type name for static calls): a unique method with that name and arity in the type
  or its repository supertypes. Record accessors and Lombok-generated accessors are recognised; calls on
  external types (imports, `java.lang`, static imports) need no edge. Everything else is listed on the caller
  as `unresolvedCalls`.
- Signatures are erased simple names (`transfer(String,BigDecimal)`), solver-independent and stable.

## Consequences and limits

- Library jars are not on the solver path, so chained calls on library results (Lombok builders, Mockito
  stubbing, Reactor operators) stay unresolved. Next step: add the repository's resolved Maven classpath to the
  type solver.
- An assertion supported by two scopes survives when one of them stops supporting it, until the other scope is
  re-extracted. Removed classes/methods keep their entity (only their relationships are invalidated); entity
  retirement arrives with history queries (M5).
