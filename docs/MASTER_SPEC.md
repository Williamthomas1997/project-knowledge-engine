# Project: AI-Powered Project Knowledge Engine
## Master Architecture and Implementation Specification

You are the principal software architect, senior Java engineer, AI engineer, knowledge-graph architect, and autonomous implementation agent responsible for designing and building this project.

Your task is to build a production-oriented, extensible, AI-powered knowledge management and retrieval platform for software engineering projects.

This is not merely a RAG application, a chatbot, a knowledge graph viewer, or a document search engine.

The goal is to build a continuously evolving project knowledge engine that understands software projects, their business requirements, their technical implementations, their dependencies, their history, and the evidence supporting its knowledge.

The system must combine:

- Spring Boot and Spring AI.
- Neo4j as the primary graph database.
- Retrieval-augmented generation using semantic and keyword retrieval.
- A source-document storage and indexing layer.
- An extensible knowledge schema.
- AI-driven knowledge extraction and reconciliation.
- Configurable skills and processing pipelines.
- Git-based version control for application code and versioned definitions.
- Historical tracking of knowledge changes.
- Knowledge snapshots and reproducibility.
- REST APIs and Model Context Protocol (MCP) integration.
- Autonomous creation and modification of capabilities, pipelines, schemas, and application code.
- Automated testing, validation, recovery, and deployment workflows.

The architecture must prioritize correctness, traceability, extensibility, efficient context retrieval, and minimal manual intervention.

Do not treat the initial requirements as a reason to hardcode the system around a fixed collection of node types or relationship types.

---

## 1. Product Vision

Build a generic knowledge engine with an initial specialization in software engineering projects.

The initial implementation should understand a software project as a collection of interconnected entities and evidence.

Examples include:

- Business capabilities.
- Business features.
- Business requirements.
- Business rules.
- BRDs.
- Technical specifications.
- Architecture documents.
- APIs.
- Microservices.
- Applications and modules.
- Classes and methods.
- Database tables and columns.
- Kafka topics and message queues.
- Configuration properties.
- Infrastructure resources.
- Tests and test suites.
- Security controls.
- Deployment artifacts.
- Git commits.
- Releases.
- Defects and incidents.
- Migration scripts.
- Generated documents.
- AI skills and pipelines.

These examples are illustrative. They must not form a permanently closed list.

The knowledge engine must allow new entity types, relationship types, attributes, validation rules, and processing capabilities to be introduced as requirements evolve.

The initial specialization should make the system useful for real Java/Spring software projects. However, the underlying knowledge model must remain generic enough to represent other domains later.

### 1.1 Primary objectives

The system must:

1. Ingest structured and unstructured project artifacts.
2. Extract meaningful entities, facts, and relationships.
3. Preserve links to the original evidence.
4. Represent knowledge and relationships in Neo4j.
5. Index documents and relevant text for retrieval.
6. Combine graph traversal with semantic and keyword search.
7. Track the evolution of knowledge over time.
8. Maintain historical versions of important entities and relationships.
9. Detect changes in source code and documentation.
10. Update affected knowledge incrementally.
11. Identify stale, conflicting, incomplete, or unsupported knowledge.
12. Generate relevant context for external coding agents.
13. Support autonomous evolution of schemas, skills, pipelines, and application capabilities.
14. Minimize repetitive manual documentation.
15. Generate implementation, testing, migration, and release documentation from the accumulated knowledge.
16. Remain maintainable as the project and knowledge base grow.

### 1.2 Non-goals for the initial implementation

- Do not attempt to build a universal autonomous agent that can safely solve every possible problem.
- Do not hardcode domain-specific assumptions throughout the core engine.
- Do not create unnecessary microservices merely to make the architecture appear sophisticated.
- Do not introduce multiple databases or orchestration frameworks without a concrete requirement.
- Do not implement unrestricted, untested production self-modification.
- Do not assume that an LLM-generated statement is correct merely because it has been stored in Neo4j.

The system should support autonomous evolution through controlled, observable, testable mechanisms.

---

## 2. Technology Strategy

Use the following initial technology direction.

### 2.1 Core application

- Java.
- A current, stable Spring Boot release compatible with the selected Java version.
- Spring AI.
- Maven or another well-justified Java build system.
- REST APIs.
- Appropriate automated testing frameworks.
- Container-based local development where useful.

Select compatible, stable dependency versions at implementation time.

Do not invent library versions. Verify compatibility through official documentation when necessary.

### 2.2 Knowledge graph

Use Neo4j as the primary operational graph database.

Neo4j must support:

- Entity storage.
- Relationship storage.
- Graph traversal.
- Knowledge queries.
- Relationship provenance.
- Current-state retrieval.
- Historical version references.
- Schema metadata.
- Knowledge snapshots.
- Efficient lookup of connected components.

Use Neo4j's native graph capabilities instead of trying to simulate a graph entirely through relational tables or JSON documents.

### 2.3 RAG and semantic retrieval

Use Spring AI to integrate with supported LLM and embedding providers.

OpenAI is an acceptable primary provider, and paid API usage is permitted.

The provider must be configurable.

Avoid coupling the entire knowledge model to OpenAI-specific classes or assumptions.

The retrieval architecture should support:

- Embeddings.
- Semantic search.
- Keyword search.
- Metadata filtering.
- Graph-based retrieval.
- Hybrid GraphRAG.
- Optional reranking.
- Retrieval evaluation.
- Source citations.
- Version-aware retrieval.

Neo4j may initially provide both graph storage and vector indexing, provided the selected version supports the required functionality.

A separate vector database should only be introduced when measured requirements justify it.

Do not maintain redundant copies of the same knowledge without a defined synchronization strategy.

### 2.4 Document storage

Store original documents outside the graph database using an abstraction over filesystem or object storage.

The initial implementation may use local filesystem storage for development.

The design must permit a future object-storage implementation.

Neo4j should contain document metadata, identifiers, version references, and links to relevant knowledge.

The retrieval index should contain searchable document chunks and their metadata.

The original document must remain accessible.

### 2.5 Version control

Use Git for:

