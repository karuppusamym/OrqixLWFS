# S65 — Case termination, request-view reuse and service OAuth

18 October 2026. The user authorized completing WM04, UI02 and the remaining integration/agent engineering. This checkpoint delivers three independently testable increments. It does not reduce the full acceptance boundaries in S56 or mark the unfinished parent epics complete.

## Case termination (WM04c)

An operator with current `run.operate` permission can terminate an active case with its current version, an idempotency key and a nonblank reason. The command locks the case row, terminates the Flowable case through its public API, records `terminated` separately from `completed`, increments the case version and writes an audit event in one transaction. It cancels the engine's open tasks. Competing completion uses the same row lock; stale commands and late decisions fail. Identical retries replay the original response after fresh authorization; changed intent conflicts.

Case detail offers a confirmation dialog and requires a reason before enabling the final command. The reason is stored in an append-only `case_termination` row and excluded from ordinary case responses and audit metadata. There is no public reason-read endpoint in this increment. V84 must run before deployment.

This does not add full CMMN conformance, discretionary planning, case data updates, stages/listeners/repetition/nested-case execution, case suspension or a full case designer.

## Pinned request-view reuse (UI02b)

Studio's Start inspector can apply an explicitly selected published request-view version. Every workflow input needs exactly one matching `request.<field>` binding, the same required flag and a compatible field type. Labels, section order, one/two-column layout, field order, supported widgets and an optional single submit action label are copied into the draft. Schema constraints, conditions, connections and execution remain authoritative. Choice values must match the workflow enumeration in order; this increment requires option labels to equal their values. Row controls require existing object-row schema; the view does not define its columns.

`inputUi.viewRef` pins the workspace-local view UUID and version. Server validation and publication independently load that exact immutable version, reject missing/foreign pins and incompatible or diverged layouts, and warn when a newer version exists. A published release renders its own copied form and submit label without fetching the view library, so later view changes cannot alter it. Detaching removes the pin and keeps the copied form; ordinary undo is available. Reading a source view grants no workflow action permissions. A hidden or relabelled submit action still calls the existing authorized start API.

This is request-form reuse, not approval-view reuse, case/worklist pages, composed actions, localization, general component extensions or the complete UI02 designer. Views containing save-draft/navigation or approval actions cannot bind through this request-form increment.

## OAuth client credentials (IN03a)

Connection bindings accept `oauth2ClientCredentials`, with a mounted secret reference and no header name. The settings page exposes the option; secret values stay outside API bodies. Provision a secret file containing the following JSON through the existing platform secret mechanism:

```json
{
  "tokenUrl": "https://identity.example/oauth2/token",
  "clientId": "approved-service-client",
  "clientSecret": "provision-through-secret-management",
  "scope": "approved.scope"
}
```

`scope` is optional. All other fields are required; unknown fields are rejected. The token endpoint must independently pass the existing HTTPS/host/DNS egress policy on every credential use, including cached tokens. OAuth client authentication uses form-encoded Basic credentials and `grant_type=client_credentials`, following [RFC 6749 §§2.3.1 and 4.4](https://www.rfc-editor.org/rfc/rfc6749.html). Redirects are refused; requests and responses are bounded. Only a bearer token with a positive integer lifetime of at most one day is accepted. Token values and provider responses are never logged or returned by connection management.

Tokens live only in process memory, partitioned by workspace/secret reference and credential-content hash. Concurrent calls for one credential share acquisition. Expiry causes renewal, secret rotation takes effect on the next use, and HTTP/Graph 401 responses invalidate the cached token without automatically resending the operation. Each process obtains its own tokens; there is no distributed credential database. HTTP, Graph and read-only MCP connection paths consume the resolved bearer credential. MCP rejection does not transparently resend a call; normal operator recovery and token expiry apply. V83 must run before deployment. Real provider consent/configuration and tenant acceptance remain external.

This does not implement delegated authorization-code consent, refresh-token persistence/rotation, asynchronous provider callbacks, extra model/A2A protocols or write-capable agent tools. MCP tools retain their existing read-only admission rules.

## Verification

| Check | Observed result |
|---|---|
| Compiler full baseline | 495/495 pass; coverage gate passes |
| Application full baseline | 803 cases: 797 pass, four intentional skips, two test-fixture failures; `evidence/engineering-full-backend.log` |
| Corrected-fixture/API verification | 50/50 pass: all 42 banking scenarios, both graceful-shutdown scenarios and six connection API tests, including the added OAuth binding case; final Maven `verify`, coverage check and executable packaging pass; `evidence/engineering-backend-corrections.log` |
| Combined application coverage | 804 unique cases: 800 pass and four intentional skips, using the full baseline plus focused corrections; this is not a fresh full-suite run after the fixture changes |
| Frontend full suite | 882/882 tests across 115 files; `evidence/engineering-full-frontend.log` |
| Static checks and build | Frontend/E2E TypeScript, frontend lint and production build pass; `evidence/engineering-frontend-types.log`, `engineering-e2e-types-final.log`, `engineering-frontend-lint.log`, `engineering-production-build.log` |
| Contracts | 88/88 pass, including generated API types and LF checks; `evidence/engineering-contracts-final.log` |
| Real Edge with mock API | Both journeys pass: Studio view selection through request submission (including mobile review), and confirmed case termination with reload. Serious/critical axe and browser-error checks pass; `evidence/engineering-browser-isolated.log` |

The full backend run exposed two existing test-fixture races. Graceful shutdown used a 10-second accelerated recovery lease around an eight-second delayed call; the drain fixture now uses and asserts the production 60-second lease, retaining the original no-duplicate/no-failed-attempt assertions. Other lease-expiry recovery fixtures retain their accelerated timings. The banking timer helper previously checked for a target task before enumerating timers, allowing it to fast-forward that newly created task's own deadline. It now snapshots timers first and advances only that snapshot when the target is absent. Production runtime behavior is unchanged by these two fixture corrections.

The initial focused run passed 44 backend tests; the final full-baseline-plus-corrections evidence above supersedes it. Raw logs remain in the ignored local evidence directory. The browser harness now honors an explicit mock-server port, so a retiring shared preview server cannot interrupt an isolated rerun.

WM04c, UI02b and IN03a are closed within the exact local delivery scopes above. Parent WM04, UI02 and IN03 remain open; IN04 write-capable agent tools remain planned. No Docker image refresh, real-stack browser acceptance, production rollout, enterprise identity/provider acceptance or full CMMN/designer conformance is claimed. Deployment must apply V83 and V84 before starting the new application image.
