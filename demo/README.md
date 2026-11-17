# demo/

The synthetic corpus used by every automated test (MASTER_SPEC §26). No Dukhan documents, code or data.

| Path | What | Used by |
|---|---|---|
| `brd/international-transfer-brd.md` / `.docx` | BRD with numbered requirements FR-1..FR-3 (FR-2 states a QAR 50,000 daily limit "the same as for local transfers") and an error-code table. The `.docx` is the M2 rendering without the limit sentence | `DocumentIngestionIT`, `ExtractHelpersTest`, `BrdIngestPipelineIT` |
| `brd/changes/international-transfer-brd-v2.md` | v1.1 of the BRD: adds FR-4 and changes the FR-2 daily limit from QAR 50,000 to 30,000 | new-version test, card UPDATE + INVALIDATE (`BrdIngestPipelineIT`) |
| `brd/changes/international-transfer-brd-v3.md` | v1.2: adds a "Limits summary" that contradicts FR-2 (QAR 50,000) | conflict question (`BrdIngestPipelineIT`) |
| `transfer-service/` | Small Spring service: controller, service, shared `CurrencyConverter`, external `FxRateClient`, JPA, Kafka, error codes, log statements, a test, an error-config SQL script | `RepositoryIngestionIT`, `JavaSourceExtractorTest` |
| `transfer-service-changes/c2/` | Commit 2: `validateCurrency` stops calling `CurrencyConverter.isSupported` and calls the new `CurrencyPolicy.isAllowed` | invalidation test |
| `transfer-service-changes/c3/` | Commit 3: `CurrencyConverter` renamed to `FxCurrencyConverter` (`DELETE` lists removed files) and `validateCurrency` renamed to `checkCurrency` with the same body | rename test |
| `transfer-service-changes/c4/` | Commit 4 (applied to c1 on its own): `execute` re-checks the currency through `validateCurrency`, and both `validate` and `execute` call the utility `TransferTextUtils.normalise` | `SHARES_LOGIC_WITH` with the utility excluded (`DiscoveryAndUpdatesIT`) |
| `reactive-service/` | M6.1: a small WebFlux service: method references (`this::x`, `field::x`), Mono operator chains with lambdas, a WebClient chain, an interface with one implementation (`TransferPort`) and one with two (`NotifierPort`), 7 log statements | `ReactiveCallResolutionTest`, `DiscoveryAndUpdatesIT` |
| `qa-automation/` | The international-transfer journey four ways: Postman collection, Java journey + TestNG test and suite, Gatling simulation, JMeter plan | `JourneyIngestionIT`, `JourneyAdaptersTest` |
| `routes/ocs_t_gateway_api_route.sql` | Gateway route export in the real export's format (one inactive row) | route resolution |

Tests copy the repositories to a temporary directory and turn them into real Git repositories (`DemoCorpus`).
