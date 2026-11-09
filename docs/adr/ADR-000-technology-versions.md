# ADR-000: Technology versions

Status: **accepted** (M0, 2026-11-05)

## Context

MASTER_SPEC §2.1 and §28 Step 3 require current, stable, mutually compatible versions, verified rather than
guessed. The October plan assumed Spring Boot 4.0.x. At M0 time, Maven Central shows Boot 4.1.2 as the newest
GA (4.2.0 is at RC2), and Spring AI 2.0.2 is GA.

## Decision

Use **Java 21, Spring Boot 4.1.2 and Spring AI 2.0.2**. Let the Boot BOM manage everything it covers, and pin
the rest in the parent `pom.xml` `<properties>`. Libraries needed only from M1 on are pinned now but are not
dependencies yet.

| Library | Version | Source | Why this version | Compatibility evidence |
|---|---|---|---|---|
| Java | 21 (21.0.11 used) | toolchain | LTS; matches the newer `dkn-*` services | `java -version` on the build machine |
| Spring Boot | 4.1.2 | parent `spring-boot-starter-parent` | Latest GA (4.2.0 is only RC2) | Maven Central `spring-boot` metadata |
| Spring AI | 2.0.2 | `spring-ai-bom` import | Latest GA; **built against Boot 4.1.2** | `spring-ai-autoconfigure-model-openai-2.0.2.pom` declares Boot starters at `4.1.2` |
| Neo4j Java driver | 6.1.0 | Boot BOM | Managed by Boot 4.1.2 | `spring-boot-dependencies-4.1.2.pom` → `neo4j-java-driver.version` |
| Spring Data Neo4j | via Boot BOM | Boot BOM | Only `Neo4jClient` is used (ADR-001) | Same BOM |
| Testcontainers | 2.0.5 (`testcontainers-neo4j`, `testcontainers-junit-jupiter`) | Boot BOM | Managed by Boot; 2.x artifact names | `spring-boot-dependencies-4.1.2.pom` → `testcontainers.version` |
| JUnit Jupiter | 6.0.3 | Boot BOM | Managed by Boot | Same BOM |
| Neo4j server (tests, compose) | `neo4j:2026.10.1-community` | `neo4j.test.image` property | Newest stable Docker tag; well above the 5.26 minimum for dynamic labels/types | Docker Hub tag list; `Neo4jDynamicLabelIT` passes against it |
| Neo4j server (Desktop) | Enterprise 2026.09.0 | local install | Already installed; supports `CREATE DATABASE` for a separate `pketest` DB | `~/.Neo4jDesktop2/Cache/dbmss` |
| ArchUnit (`archunit-junit5`) | 1.5.1 | explicit | Latest stable; supports Java 21 bytecode | Maven Central; `ArchitectureTest` runs |
| JavaParser (`javaparser-symbol-solver-core`) | 3.28.2 | explicit, unused until M2 | Latest stable | Maven Central metadata |
| Eclipse JGit | 7.8.1.202611041320-r | explicit, unused until M2 | Latest stable; Java 17+ | Maven Central metadata |
| Apache Tika (`tika-core`, `tika-parsers-standard-package`) | 4.2.0 | explicit, unused until M2 | Latest stable | Maven Central metadata |
| `com.networknt:json-schema-validator` | 3.0.9 | explicit, used from M1 | Latest stable; JSON Schema 2020-12 | Its POM depends on `tools.jackson.core:jackson-databind` **3.x** (3.1.8); Boot's BOM pins Jackson 3.1.7, which it runs on (all schema tests pass). Decision recorded below |
| swagger-parser v3 | 2.1.48 | explicit, unused until M2 | Latest stable | Maven Central metadata |
| JSqlParser | 5.4 | explicit, unused until M2 | Latest stable | Maven Central metadata |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | 3.1.7 | Boot BOM | Parses YAML schema definitions with Boot's Jackson 3 | Boot BOM |
| Maven | 3.9.11 (wrapper) | `.mvn/wrapper` | Matches the installed Maven | `mvn -v` |

## Spring AI model switches (verified in bytecode)

In 2.0.2 every OpenAI auto-configuration is `@ConditionalOnProperty(name="spring.ai.model.<kind>",
havingValue="openai", matchIfMissing=true)`. So the starter **turns models on** unless they are explicitly
disabled. `application.yml` therefore sets `spring.ai.model.{chat,embedding,image,moderation,audio.speech,
audio.transcription}=none`, and `application-openai.yml` sets chat and embedding back to `openai`.
`SystemInfoIT` proves that the application starts and reports no models when `OPENAI_API_KEY` is absent.

## Consequences

- Boot 4 uses Jackson 3 (`tools.jackson.*`). Libraries that bring Jackson 2 must be checked when they are
  added. **M1 result:** json-schema-validator 3.0.9 is already on Jackson 3 (`tools.jackson`), so it shares Boot's
  Jackson and needs no isolated Jackson 2 ObjectMapper or alternative validator. Boot pins the patch level
  (3.1.7 instead of the 3.1.8 the library declares); `SchemaDefinitionParserTest` and `SchemaRegistryIT` run on it.
  swagger-parser (M2) still needs checking.
- Moving to Boot 4.2 needs a Spring AI release built against it. Upgrade both together.
- MCP: Spring AI 2.0.2 provides `spring-ai-starter-mcp-server-webmvc` (checked on Maven Central). It is added in M6.
