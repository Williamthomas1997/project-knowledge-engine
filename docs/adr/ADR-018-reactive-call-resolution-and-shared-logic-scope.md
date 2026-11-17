# ADR-018: Reactive call resolution and the scope of SHARES_LOGIC_WITH

Status: **accepted** (M6.1, 2026-11-17). Hardening from the M6 real-repository smoke (`docs/reports/M6-REPORT.md`
§6). Amends ADR-016 (shared logic) and the Java extractor of M2.

## Decisions

### Shared logic
- **Default exclusions** (`pke.discovery.shared-logic.exclude`, `SharedLogicStep.DEFAULT_EXCLUDE`) now also exclude
  helpers, log-support wrappers and error-response plumbing: `helper`, `logsupport`, `[.]platform[.]error[.]`,
  `errorsupport`, `errorresponse`, `errorcontext` (case-insensitive, on the class name). `[.]` is used instead of
  `\.` so the regex survives Spring property placeholders. The `shared-logic-of-api` template (1.1.0) uses the same
  list.
- **Service APIs only.** `SHARES_LOGIC_WITH` is derived between `layer: service` ApiOperations. A gateway API reaches
  it through its `HANDLED_BY` route to the same handler; pairing gateway APIs repeated every pair up to 3 × 3 times.
- **Migration with history**: the derivation is one evidence scope, so re-deriving invalidates pairs that no longer
  qualify (versions and events kept) and adds nothing for unchanged pairs. The `shared-logic` pipeline re-derives
  without re-ingesting code; run it once after upgrading (`code-on-commit` does the same on the next commit).

### Java call resolution (no library classpath, nothing guessed)
The symbol solver only knows the JDK and the repository's sources, so most calls in Reactor/WebClient code fail to
resolve. In addition to the solver and the M2 declared-type fallback:
- **Method references** (`this::x`, `field::x`, `Type::x`, also inside operator chains) become `CALLS` with
  `resolution: method-reference` when the receiver is typed (this, a declared variable or field, a repository type)
  and the name matches exactly one method in the type or its repository superclasses. Overloaded names are listed as
  `ambiguous-method-reference`. JavaParser parses `field::x` with a type-expression receiver; a declared variable or
  field of that name takes precedence.
- **Lambdas**: calls in lambda bodies were already attributed to the enclosing method; explicitly typed lambda
  parameters (and catch parameters) now type their receivers. Implicit lambda parameters are never typed, even when a
  field has the same name (`untyped-lambda-parameter`).
- **`this.field.call()`** is typed by the field's declared type (injected fields).
- **Interfaces with a single implementation**: a call to an interface method also calls the only implementing class
  (not abstract) in the repository, when it declares the same signature (`resolution: single-implementation`). With
  several implementations the interface edge stays and the dispatch is recorded as `multiple-implementations`;
  implementations that inherit the method from a superclass are recorded as `inherited-implementation`.
- **Unresolved calls carry a reason**: `JavaMethod.unresolvedCalls` items are `<reason>: <call>` (at most 50 per
  method). Reasons: `external-library` (an operator of Reactor, WebClient, Stream or Optional on a receiver chain that
  cannot be typed), `unknown-receiver-chain`, `untyped-lambda-parameter`, `unknown-type`, `unknown-scope`,
  `ambiguous-overload`, `ambiguous-method-reference`, `no-matching-method`, `multiple-implementations`,
  `inherited-implementation`.
- `CALLS` 1.2.0 allows `resolution` ∈ {`resolved`, `method-reference`, `single-implementation`}. Solver and
  declared-type results stay `resolved`, so graphs without the new patterns do not change. One edge per target: the
  first resolution seen is kept.

## Consequences
- More `CALLS` edges and more reachable log statements in reactive services; `external-library` makes up most of the
  unresolved list and is expected (library calls, not missing repository calls).
- A second implementation added later turns `single-implementation` edges into a `multiple-implementations` record
  on the next extraction of the caller (the edge is retracted with its scope).
- Still not resolved: calls on values returned by repository methods (`service.load(id).getX()`), generic type
  parameters, Spring Data derived queries beyond the existing READS/WRITES heuristics, and dispatch through abstract
  classes. Adding the library classpath to the solver remains the larger, later step.