- Application source code.
- Tests.
- Knowledge schema definitions.
- Relationship definitions.
- Skill definitions.
- Pipeline definitions.
- Prompt templates.
- Validation policies.
- Migration scripts.
- Versioned configuration.
- Release artifacts where appropriate.

Git is not a replacement for Neo4j or the knowledge-history store.

### 2.6 External interfaces

Provide both:

1. REST APIs.
2. An MCP server compatible with supported coding-agent clients.

Keep the knowledge engine independent of the client interface.

The initial system should be usable through APIs and local development tools before any advanced UI is introduced.

---

## 3. Architectural Principles

The implementation must follow these principles.

### 3.1 Knowledge is independent of its representation

The same logical knowledge may be represented in Neo4j, indexed document chunks, historical events, and version-controlled definitions.

These representations must have clear responsibilities and stable identifiers.

### 3.2 Evidence must remain traceable

Important facts and relationships must retain references to the evidence supporting them.

### 3.3 Current state and historical state are different concerns

Neo4j should efficiently serve the current knowledge graph.

A separate, durable history mechanism must preserve meaningful knowledge changes.

### 3.4 Facts and inferences must remain distinguishable

Directly observed facts must not be silently conflated with AI-generated inferences.

### 3.5 Knowledge schemas must be extensible

New entity types, relationship types, and properties should be introduced through metadata whenever possible.

### 3.6 New capabilities must be extensible

When metadata and existing pipeline capabilities are insufficient, the system must support the creation of new skills, plugins, and application code.

### 3.7 Retrieval must be selective

Do not load the entire knowledge base into every LLM prompt.

Retrieve the smallest useful context that satisfies the task.

### 3.8 Reprocessing must be idempotent

Reprocessing an unchanged source should not create duplicate entities, duplicate relationships, or unnecessary historical changes.

### 3.9 Autonomous operations must be recoverable

Changes must be observable, validated, and recoverable when something fails.

### 3.10 Avoid unnecessary architectural complexity

Prefer a modular monolith initially unless a clear technical requirement justifies independent deployment.

---

## 4. High-Level Architecture

Design the application around the following logical components.

### 4.1 Source ingestion layer

Responsible for discovering, importing, parsing, and identifying source artifacts.

Examples:

- BRDs.
- PDFs.
- DOCX files.
- Markdown documents.
- Text files.
- Java source code.
- API specifications.
- Database schemas.
- Configuration files.
- Git repositories.
- Test reports.
- Deployment manifests.

Each source type should have an appropriate adapter.

### 4.2 Extraction layer

Transforms source artifacts into candidate knowledge.

Examples:

- Business requirement extraction.
- Entity extraction.
- Business-rule extraction.
- API specification parsing.
- Java symbol analysis.
- Method-call extraction.
- Dependency analysis.
- Configuration relationship extraction.
- Test-to-feature mapping.
- Document section extraction.

Use deterministic extraction where reliable.

Use AI for interpretation, classification, summarization, and higher-level inference.

### 4.3 Knowledge schema registry

Maintains definitions of supported entity types, relationship types, properties, constraints, and validation policies.

The registry must support schema evolution.

### 4.4 Knowledge graph service

Provides controlled operations for creating, updating, querying, and retiring graph entities and relationships.

All graph writes should go through this service or an equivalent governed boundary.

### 4.5 Provenance and history service

Records meaningful knowledge changes, source references, processing metadata, and historical versions.

### 4.6 Retrieval service

Combines graph traversal, semantic search, keyword search, metadata filtering, and optional reranking.

### 4.7 AI orchestration service

Coordinates LLM calls, tools, skills, pipelines, validation, and knowledge updates.

### 4.8 Skill and pipeline registry

Stores and manages reusable skills, pipeline definitions, versions, execution status, and dependencies.

### 4.9 Autonomous evolution service

Evaluates requests for new capabilities and proposes or executes schema, pipeline, skill, and application-code changes.

### 4.10 External access layer

Exposes the system through REST and MCP.

### 4.11 Observability layer

Records:

- Ingestion outcomes.
- Pipeline execution.
- Model usage.
- Retrieval quality.
- Knowledge changes.
- Validation failures.
- Schema migrations.
- Application releases.
- Recovery operations.
- API and MCP activity.

---

## 5. Extensible Knowledge Model

This is one of the most important requirements.

Do not implement the knowledge model as a closed set of Java enums that must be modified every time a new relationship type appears.

### 5.1 Entity definitions

Support dynamically registered entity types.

Each type definition should be capable of specifying:

- Type identifier.
- Human-readable name.
- Description.
- Property definitions.
- Required properties.
- Property data types.
- Validation rules.
- Identifier strategy.
- Relationships the entity may participate in.
- Applicable extraction methods.
- Applicable retrieval behavior.
- Schema version.
- Lifecycle status.

Illustrative example:

```json
{
  "type": "BusinessFeature",
  "version": "1.0.0",
  "description": "A business capability or application feature",
  "properties": {
    "name": {
      "type": "string",
      "required": true
    },
    "description": {
      "type": "string"
    },
    "status": {
      "type": "string"
    }
  }
}
```

This example is not the final schema.

Design a formal schema specification and validate definitions before registering them.

### 5.2 Relationship definitions

Support dynamically registered relationship types.

Each relationship definition should be capable of specifying:

- Relationship type identifier.
- Description and semantics.
- Permitted source types.
- Permitted target types.
- Relationship properties.
- Required evidence.
- Validation rules.
- Extraction strategies.
- Discovery policy.
- Approval policy.
- Temporal behavior.
- Query hints.
- Schema version.
- Lifecycle status.

Example:

```json
{
  "type": "REGULATORY_DEPENDENCY",
  "version": "1.0.0",
  "description": "Indicates a dependency on a regulatory control",
  "sourceTypes": [
    "BusinessFeature"
  ],
  "targetTypes": [
    "RegulatoryControl"
  ],
  "properties": {
    "rationale": {
      "type": "string"
    },
    "verificationStatus": {
      "type": "string"
    }
  }
}
```

The system must not require application-code changes merely to register a valid relationship that can be handled by existing generic capabilities.

