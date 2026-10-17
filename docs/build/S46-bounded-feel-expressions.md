# S46 - Broader bounded FEEL/JUEL gateway conditions (EX07)

EX07 widens the Camunda importer's condition reader (`Expressions`) while keeping its rule: a construct is either converted to an exact canonical `all`/`any` group the compiler accepts, or reported as an `EXPRESSION_NOT_SUPPORTED` blocker with the original text and a reason. Nothing is evaluated or approximated.

## What converts now

| Source form | Canonical result |
| --- | --- |
| `= 5000 <= amount` (literal on the left, JUEL or FEEL) | `amount >= 5000` (operator turned around) |
| `= amount between 1000 and 5000` (FEEL only) | `all`: `>= 1000`, `<= 5000` |
| `= amount in [1000..5000)`, `(a..b]`, `]a..b[` (FEEL only, numbers) | `all` with open or closed bounds as bracketed; a reversed range is refused |
| `= region in ("EU", "UK")` (FEEL only) | `any` of equalities; one value becomes a plain comparison; at most 8 values |
| `not(<group>)` / `!(<group>)` | De Morgan: connective and every operator inverted (`not(a>1 and b<2)` -> `any` of `a<=1`, `b>=2`) |
| `not(x between ...)`, `not(x in (...))` | `any` of outside bounds / `all` of `ne` terms |
| `(a and b) and (c and d)` | flattened into one `all` group |

An `and` that belongs to `between` is not taken as a group connective. Type consistency per variable, the safe text character set and the 8-term group limit still apply after flattening.

## Still reported, never converted

Mixed `and`/`or`, a group of a different connective nested in a group (including `x in (...)` or `between` inside an `or`/`and` of the other kind), `in`/`between` in JUEL, `in` lists with non-literals or empty/trailing-comma lists, unary tests such as `in (>5)`, functions, property access, arithmetic, `if`/`some`/`for`, list indexing, null checks, comparing two variables, other script languages and external script resources. Each yields a BLOCKER on the gateway and on the flow containing the source condition text and the reason; the conversion produces no definition.

One reporting gap was closed: the INFO item `CONDITION_DROPPED` (a condition on a flow leaving a user task, replaced by the approval outcome mapping) did not show the source text. It now quotes it, so a dropped condition is visible in the report. It is still not interpreted.

## Evidence - 17 October 2026

Run with JDK 21: `backend/mvnw.cmd -f backend/pom.xml -pl app -am -Dorqix.buildDirectory=target/ex07 -Dtest='com.orqix.migration.**.*Test,com.orqix.migration.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`. All 234 migration-package tests pass (BUILD SUCCESS), including PostgreSQL/Flowable `MigrationApiTest`, `Camunda8MigrationApiTest`, `MigrationProjectApiTest` and `MigrationGovernanceApiTest`. A focused rerun of `ExpressionsTest` (70), `ConverterExpansionTest` (18), `FixtureDispositionTest` (20), `Camunda8ImportTest` (25), `DmnImportTest` (52) = 185 passes; log `evidence/ex07-backend.log` (evidence logs are git-ignored).

New cases: `ExpressionsTest` (left literal, between/ranges and bounds, `and` of `between` in a group, `in` lists, De Morgan, flattening, JUEL refusal, about 20 new refusal rows); `ConverterExpansionTest` converts the new fixture `examples/migration/expression-feel-extended.bpmn`, checks the exact canonical JSON, compiles it with the real compiler with no blockers, and executes the converted graph at every boundary (10000.01, 10000, 1000.01, 1000, UK listed, US outside, 79.99/80); a second case runs 14 unsupported constructs and asserts each is a BLOCKER with its text, the report is unpublishable and no definition is produced; a third checks the user-task exit report quotes the source text.

Existing tests that asserted the old limits were changed deliberately: `not(a && b)` and `in (...)` are no longer in the unsupported lists (replaced by still-unsupported forms), and the `camunda8-unsupported.bpmn` fixture `gIn` condition now adds `and other = 1` so it remains an unsupported nested group.

## Not covered

DMN input-entry FEEL (`DmnEntries`) is unchanged. Frontend and browser suites, the full backend suite, the real Docker stack and an image refresh were not run; only importer behavior changed and the API shape is unchanged. No customer-source equivalence is inferred. Remaining FEEL (functions, arithmetic, date/time, contexts, lists, null semantics) stays out of scope and reported.
