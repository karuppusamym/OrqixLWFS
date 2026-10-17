# S32: Known HTTP failure routes

Implemented scope, 15 October 2026. Combined verification and deployment status are recorded in the product build tracker.

## Supported execution

A `http` step can have its usual `next` transition and one optional `failed` transition. The compiler emits an interrupting BPMN boundary with error code `ORQIX_STEP_FAILED`. Runtime follows that boundary only after the durable effect journal confirms an eligible permanent result for the exact workspace, run, node and occurrence:

- A definitive HTTP 4xx rejection, excluding 401/403 authentication or authorization responses and 408/429 retry cases. The result must carry the matching `HTTP_<status>` error code. Local request validation failures are not external rejections.
- An unusable response from a declared read-only HTTP operation, with `RESPONSE_INVALID`, `RESPONSE_TOO_LARGE` or `UNEXPECTED_STATUS`. This includes response-schema/mapping failure and an unexpected HTTP response. Mutating calls with unusable responses remain uncertain effects and require reconciliation.

The step records the failure, clears its active-step projection, writes the three failure facts below and throws the generated business error. Flowable catches it at the boundary in the current engine transaction; a handled rejection does not become a failed-job retry or dead-letter incident. Bodyless GET operations may have an empty input mapping, and calls whose results are unused may have an empty result mapping. The pinned operation contract still requires every mandatory request field. Normal success continues through `next`. Without an authored `failed` route, existing incident and operator recovery behavior remains.

| Field | Value |
| --- | --- |
| `failureKind` | `rejected` or `invalidResponse` |
| `failureHttpStatus` | Actual HTTP status, or 0 when no HTTP status exists |
| `failureMessage` | Fixed, redacted explanation; no response body, credential or arbitrary provider error text |

These fields are reserved result names. The compiler rejects collisions and prevents using failure facts on a success path or successful result fields on a failure path. Duplicate failed routes and unsupported node kinds are blockers. A failed route that leads to the same target as success produces an explicit warning. A workflow may handle the failure and continue or finish with a named business outcome; V29 aligns the database with the existing `failed` outcome kind. A completed run with a failed business outcome is distinct from a run awaiting technical recovery.

## Crash recovery and visibility

Effect journaling commits independently of the engine transaction. A job can therefore stop after a rejection was recorded but before its boundary continuation committed. Before dispatch, runtime checks for the same known permanent result, including the pinned connection/version/operation and exact canonical request hash. It reuses that result to complete the failure route without resending the call. Other permanent failures retain their existing recovery behavior.

Handled results write `EFFECT_FAILED` with `routed: true` and `STEP_FAILED` with the failure kind and effect ID. Their transaction commits together with the engine continuation. Run diagrams use the explicit failed outcome to distinguish `failed` and `next`, including when both transitions share a target. Request-data visibility continues to govern the stored step facts and outputs.

## Explicit limits

- Unknown outcomes never take this route. The operator must reconcile whether the remote action happened before retrying.
- Binding, credential, egress-policy and internal failures remain incidents.
- Exhausted retries remain incidents. `retriesExhausted` is not an implemented `failureKind`; authored conditions using that value are rejected. Reliable final-attempt routing needs a separate durable retry protocol.
- Child workflow failed routes are compiler blockers, including single calls and repeated/collection children. A child with a rejected business outcome is ordinary child completion; a child needing attention remains recoverable rather than being silently classified as a business failure.
- AI/MCP and Microsoft document adapter errors retain their own incident behavior; this slice does not claim arbitrary agent or connector error parity.

The `compensates` annotation describes an explicitly authored call intended to undo an earlier effect. The compiler checks that the referenced step exists, is another non-read-only HTTP call, and always precedes the annotated call. The annotation does **not** register an automatic undo handler, reverse an earlier effect, guarantee a successful rollback, or implement general BPMN compensation events. Designers still author and approve the actual undo operation and mappings.

## Simulation and tests

Simulation follows an authored failure route for a sampled rejection and for unusable read-only responses. It writes the same three field names and fixed explanations. A sampled rejection uses HTTP 400; an unusable simulated response uses status 0 because the scenario provides no actual HTTP response. Unknown mutating outcomes and unavailable/exhausted scenarios stop for attention. Simulation does not prove remote execution or journal durability.

`FailureRoutesCompilerTest` covers boundary/source-map generation, data availability, reserved outputs, unsupported children, compensation validation and the public simulator behavior. `FailureRoutesRuntimeTest` publishes real workflows and checks handled HTTP rejection, `failed` business outcome storage, read-only invalid responses, unknown mutating responses, disabled bindings, exhausted retries, ordinary success and the journal-before-engine crash window with zero additional remote calls. Root performs Maven verification of these tests with the combined feature set before reporting completion.

## Studio authoring gap closed - 16 October 2026

UI04 completes authoring of the existing HTTP failure boundary. The Steps editor offers independent Next and optional When failed destinations. The add-after dialog can insert a step on either path, preserving the original destination. Canvas system-call cards have separate Next and Failed handles, and saved failure edges attach to the Failed handle with the existing negative route color. Duplicate Next/Failed routes and unsupported route kinds remain blocked by editor checks; the normal compiler remains authoritative for connector eligibility and runtime semantics.

Renaming a system-call step also rewrites any explicit `compensates` reference to that step. The annotation remains an authored relationship and does not introduce automatic compensation.

Verification: 52 focused frontend tests pass, including saved-route reload, success/failure independence, duplicate refusal, insertion and compensation-reference rename. Frontend typecheck, scoped ESLint, E2E typecheck and the rebuilt mock bundle pass. One Edge browser journey verifies saved reload, the edge's actual geometric attachment, visible route styling, step insertion and axe with no serious/critical violations. Logs: `evidence/http-failure-authoring-tests-final.log`, `evidence/http-failure-authoring-browser-final.log`, `evidence/http-failure-authoring-typecheck.log`, `evidence/http-failure-authoring-lint.log`, `evidence/http-failure-authoring-markup-lint-final.log`, `evidence/http-failure-authoring-e2e-types-final.log` and `evidence/http-failure-authoring-build-final.log`.

This follow-up changes Studio authoring only. Browser validation uses the mock API; prior backend runtime evidence above retains its original scope. No fresh full backend/frontend suite or Docker rollout is claimed for this UI correction.