### 5.3 Generic graph representation

Prefer stable generic entity and relationship models.

Use a Neo4j representation that permits dynamically registered types while preserving efficient graph traversal.

You may use native Neo4j labels and relationship types where appropriate, or a generic representation for dynamic types.

Evaluate the trade-offs.

Do not blindly use dynamically constructed Cypher identifiers. Validate schema identifiers and prevent query injection.

Separate the public logical type model from the internal database representation where that improves maintainability.

### 5.4 Metadata versus behavior

A new relationship type may require only metadata.

Another may require a new extraction algorithm.

Another may require an external integration.

The system must distinguish these cases.

It should reuse existing capabilities whenever possible and generate new code only when existing capabilities are insufficient.

---

## 6. Identity, Deduplication, and Entity Resolution

Implement stable identities for logical entities.

Do not use mutable names, file paths, line numbers, or display labels as the sole identity of an entity.

For example, a Java class may move to another package, a method may be renamed, or a document may receive a new title.

The system should attempt to preserve logical identity where justified.

Use appropriate identity strategies for different entity types.

Potential identifiers include:

- Repository and stable source identifiers.
- Fully qualified Java symbols.
- API operation identifiers.
- Document IDs.
- Content hashes.
- External identifiers.
- Explicit canonical IDs.
- Database object identifiers.

Content hashes should help detect changes, but should not automatically become the permanent identity of an entity whose content is expected to evolve.

### 6.1 Entity resolution

Support:

- Exact identity matches.
- Canonical identifier matches.
- Alias matching.
- Controlled semantic matching.
- Duplicate detection.
- Merge proposals.
- Split operations when two incorrectly merged entities are discovered.

AI-assisted resolution must preserve evidence and confidence metadata.

For ambiguous cases, use the configured resolution policy.

Do not silently merge two potentially different business requirements merely because their descriptions are similar.

### 6.2 Relationship identity

Relationships need stable identities or equivalent deterministic keys.

Where possible, distinguish a logical relationship from its historical versions.

For example, the relationship between a specific caller method and callee method may retain a logical identity while its evidence and validity change.

When a relationship's endpoints or semantics change materially, determine whether it should be updated or replaced.

Document these rules.

---

## 7. Relationship Types and Relationship Semantics

The initial software-project implementation should support examples such as:

**Business relationships**

- IMPLEMENTS_REQUIREMENT.
- DEPENDS_ON_FEATURE.
- CONSTRAINED_BY.
- DEFINED_BY.
- DERIVED_FROM.
- RELATED_TO.
- SUPERSEDES_REQUIREMENT.

**Technical relationships**

- CALLS.
- IMPORTS.
- IMPLEMENTS_INTERFACE.
- EXTENDS.
- DEPENDS_ON_LIBRARY.
- EXPOSES_API.
- CONSUMES_API.
- PUBLISHES_TO.
- CONSUMES_FROM.
- READS_FROM.
- WRITES_TO.
- USES_CONFIGURATION.

**Verification relationships**

- TESTED_BY.
- VERIFIED_BY.
- COVERS.
- VALIDATES.
- REGRESSION_TESTED_BY.

**Operational relationships**

- DEPLOYED_TO.
- RUNS_ON.
- CONFIGURED_BY.
- MONITORED_BY.

**Lifecycle relationships**

- INTRODUCED_BY.
- MODIFIED_BY.
- REMOVED_BY.
- SUPERSEDES.
- REPLACED_BY.

These are initial examples only.

Do not assume that every relationship must belong to one of these categories.

The schema registry must support new relationship types and, where useful, relationship categories.

Business and technical relationships must remain distinct.

Two features must not be considered business-related merely because they use the same Java utility.

Likewise, two features may be business-related even if they are implemented in entirely separate services.

---

## 8. Evidence, Provenance, and Confidence

Every meaningful extracted fact or relationship should be traceable to its source.

Support evidence from:

- Documents.
- Document sections.
- Code locations.
- Git commits.
- API specifications.
- Configuration files.
- Database schemas.
- Test results.
- Human-provided information.
- AI inference.
- External integrations.

A provenance record should be capable of storing:

- Source artifact ID.
- Source version.
- Source location.
- Content hash.
- Extraction method.
- Pipeline version.
- Skill version.
- Model provider and model identifier, where applicable.
- Extraction timestamp.
- Evidence excerpt or structured evidence reference.
- Verification status.
- Confidence metadata, when meaningful.

Do not represent an LLM-generated confidence score as a calibrated probability unless it has been independently calibrated.

Distinguish:

1. Directly observed facts.
2. Deterministically extracted facts.
3. AI-inferred facts.
4. Human-verified facts.
5. Contradicted or superseded facts.

Preserve the distinction between a documented requirement, the actual code implementation, and an AI interpretation of their relationship.

When evidence conflicts, record the conflict and apply an explicit reconciliation policy.

Do not silently overwrite one source with another.

---

## 9. Source Ingestion and Processing

Implement ingestion as an extensible framework.

Each source adapter should produce a common internal representation while retaining source-specific metadata.

### 9.1 Document ingestion

Support an initial set of practical document formats.

For PDFs and office documents, preserve useful structure such as:

- Headings.
- Sections.
- Tables, where extractable.
- Page numbers.
- Paragraphs.
- Source locations.

Do not assume that all PDFs contain machine-readable text.

Provide an extensible parsing strategy for scanned documents where required.

### 9.2 Code ingestion

For Java projects, prefer deterministic source analysis using appropriate parsing or compiler tooling.

Extract facts such as:

- Packages.
- Classes.
- Interfaces.
- Methods.
- Imports.
- Inheritance.
- Method calls.
- Annotations.
- REST endpoint mappings.
- Dependency injection relationships.
- Relevant configuration references.
- Test relationships.

Support incremental analysis based on changed files and Git commits.

Do not rely exclusively on LLMs to discover relationships that can be extracted directly from source code.

### 9.3 Git ingestion

Track:

- Repository identity.
- Commit identity.
- Changed files.
- Relevant diffs.
- Branch and release references, when available.
- Source versions used for knowledge extraction.

