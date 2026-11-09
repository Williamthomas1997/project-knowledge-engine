# ADR-012: API identity, user journeys and route resolution

Status: **accepted** (M2, 2026-11-09). Implements ADR-007 §6 and ARCHITECT-HANDOFF items 8–10.

## API identity: two layers

`ApiOperation` 2.0.0 has `layer`:

| Layer | Meaning | Natural key | Produced by |
|---|---|---|---|
| `gateway` | The API as a client calls it through the gateway | `httpMethod`, `pathTemplate`, `gatewayKey` (`service` = `gateway`) | journey adapters |
| `service` | A controller mapping inside a service | `service`, `httpMethod`, `pathTemplate` (`gatewayKey` = "") | Java extractor |

- `gatewayKey` joins the **identity headers** `moduleId|subModuleId|serviceId|screenId` (configurable,
  `pke.ingestion.identity-headers`). `unit` and `channel` differ per client for the same API in the real QA
  data (MB, WEB, placeholder `ab`), so they are kept as observed lists (`units`, `channels`), not identity.
  This narrows handoff item 9 ("gateway service headers"); see the M2 report.
- The full definition is stored as structure only: other header **names**, path and query parameter names, and
  request/response field trees (names and types, JSON). Values never leave the source: tokens, sample bodies
  and environment values are dropped by the adapters.
- Observations of the same gateway API from several sources are merged (union of lists, richer field tree).
  Journey ingestion starts from the API's stored properties, so repositories describing the same API converge
  instead of overwriting each other (verified on the real repositories).
- Paths are normalised: `{{var}}`, `:var`, `{var:regex}` all become `{var}`; no trailing or double slashes.

## Journeys

`UserJourney {journeyKey} -[:HAS_STEP {order}]-> JourneyStep {journeyKey, order} -[:CALLS_API]-> ApiOperation`,
`JourneyStep -[:FEEDS {field, variable}]-> JourneyStep`, `TestSuite -[:COVERS]-> UserJourney`.

- The journey key (kebab-case) makes different sources describe **the same journey**: Postman collection name,
  JMeter test plan name, Java class name without `Journey`/`E2ETest`/`Simulation`. Step names per source are
  evidence excerpts, so sources never overwrite each other's labels.
- Adapters (`JourneySourceAdapter` SPI in `pke-spi`): **Postman** (requests depth-first; `pm.*.set` + later
  `{{x}}` → FEEDS), **Java journeys** (public methods ordered by the test that drives them, traced statically
  through clients to `get/post/...(path)` with constants and `Map.of` header enums), **Gatling** (`http(...)`
  chains in source order, `saveAs` + `#{x}`), **JMeter** (transaction controller = step, extractors + `${x}`),
  **TestNG suites** (suite XML → classes → journeys they drive). Anything depending on runtime values
  (polymorphic header/path selection) is reported as unresolved. The prompt-interview adapter comes in M6.
- Java journeys produce no FEEDS (data passes through fields at runtime); Postman, Gatling and JMeter do.

## Routes: a resolver, not graph structure

`RouteResolver` produces `ApiOperation(gateway) -[:HANDLED_BY]-> JavaMethod` with evidence citing the route
row (or the controller), and no route nodes:

1. **Gateway route export** (`pke.ingestion.route-export-files`, the `ocs_t_gateway_api_route` INSERT scripts
   from `dkn-db-config-master`): an active row with the same method and URI; target service = host of
   `target_url` without `-svc`; forwarded path = `replace_uri` or the URI; handler = the unique service API of
   that service with that path.
2. **Spring mapping** fallback: the unique service API with the same method and path in any ingested service.

Each resolver scope is retracted and rewritten as a whole on every run, so routes that disappear are
invalidated with history. Gateway APIs whose target service has not been ingested are reported, not guessed.
The repository's `serviceName` must match the route's service name (e.g. `international-transfer-service`).
