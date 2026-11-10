# S149 - SDKs for Java, TypeScript and .NET, and the API compatibility policy

Closes [doc 29](../29-camunda-gap-implementation-plan.md) §9.14 **CG-K02** (Java with a Spring Boot starter, TypeScript,
.NET, generated from OpenAPI plus worker loops) and **CG-K05** (versioned API, two-release deprecation window,
changelog; design rule 9 in §4). Branch `claude/wave-e-sdks-c7579f`, base 52769a2b (Waves A to D).

| Part | Where | Tests |
|---|---|---|
| Code generator | `sdks/codegen/generate.py` | `--check` in CI |
| Java client, worker, callback tokens | `sdks/java/orqix-client` | 28 (JUnit 6) |
| Spring Boot starter, `@OrqixWorker` | `sdks/java/orqix-spring-boot-starter` | 9 (ApplicationContextRunner) |
| TypeScript client, worker, callback tokens, embed helper | `sdks/typescript` | 30 (`node:test`) |
| .NET client, worker, callback tokens | `sdks/dotnet` | 25 (xUnit) |
| Compatibility policy | [`docs/api-compatibility.md`](../api-compatibility.md) | — |
| Contract diff, baseline, changelog | `tools/contract-check/contract_diff.py`, `contracts/compat/openapi-v1-baseline.json`, [`docs/api-changelog.md`](../api-changelog.md) | 24 (`unittest`) + 2 checks in `contract_check.py` |

No backend change, no Flyway migration (V430-V439 unused), no OpenAPI change, compiler version not bumped. The Python
worker SDK (`tools/worker-sdk`) is unchanged; the new SDKs use its feature names ([`sdks/README.md`](../../sdks/README.md)
has the table).

## CG-K02 SDKs

### Scope: the machine surface

A bearer token reaches only the operations of `SecurityConfiguration.machineChain`; everything else needs a browser
session. The SDKs therefore type exactly those 12 bearer operations plus the 2 callback-token operations
(`pollCallbackWait`, `resumeCallbackWait`): start/read requests and their events, messages, the external-job lease and
callback protocol and callback tokens. `revokeCallbackToken` (DELETE) is not in the machine chain and is left out. The
TypeScript client also exposes `api`, the `openapi-fetch` client typed for all 456 operations, for hosts that call with
a session.

### Generated from the contract

`python sdks/codegen/generate.py` reads `contracts/openapi.yaml` and writes, for the 14 operations and the 44 schemas
they reach: Java records (`com.orqix.sdk.model`) and enum-constant classes, the Java `OrqixOperation` enum, the .NET
`Models.cs` and `OrqixOperations.cs`, and the TypeScript `generated/operations.ts`. TypeScript types are the
`openapi-typescript` 7.13 output, identical to the frontend's `schema.d.ts` (`npm run api:check`). CI fails when a
generated file is stale. Inline objects become records named after their parent (`RunWorkflow`); `allOf` of a schema
and an object is flattened (`RunDetail`); `oneOf` with `null` becomes a nullable field. Unsupported shapes stop the
generator instead of guessing.

Tolerant-reader rules built into the generated code (also in the policy): unknown response fields are ignored; enums are
strings with constants, so a value added later never fails deserialization; nulls are not sent. date-time fields are
`OffsetDateTime` / `DateTimeOffset` (instants compared in tests, never local time); numbers would be `BigDecimal` /
`decimal` (none in the machine surface today).

### Worker loops

Same behaviour as `orqix_worker.py`: lease extension at half the lease while a handler runs; idempotency keys
`<job>:<fence>:complete|fail|await-callback` and `<callback>:complete|fail`; `JOB_LEASE_LOST` / `JOB_NOT_OPEN` drop the
job; 401/403 stop the loop; an unexpected exception is a retryable failure naming only its class; business error codes
checked locally (`^[A-Z][A-Z0-9_]{1,39}$`, no `ORQIX_`); messages cut to 500 characters; topics sorted in the fetch.

### Spring Boot starter

`orqix-spring-boot-starter` for Spring Boot 4.0.8: `OrqixAutoConfiguration` (registered in
`AutoConfiguration.imports`), active once `orqix.base-url` is set. Beans: `OrqixTokenProvider` from `orqix.token-file`
(reread per request) unless the application defines one, `OrqixClient` (`@ConditionalOnMissingBean`), the
`OrqixWorkerMethods` bean post-processor that collects `@OrqixWorker(topic)` methods, and `OrqixWorkerContainer`, a
`SmartLifecycle` running one loop on its own thread (phase `MAX_VALUE - 1000`: starts last, stops first, waits up to
`orqix.worker.shutdown-timeout`). Signatures are checked at startup: no parameter, `Job` or the input `Map`; returns a
`Map` or `void`; a topic served twice fails the context.

### Embed helper (TypeScript)

`mountOrqixComponent(element, { component, workspaceId, workflowId?, onNavigate?, baseUrl? })` loads the component's
stylesheet once and its module, and calls `mount` with `apiVersion: 'v1'`. It validates the UUIDs before loading and
refuses a component origin other than the page's, because the components use the application session
([embedding](../hackathon-embedding.md)).

## CG-K05 API compatibility