A Git commit should be linked to the knowledge changes produced by processing that commit.

Do not assume every knowledge change originates from a Git commit.

A newly uploaded BRD, for example, has its own source identity and version.

### 9.4 Incremental processing

Use content hashes, source versions, dependency information, and processing metadata to avoid unnecessary reprocessing.

Reprocess affected material when:

- A source artifact changes.
- A relevant parser changes.
- An extraction pipeline changes.
- A schema migration requires recomputation.
- An embedding model changes.
- An explicit reindexing operation is requested.

Avoid rebuilding the entire knowledge base for every small update.

---

## 10. RAG and Hybrid GraphRAG

Implement a retrieval layer that combines structured graph knowledge with unstructured document retrieval.

### 10.1 Retrieval modes

Support:

1. Semantic search.
2. Keyword search.
3. Metadata-filtered search.
4. Graph traversal.
5. Hybrid retrieval.
6. Optional reranking.

The system should select retrieval strategies based on the query and available knowledge.

### 10.2 Example

A user asks:

"Which business requirements and APIs are affected if beneficiary currency validation changes?"

The system should:

1. Identify the relevant concepts.
2. Retrieve matching entities from Neo4j.
3. Traverse relevant business and technical relationships.
4. Retrieve associated source-document sections.
5. Search additional documents semantically and lexically.
6. Identify conflicting or stale information.
7. Return relevant context with evidence references.

Do not assume that vector similarity alone identifies all affected components.

### 10.3 Chunking and indexing

Maintain stable identifiers for documents and chunks.

Each indexed chunk should carry appropriate metadata, including:

- Document ID.
- Document version.
- Section or location.
- Content hash.
- Chunking strategy version.
- Embedding model identifier.
- Index generation identifier.
- Source repository or project, where applicable.

Use deterministic chunk identifiers when possible.

### 10.4 Embedding lifecycle

Track embedding model changes separately from source and knowledge changes.

When an embedding model changes, re-embed the required documents without automatically treating that operation as a change to the underlying business requirements.

Support index generations and a controlled switch to a validated index.

Avoid mixing incompatible embedding dimensions or models within an index without an explicit, supported strategy.

### 10.5 Retrieval evaluation

Implement an evaluation framework using representative questions and expected evidence.

Measure appropriate metrics such as:

- Relevant-source recall.
- Precision at selected cutoffs.
- Ranking quality.
- Citation correctness.
- Retrieval latency.
- Token consumption.
- Completeness of graph traversal.
- Unsupported-claim frequency, where measurable.

Do not claim that retrieval is reliable merely because it returns a result.

---

## 11. Knowledge Versioning and Historical State

Versioning is a foundational capability.

The system must preserve meaningful knowledge history rather than simply overwriting nodes and relationships.

Use a hybrid strategy:

- Git for application code and versioned definitions.
- Neo4j for current operational knowledge.
- An append-only knowledge-change history.
- Historical entity and relationship versions.
- Knowledge snapshots for important states.
- Versioned source artifacts.
- Version-aware retrieval indexes.

### 11.1 Entity versioning

Track meaningful changes to entities.

Examples include:

- Requirement description changes.
- API contract changes.
- Class identity or location changes.
- Status changes.
- Entity merges or splits.
- Superseded definitions.

Historical versions must remain distinguishable from current state.

### 11.2 Relationship versioning

Track:

- Relationship creation.
- Relationship modification.
- Relationship verification.
- Relationship invalidation.
- Relationship removal.
- Relationship replacement.
- Relationship merges or splits.

Do not delete historical relationship evidence simply because the relationship no longer exists in the current implementation.

Represent current validity and historical existence separately.

### 11.3 Append-only knowledge history

Implement a durable mechanism for recording meaningful knowledge changes.

Potential event types include:

- ENTITY_CREATED.
- ENTITY_UPDATED.
- ENTITY_MERGED.
- ENTITY_SPLIT.
- RELATIONSHIP_CREATED.
- RELATIONSHIP_UPDATED.
- RELATIONSHIP_INVALIDATED.
- RELATIONSHIP_REMOVED.
- SCHEMA_REGISTERED.
- SCHEMA_MIGRATED.
- DOCUMENT_VERSION_ADDED.
- PIPELINE_VERSION_CHANGED.
- KNOWLEDGE_RECONCILIATION_COMPLETED.

The event model must be extensible.

Each event should include suitable identifiers, timestamps, source references, processing metadata, and change details.

Do not store unnecessary sensitive source content in the event log. Reference immutable source artifacts where practical.

### 11.4 Transaction consistency

Define how current graph updates and history events remain consistent.

A graph mutation must not silently succeed while its corresponding history record is lost.

Evaluate an appropriate mechanism, such as a transactional outbox or a durable staged change protocol.

If a dedicated event store is introduced, explain why it is necessary.

Do not introduce a distributed messaging platform solely to solve a problem that can be handled reliably within the initial architecture.

### 11.5 Historical reconstruction

Provide a defined method for reconstructing or querying historical knowledge states.

The implementation may use:

- Versioned entity and relationship records.
- Append-only events.
- Periodic checkpoints.
- Knowledge snapshots.

Document the reconstruction algorithm and its limitations.

### 11.6 Retention

Preserve meaningful knowledge history for as long as the configured retention policy requires.

Do not automatically discard historical business requirements or relationship history merely to reduce storage.

Derived artifacts, temporary processing data, and embeddings may have different retention policies.

---

## 12. Knowledge Snapshots

Implement a knowledge snapshot concept.

A snapshot identifies a reproducible or sufficiently well-defined state of the knowledge base.

A snapshot should reference relevant versions of:

- Source repositories and Git commits.
- Source documents.
- Knowledge schemas.
- Extraction pipelines.
- Skills.
- Model configurations.
- Embedding configurations.
- Knowledge history position.
- Retrieval index generation.
- Application release.

A snapshot should make it possible to answer:

"What did the knowledge engine know about this project when a particular release was prepared?"

Snapshots should support:

- Comparison.
- Historical analysis.
- Release reporting.
- Regression investigation.
- UAT documentation.
- Production release documentation.
- Recovery and audit workflows.

Do not assume a Git commit alone identifies the entire knowledge state.

Some knowledge changes originate outside Git.

---

## 13. Schema Evolution and Migration

The system must support the introduction and modification of entity types, relationship types, and properties.

### 13.1 Schema registry

Maintain versioned schema definitions in a form that can be validated and managed programmatically.

Use Git to version definitions and releases.

Use the operational registry to determine which definitions are active.

Define how Git changes are validated and promoted into the running system.

### 13.2 Migration capabilities

Support:

- Adding new entity types.
- Adding new relationship types.
- Adding optional properties.
- Adding required properties with migration strategies.
- Renaming properties.
- Deprecating properties.
- Changing relationship constraints.
- Migrating existing entities and relationships.
- Recomputing derived knowledge when necessary.

Every migration should declare its expected effects.

### 13.3 Migration safety

Before executing a migration:

1. Validate the new schema.
2. Identify affected data.
3. Estimate the scope of the change.
4. Check compatibility.
5. Generate or select a migration strategy.
6. Test against representative data.
7. Record a migration plan.
8. Execute using an appropriate recovery strategy.
9. Validate graph integrity.
10. Record the result.

Avoid assuming every migration can be rolled back through a simple inverse operation.

For destructive or non-reversible changes, use checkpoints, retained historical versions, or other appropriate recovery mechanisms.

### 13.4 Schema retirement

Do not physically remove historical schema definitions while they are still needed to interpret historical knowledge.

Support deprecation and retirement policies.

---

## 14. Skills and Pipeline Architecture

Skills and pipelines must be first-class, versioned capabilities.

### 14.1 Skills

A skill describes reusable expertise or a procedure.

Examples:

- Extract requirements from a BRD.
- Analyze Java dependencies.
- Map APIs to business features.
- Identify likely regression impacts.
- Reconcile conflicting requirements.
- Generate migration documentation.
- Evaluate graph consistency.

A skill may contain:

- Identifier.
- Name.
- Description.
- Version.
- Instructions.
- Input schema.
- Output schema.
- Required capabilities.
- Validation requirements.
- Applicable source types.
- Applicable entity or relationship types.
- Dependencies.
- Execution policy.
- Tests or evaluation cases.

Use a practical, version-controlled representation such as Markdown combined with structured metadata.

Do not assume that a natural-language skill can execute arbitrary operations without corresponding tools.

### 14.2 Pipelines

A pipeline coordinates multiple capabilities.

For example:

**Document ingestion:**

1. Discover the source.
2. Identify its version.
3. Parse its content.
4. Extract candidate knowledge.
5. Validate candidate entities and relationships.
6. Resolve identities.
7. Record provenance.
8. Update the knowledge graph.
9. Update document indexes.
10. Record the outcome.

**Code analysis:**

1. Identify changed files.
2. Parse the source code.
3. Extract technical facts.
4. Identify potentially affected entities.
5. Reconcile existing relationships.
6. Update current knowledge.
7. Preserve historical changes.
8. Refresh affected retrieval material.
9. Record processing metadata.

Pipelines must be configurable and versioned.

Do not hardcode every workflow into a single large orchestration class.

### 14.3 Pipeline execution

Support:

- Execution IDs.
- Input references.
- Pipeline version.
- Step-level status.
- Retry policies.
- Timeouts.
- Idempotency.
- Error classification.
- Recovery or compensation.
- Output references.
- Observability.
- Cancellation where appropriate.

Use an appropriate orchestration mechanism for the initial scale.

Keep the pipeline model extensible so that a more sophisticated workflow engine can be introduced later.

---

## 15. Autonomous Knowledge Evolution

The system must support autonomous changes to its own knowledge model and capabilities.

This is a primary product requirement, not an optional future idea.

### 15.1 Evolution requests

The system should accept requests such as:

"Introduce a new relationship type for security dependencies. Identify the required metadata, update the extraction pipeline, inspect existing project artifacts, and discover applicable relationships."

Another example:

"Add support for a new source format. Create the ingestion adapter, tests, and pipeline integration."

### 15.2 Evolution planning

The AI should determine:

- What capability is missing.
- Whether metadata alone is sufficient.
- Whether an existing skill or pipeline can be extended.
- Whether new code is necessary.
- Which schemas or services are affected.
- Which migrations are required.
- How the change will be tested.
- How it will be deployed.
- How it can be recovered if it fails.

### 15.3 Three extension levels

Support these levels.

**Level 1: Metadata-only evolution.** Register entity types, relationship types, properties, validation rules, and supported extraction policies without changing application code.

**Level 2: Workflow evolution.** Create or modify skills, pipelines, and configurations using existing executable capabilities.

**Level 3: Code evolution.** Generate new Java classes, adapters, plugins, tests, migration scripts, and other required application changes.

Do not require code changes when metadata or workflow configuration is sufficient.

### 15.4 Autonomous implementation workflow

Use a controlled workflow:

1. Receive the requested capability.
2. Analyze the existing architecture.
3. Generate an implementation plan.
4. Identify affected files, schemas, pipelines, and graph data.
5. Create an isolated Git branch or worktree.
6. Implement the change.
7. Generate or update tests.
8. Run formatting, compilation, and tests.
9. Run static analysis where available.
10. Validate schema and migration compatibility.
11. Test on isolated or representative data.
12. Run knowledge-integrity checks.
13. Deploy to a test environment.
14. Validate the new capability.
15. Promote or activate the capability according to the configured autonomy policy.
16. Record the release and knowledge snapshot.
17. Preserve diagnostics and recover if validation fails.

The objective is autonomous execution with minimal manual intervention, not unbounded modification without verification.

### 15.5 Recovery

If a generated change fails:

- Preserve logs and diagnostics.
- Identify affected resources.
- Revert the code branch or deployment when appropriate.
- Restore or reconcile affected knowledge.
- Avoid deleting unrelated successful work.
- Record the failure and recovery action.
- Retry only when the failure classification justifies it.

Provide a clear mechanism for preventing an unsuccessful autonomous change from repeatedly reapplying itself.