- **Policy**: [`docs/api-compatibility.md`](../api-compatibility.md): `/api/v1` in the path; additive changes only
  within a version; tolerant-reader rules for clients; deprecation with `deprecated: true`, `x-orqix-deprecated-in`,
  `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) headers; a deprecated operation keeps working in its release and the
  two after it; a new major version runs next to the old one for at least two releases.
- **Baseline**: `contracts/compat/openapi-v1-baseline.json`, recorded at `r0-2026-11-08` (Waves A to D, 456
  operations). It stores the contract's surface, not a copy: per operation the parameters and flattened request and
  response fields (`items[].id`) with `R`/`O`, types and enums; problem responses once under `sharedResponses`. About
  1.1 MB, one field per line.
- **Check**: `contract_diff.py` fails on a removed operation (unless deprecated and two releases have been recorded
  since), a removed or no-longer-required response field, a narrowed response enum (value removed, or an enum placed on
  a free field), and a new required request field, parameter or body. It also lists additive changes. Window counting
  uses the baseline's release list, never the clock. `contract_check.py` runs it (plus the deprecation-header rule and
  the changelog freshness check), so the mandated contract check covers it; its unit tests run in the `contracts` job.
- **Changelog**: `docs/api-changelog.md` has a generated Unreleased section (`--changelog`); CI fails while it is stale.
  `--record <release>` records a new baseline and resets it.

## Decisions (doc 29 silent; conservative choices)

1. **Typed surface = machine surface** (above). Generating clients for 456 session operations would ship methods a
   machine identity is always refused.
2. **Own small generator instead of openapi-generator** for Java and .NET: the 14 operations reach 44 schemas, and
   openapi-generator's OpenAPI 3.1 support (type arrays, `oneOf` with `null`) is partial. The generator is about 600
   lines of Python with PyYAML, already a contract-check dependency. TypeScript uses `openapi-typescript`, as the
   frontend does.
3. **Dependencies**, all open source: Java: Jackson 3.1.5 (Apache-2.0), Spring Boot 4.0.8 (Apache-2.0), JUnit 6.0.3
   (EPL-2.0), AssertJ 3.27.7 (Apache-2.0, starter tests only). TypeScript: openapi-fetch 0.17 (MIT); dev: TypeScript 5.9,
   openapi-typescript 7.13 (MIT), @types/node (MIT). .NET: none at runtime; tests: xUnit 2.9.3 and its runner 2.8.2
   (Apache-2.0), Microsoft.NET.Test.Sdk 17.13 (MIT), Kestrel from the shared framework. Versions match the backend and
   frontend where they overlap.
4. **Two additions over the Python SDK**, the same in all three new SDKs: a final report that fails transiently (no
   answer, 429, 502, 503, 504) is repeated up to three times with the same idempotency key (safe by the protocol), and a
   result refused with 422 is reported as a retryable failure naming the problem code instead of waiting for the lease
   to expire. Python keeps its behaviour (the task keeps that SDK as is).
5. **No static token property** in the starter; `orqix.token-file` or an `OrqixTokenProvider` bean. No OAuth client
   integration (security work is out of scope for S149).
6. **Enums as strings** in generated models (tolerant reader) rather than language enums.
7. **Java records** for generated models; their constructors may gain components in a minor SDK release, documented in
   the policy and READMEs (request records are built through client helpers).
8. **Deprecation window in releases, not days**: two releases after the deprecating release, counted from the baseline's
   recorded release history, so the check never depends on the date.
9. **Headers are a contract rule now, server code later**: no operation is deprecated yet, so no backend change. The
   pull request that deprecates the first operation must make the server send `Deprecation`/`Sunset`/`Link`;
   `contract_check.py` already refuses a deprecated operation that does not declare them. The SDKs already report them.
10. **CI**: new job `sdks` (stage 1, independent of the backend): generator check, `mvnw verify` in `sdks/java`,
    `npm ci`/`api:check`/`build`/`test` in `sdks/typescript`, `dotnet test` with `actions/setup-dotnet@v6.0.0` (v6.1.0
    was six days old) and .NET `8.0.x`. The `contracts` job also runs the contract-diff unit tests.
11. **Packages are not published** (no registry decision yet); versions are 0.1.0. TypeScript `private: true`.

## Merge notes (coordinator)

- Any branch that changes `contracts/openapi.yaml` makes `docs/api-changelog.md` stale: after merging, run
  `python tools/contract-check/contract_diff.py --changelog` and commit. Additive changes pass; a breaking one is listed
  as `BREAKING` and fails.
- If a merged branch changes a schema the machine operations reach (`Run`, `RunDetail`, `RunEvent`, `ExternalJob*`,
  `Callback*`, `SendMessageRequest`, `MessageAccepted`, `StartRunRequest` and their parts), run
  `python sdks/codegen/generate.py` and, in `sdks/typescript`, `npm run api:generate`; then rerun the SDK tests.
- If a branch adds a route to `SecurityConfiguration.machineChain`, add its operationId to `MACHINE_OPERATIONS` in the
  generator and a client method in each SDK.
- Do not re-record the baseline when merging Wave E: it stays at `r0-2026-11-08` until the next release.

## Verification (this branch)

- `python sdks/codegen/generate.py --check`: current (48 files).
- `sdks/java`: `mvnw -B verify` (JDK 21.0.12): 37 tests, BUILD SUCCESS; surefire runs with time zone
  `Pacific/Kiritimati` and locale `tr`.
- `sdks/typescript`: `api:check` ok, `build` ok, 30 tests pass (Node 24).
- `sdks/dotnet`: `dotnet test Orqix.sln` (SDK 8.0.427): 25 tests pass, with `TZ=Pacific/Kiritimati`; warnings are
  errors.
- `python -m unittest discover -s tools/contract-check -p "test_*.py"`: 24 tests pass.
- `python tools/contract-check/contract_check.py`: PASS, 143 checks (141 before plus the two compatibility checks).
  Redocly lint 2.60.0: valid, 0 errors, 75 warnings (contract unchanged by S149).
- `python -m unittest discover -s tools/worker-sdk -p "test_*.py"`: 38 tests pass (Python SDK unchanged).
- Not run: the SDKs against a live Orqix stack (the scripted servers cover the protocol as documented in S64, S98 and
  S132); a browser test of the embed helper with the built components.