---

## 16. Source-Specific Relationship Policies

Relationship creation should follow policies based on evidence source and relationship semantics.

Do not use a single approval rule for every relationship.

### 16.1 Deterministically extracted relationships

Examples include direct method calls identified from parsed Java source.

When the extraction is reliable and the source is current, the system should be able to update the corresponding technical relationship automatically.

The relationship should retain evidence identifying the source file, symbol, and relevant commit.

### 16.2 AI-inferred business relationships

Business relationships inferred from document interpretation may be less certain.

The system should apply a configurable policy based on:

- Relationship type.
- Source quality.
- Evidence.
- Extraction method.
- Confidence metadata.
- Potential impact.

The default policy should minimize manual intervention.

Automatically store eligible inferences while preserving their inferred status and evidence.

Use review gates only when the configured risk or verification policy requires them.

### 16.3 Contradictory evidence

If a new source conflicts with existing knowledge:

- Preserve both sources.
- Record the conflict.
- Identify affected entities and relationships.
- Apply a configured reconciliation policy.
- Avoid silently treating the newest source as universally authoritative.

Different source types may have different authority for different questions.

For example, source code may be authoritative for an observed method call, while a BRD is authoritative for a documented business requirement.

Neither source alone proves that the implementation conforms to the requirement.

---

## 17. REST API

Expose the knowledge engine through documented REST APIs.

Initial API capabilities should include:

**Knowledge retrieval**

- Search entities.
- Search relationships.
- Retrieve entity details.
- Traverse graph neighborhoods.
- Query related business requirements.
- Retrieve source evidence.
- Run hybrid retrieval.
- Generate task-specific context.

**Knowledge management**

- Register entity types.
- Register relationship types.
- Update schema definitions.
- Create or update knowledge entities.
- Create or update relationships.
- Inspect provenance.
- Query historical versions.
- Compare knowledge snapshots.

**Ingestion**

- Submit documents.
- Register source repositories.
- Trigger ingestion pipelines.
- Trigger incremental code analysis.
- Inspect ingestion status.
- Retry failed processing jobs.

**Pipeline and skill management**

- List skills and pipelines.
- Inspect versions.
- Trigger execution.
- Inspect execution results.
- Validate definitions.
- Register new definitions through the appropriate controlled interface.

**Autonomous evolution**

- Submit evolution requests.
- Inspect implementation plans.
- Inspect generated changes.
- Retrieve test results.
- Inspect deployment status.
- Inspect recovery results.

Design clear request and response schemas.

Provide consistent error handling, pagination where needed, input validation, and API documentation.

Do not expose unrestricted database queries or arbitrary filesystem operations through public endpoints.

---

## 18. MCP Integration

Implement an MCP server for compatible coding agents.

The MCP layer should expose useful operations, such as:

- Search project knowledge.
- Retrieve business requirements.
- Find relevant BRD sections.
- Retrieve API and service details.
- Traverse technical dependencies.
- Find affected components.
- Retrieve source evidence.
- Compare knowledge snapshots.
- Inspect current schema definitions.
- Discover available skills.
- Trigger permitted knowledge-ingestion operations.

Tool names and input schemas should be descriptive and stable.

Avoid creating a separate MCP implementation of business logic.

MCP tools should call the same application services used by REST APIs.

Do not expose unrestricted autonomous code execution through MCP.

Autonomous evolution operations must follow the configured execution policy.

---

## 19. Identity and Synchronization Across Systems

Define explicit synchronization responsibilities between:

- Source documents.
- Git repositories.
- Neo4j.
- Knowledge history.
- Retrieval indexes.
- Schema definitions.
- Pipelines and skills.
- Application releases.

Every artifact should have a stable logical identity and a version identity where applicable.

### 19.1 Document synchronization

Detect new document versions.

Determine whether content has changed.

Update affected knowledge and indexed chunks.

Retain the previous document version and its evidence references.

### 19.2 Code synchronization

Use repository identity and commit information to determine the source state being analyzed.

Process changed files incrementally where practical.

Update technical relationships that are no longer valid.

Create new relationships supported by the changed source.

### 19.3 Graph and index synchronization

Treat the graph and retrieval index as different representations of related knowledge.

Define how failed indexing operations are retried.

Do not silently report ingestion as fully successful if graph updates succeeded but required retrieval-index updates failed.

Expose partial completion and recovery status.

### 19.4 Synchronization observability

Provide a way to identify:

- Sources that have not been processed.
- Sources that changed after their last successful ingestion.
- Graph entities that reference obsolete evidence.
- Index entries that are stale.
- Failed or incomplete pipelines.
- Schema migrations awaiting completion.
- Knowledge updates requiring reconciliation.

---

## 20. Knowledge Context Generation

The system should provide a dedicated capability for generating concise, relevant context for coding agents.

The objective is not to retrieve the maximum possible amount of information.

The objective is to retrieve enough verified and relevant knowledge to complete a particular task.

For a request such as:

"Modify beneficiary currency validation in the transfer flow."

The engine should attempt to retrieve:

- Relevant business requirements.
- Applicable BRD sections.
- Relevant API contracts.
- Related services and methods.
- Technical dependencies.
- Important configuration.
- Related tests.
- Known exceptions.
- Relevant historical changes.
- Conflicting or incomplete evidence.

The context generator should:

1. Interpret the task.
2. Identify relevant entities.
3. Traverse important graph relationships.
4. Retrieve supporting documents.
5. Rank and deduplicate evidence.
6. Apply token or context budgets.
7. Preserve source references.
8. Distinguish facts from inferences.
9. Report uncertainty where material.

Support configurable retrieval depth and context budgets.

Avoid flooding coding agents with unrelated graph neighbors.

---

## 21. Security and Autonomous Execution Boundaries

Autonomous evolution is required, but the implementation must still define security boundaries.

Implement appropriate controls for:

- API authentication and authorization.
- Secret management.
- External model credentials.
- File access.
- Repository access.
- Database access.
- Generated-code execution.
- Build and test execution.
- Deployment permissions.
- Audit logs.
- Sensitive source content.

Do not place API keys in Git, prompts, generated reports, or logs.

Generated application code must be tested in an appropriately isolated environment before execution or deployment.

The default development environment should not permit an LLM to execute arbitrary commands with unrestricted access to the host.

Use explicit execution policies for development, testing, and production.

The objective is to minimize human intervention without compromising data integrity or security.

---

## 22. Testing Strategy

Testing must be part of the implementation, not an afterthought.

### 22.1 Unit tests

Test:

- Entity identity.
- Relationship identity.
- Schema validation.
- Provenance handling.
- Versioning logic.
- Idempotent ingestion.
- Relationship policies.
- Retrieval result formatting.
- Migration planning.
- Pipeline execution state.

### 22.2 Integration tests

Test:

- Neo4j persistence.
- Graph traversal.
- Vector retrieval.
- Document storage.
- Source ingestion.
- Git integration.
- History persistence.
- Snapshot creation.
- REST APIs.
- MCP tools.

### 22.3 Failure tests

Test cases should include:

- Graph update succeeds but index update fails.
- History persistence fails.
- A document is reprocessed.
- A source version is removed or replaced.
- A schema migration fails.
- A generated code change fails compilation.
- A pipeline is interrupted.
- A relationship becomes invalid.
- Duplicate entities are discovered.
- Two sources contradict each other.

### 22.4 AI evaluation

Create evaluation datasets for representative extraction and retrieval tasks.

Measure whether:

- Required evidence is retrieved.
- Relevant relationships are found.
- Unsupported relationships are avoided.
- Source references are correct.
- Schema definitions are valid.
- Generated context is useful.
- Token usage remains reasonable.

Use deterministic tests wherever possible.

AI-based tests should have clear evaluation criteria rather than relying solely on subjective output inspection.

---

## 23. Observability and Cost Management

The system should record relevant metrics for:

- API latency.
- Pipeline latency.
- Failed ingestion jobs.
- Graph operations.
- Retrieval latency.
- Model requests.
- Token usage.
- Embedding generation.
- Retry counts.
- Schema changes.
- Autonomous code changes.
- Recovery operations.

Track model provider, model identifier, and relevant configuration versions.

Use caching only where correctness can be preserved.

Avoid re-embedding unchanged content.

Avoid repeatedly invoking an LLM to extract knowledge that can be obtained through deterministic parsing.

The system should make it possible to understand the cost of processing a document, analyzing a commit, answering a query, or evolving a capability.

---

## 24. Deployment and Development Environment

Provide a reproducible development environment.

Use containers where appropriate for infrastructure such as Neo4j.

Provide:

- Environment configuration.
- Example configuration files without secrets.
- Database initialization procedures.
- Schema initialization.
- Migration commands.
- Test commands.
- Local startup instructions.
- Backup and recovery instructions.
- Troubleshooting documentation.

Use environment variables or an appropriate secret-management mechanism for credentials.

Do not require paid API access for basic application startup or deterministic unit tests.

The system should start in a configuration that permits local development and testing even if an external LLM provider has not yet been configured.

---

## 25. Initial Delivery Scope

Build the platform incrementally.

Do not attempt to implement every possible future capability in the first iteration.

The initial release must establish the core architecture and demonstrate the complete knowledge lifecycle.

**Phase 1: Foundation.** Spring Boot application; Neo4j integration; core entity and relationship models; schema registry; stable identifiers; provenance model; basic REST APIs; basic tests; local development environment.

**Phase 2: Source ingestion.** Document ingestion; document version tracking; text extraction; chunking; content hashes; initial Java source analysis; Git commit references; idempotent ingestion.

**Phase 3: Retrieval.** Embedding integration; semantic search; keyword search; graph traversal; hybrid retrieval; evidence references; context generation.

**Phase 4: Versioning.** Entity versioning; relationship versioning; durable knowledge history; knowledge snapshots; historical queries; snapshot comparison; Git-to-knowledge references; recovery procedures.

**Phase 5: Skills and pipelines.** Skill registry; pipeline registry; versioned definitions; pipeline execution tracking; retry and recovery behavior; initial BRD and code-analysis pipelines.

**Phase 6: Agent integration.** MCP server; useful retrieval tools; context-generation tools; pipeline invocation tools; appropriate authorization and execution policies.

**Phase 7: Autonomous evolution.** Evolution-request handling; schema-change proposals; metadata-driven extensions; skill and pipeline generation; Git branch or worktree integration; generated-code changes; automated testing; controlled activation; recovery and audit mechanisms.

These phases may be refined after repository inspection and architecture validation.

The objective is a working, integrated system at every stage.

---

## 26. Required Demonstration Scenario

The initial implementation should demonstrate the following end-to-end workflow.

Assume the project contains:

- A BRD describing an international transfer requirement.
- A Java service implementing the transfer.
- An API used by the service.
- A shared currency-conversion component.
- A test covering the currency-validation behavior.

The system must be able to:

1. Ingest the BRD.
2. Extract the relevant business requirement.
3. Store the requirement in Neo4j.
4. Preserve the BRD reference and section information.
5. Analyze the Java source code.
6. Extract the API and technical dependencies.
7. Link the requirement to the implementation where sufficient evidence exists.
8. Index the document for semantic and keyword retrieval.
9. Answer a query about the transfer flow using graph and document evidence.
10. Detect a relevant source-code change.
11. Update technical relationships.
12. Preserve the previous relationship history.
13. Generate a new knowledge snapshot.
14. Compare the knowledge before and after the change.
15. Expose the relevant information through REST and MCP.
16. Introduce a new metadata-defined relationship type without modifying the core engine, provided the required behavior is supported by existing capabilities.
17. Demonstrate how the system handles a new capability that genuinely requires code.
18. Test the generated change in an isolated environment and record the outcome.

This demonstration must use working code and actual persistence rather than mocked responses for the core knowledge lifecycle.

---

## 27. Required Documentation

Maintain useful project documentation as implementation evolves.

At minimum, provide:

- README.
- Architecture overview.
- Component responsibilities.
- Data model.
- Schema registry design.
- Relationship and provenance model.
- Versioning strategy.
- Event-history strategy.
- Snapshot and reconstruction design.
- Retrieval architecture.
- Pipeline and skill architecture.
- Autonomous evolution workflow.
- API documentation.
- MCP tool documentation.
- Configuration guide.
- Local development guide.
- Testing guide.
- Migration and recovery guide.
- Known limitations.
- Future extension points.

Documentation must describe the implemented system, not merely the originally intended architecture.

Update relevant documentation when architectural decisions change.

---

## 28. Implementation Instructions

Follow these instructions while building the project.

**Step 1: Inspect the environment.** Determine whether an existing repository or project is available. If one exists, inspect it before making changes. Preserve useful existing work. Do not assume the repository is empty. If no repository exists, establish a clear project structure.

**Step 2: Produce an implementation plan.** Before extensive coding, provide: proposed architecture; module structure; major data models; persistence strategy; versioning strategy; schema evolution approach; retrieval strategy; testing strategy; identified risks; initial milestone plan. Resolve critical contradictions before implementation. Do not repeatedly ask for approval on routine technical decisions that can be made safely from the requirements. When a material ambiguity cannot be resolved without changing the product's behavior, document the ambiguity and choose a reversible default where practical.

**Step 3: Verify technology compatibility.** Verify the selected Spring Boot, Spring AI, Neo4j, Java, and MCP integration versions. Use official documentation and actual build verification. Avoid unsupported or obsolete APIs.

**Step 4: Establish architecture boundaries.** Create clear module or package boundaries for: domain model; knowledge schema; graph persistence; source ingestion; extraction; provenance and history; retrieval; AI orchestration; skills and pipelines; autonomous evolution; REST; MCP; observability. Use a modular monolith initially unless a concrete requirement justifies a different deployment architecture.

**Step 5: Implement incrementally.** Build one coherent vertical slice at a time. For each milestone: (1) implement the capability; (2) add tests; (3) run the relevant tests; (4) fix failures; (5) update documentation; (6) record architectural decisions; (7) report completion and known limitations. Do not claim tests passed unless they were actually executed successfully. Do not replace required implementations with placeholder methods or mock responses and then describe the system as complete.

**Step 6: Keep decisions explicit.** Maintain an architecture decision record for important choices, for example: how graph types are represented; how entity identity is resolved; how relationship history works; how graph updates and history events remain consistent; how source documents are versioned; how embeddings are refreshed; how schema migrations work; how autonomous code changes are validated. When changing an earlier decision, document the reason and the migration implications.

**Step 7: Preserve extensibility.** Before hardcoding a new capability, ask: "Can this be represented as metadata, a reusable pipeline, or an existing plugin instead?" If yes, prefer the reusable mechanism. If new executable behavior is genuinely required, implement it through a well-defined extension point. Do not create unnecessary abstractions for hypothetical requirements, but do not make the core model rigid.

**Step 8: Report actual implementation status.** At the end of each milestone, provide: what was implemented; files and modules created or modified; tests executed and their actual results; database migrations added; configuration required; known issues; next recommended milestone. Do not claim the entire system is complete merely because the initial code generation finished.

---

## 29. Acceptance Criteria

The initial platform should not be considered complete until the following are demonstrated.

**Knowledge representation**

- Entity types can be registered and validated.
- Relationship types can be registered and validated.
- Stable identities are supported.
- Duplicate ingestion does not create unnecessary duplicates.
- Relationships preserve evidence and provenance.

**Source processing**

- At least one document format is ingested successfully.
- Source locations can be retained.
- Java technical relationships can be extracted deterministically where supported.
- Source versions can be identified.
- Incremental reprocessing works.

**Retrieval**

- Semantic retrieval works with a configured embedding provider.
- Keyword retrieval is available.
- Graph traversal returns connected entities.
- Hybrid retrieval combines relevant results.
- Responses can cite their underlying evidence.
- Retrieval behavior can be evaluated.

**Versioning**

- Meaningful entity changes are recorded.
- Relationship creation and invalidation preserve history.
- Git commits can be linked to relevant knowledge changes.
- Non-Git sources have their own version identities.
- Knowledge snapshots can be created and compared.
- Failure and recovery behavior is tested.

**Extensibility**

- A new metadata-supported relationship type can be registered without changing core application code.
- Skills and pipelines are versioned.
- New executable capabilities can be introduced through a controlled extension workflow.

**Autonomous evolution**

- The system can analyze an extension request.
- It can generate a structured implementation plan.
- It can create an isolated code change.
- It can run relevant tests.
- Failed changes are detected.
- Recovery is recorded and tested.
- Successful capabilities can be activated according to policy.

**Interfaces**

- REST APIs are documented and tested.
- MCP tools use the same underlying application services.
- Coding agents can retrieve useful project context without receiving the entire knowledge base.

**Maintainability**

- The project builds successfully.
- Automated tests execute successfully.
- Configuration is documented.
- No secrets are committed.
- Architecture documentation reflects the implementation.

---

## 30. Final Architectural Directive

The most important requirement is not the use of any particular framework or database.

It is the ability to maintain a reliable, evolving representation of a software project and make the relevant knowledge available to AI coding agents efficiently.

The system must distinguish:

- What the source says.
- What the code does.
- What relationships have been directly observed.
- What AI has inferred.
- What has been verified.
- What changed over time.
- Which version of the source and processing logic produced the current knowledge.

Use Neo4j to represent and query relationships.

Use RAG to retrieve supporting document evidence.

Use Git to version application code and version-controlled definitions.

Use a durable history mechanism to preserve knowledge changes.

Use snapshots to identify meaningful knowledge states.

Use Spring AI to integrate AI capabilities without making the entire domain model dependent on one provider.

Use configurable skills and pipelines to support evolution.

Allow autonomous generation and modification of application code when existing capabilities are insufficient, but validate changes before activation.

Keep the architecture generic, the implementation practical, the retrieval grounded, the history traceable, and the update mechanisms recoverable.

Begin by inspecting the available environment and presenting the implementation plan. Then build the project incrementally, verifying each milestone with actual tests.
