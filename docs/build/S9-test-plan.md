# S9 — Test plan and evidence
Build specification · revision 1.0 · 7 October 2026

Every R0 acceptance claim must point to a test in this catalog and its CI evidence. Mocks may drive failure cases but never mark a feature Verified (doc 11).

## 1. Levels and tools

| Level | Tooling | Runs | Covers |
|---|---|---|---|
| Contract | JSON Schema validation of schemas, examples and fixtures; Redocly lint; generated-type drift check | Every PR | T-COMP-06, T-API-01 setup |
| Unit | JUnit Jupiter + AssertJ (backend), Vitest + Testing Library (frontend) | Every PR | Compiler rules, evaluator, classification table, policy, form renderer, studio store |
| Golden files | Byte comparison of generated BPMN/DMN/source map | Every PR | T-COMP-03 |
| Integration | Spring Boot tests + Testcontainers PostgreSQL (same major as target) + embedded Flowable + WireMock; responses validated against `openapi.yaml` | Every PR | T-API, T-RUN, T-TASK, T-EFF, T-SEC |
| Architecture | Spring Modulith `verify()`, ArchUnit | Every PR | Module and engine-access rules |
| E2E | Playwright against Compose (built image, Keycloak, stub) + axe | Every PR to main | T-UI |
| Resilience | Scripted multi-instance runs with process kill and DB stop | Nightly + pre-release | T-RES |
| Performance | Gatling (JVM, no extra runtime) | Pre-release smoke; full pilot load at GA | T-PERF |

## 2. Shared fixtures

| Fixture | Used by |
|---|---|
| `examples/purchase-approval.json` | Template, compiler, engine, E2E |
| `examples/connections/procurement.connection.json` | Connection registration, compiler binding checks, stub contract |
| `examples/fixtures/compiler-cases.json` | T-COMP-01 (backend loads the file directly; no copies) |
| `examples/fixtures/purchase-scenarios.json` | T-RUN-01, T-RUN-03, T-COMP-04, T-UI-01 |
| Dev realm + dev seed (S1 §6.1) | T-SEC matrix principals, E2E users |

## 3. Test catalog

### Database and contracts
| ID | Test | Ticket |
|---|---|---|
| T-DB-01 | `migrate` profile applies V1/V2 to an empty database; `orqix_app` cannot UPDATE/DELETE append-only tables or touch `flyway_schema_history` | B01.4 |
| T-DB-02 | Composite FKs reject cross-workspace links (draft, release, run, task, effect, event, notification) | B01.4 |
| T-COMP-06 | Both JSON Schemas are valid 2020-12; examples validate; every fixture patch applies | B01.5 |
| T-API-01 | Every integration-test response validates against `openapi.yaml` (status, headers, body) | B02.2 |

### Compiler
| ID | Test | Ticket |
|---|---|---|
| T-COMP-01 | Each `compiler-cases.json` case yields its expected blocker/warning codes; `valid-*` cases yield no blockers | B03.2 |
| T-COMP-02 | Issue lists are deterministic (order and content) across runs | B03.2 |
| T-COMP-03 | Purchase template compiles to byte-identical artifacts matching golden files; changing only `uiLayout` keeps `semanticHash` | B03.3 |
| T-COMP-04 | Canonical evaluator and compiled DMN agree on all `decisionBoundaries` | B05.1 |
| T-COMP-05 | `orqixRoute.condition` matches the evaluator for eq/gte/lt on number, string, boolean, including `5000` vs `5000.00` | B05.1 |

### API behavior
| ID | Test | Ticket |
|---|---|---|
| T-API-02 | Draft `If-Match` race: one 200, one 412 with `currentRevision`; missing `If-Match` → 428 | B03.4 |
| T-API-03 | Publish replay returns 200 + `Idempotent-Replayed` and creates no second deployment; blocker draft → 422 and no deployment | B03.5 |
| T-API-04 | Start: same key + body → one run (sequential and concurrent threads); different body → 409 | B03.8 |
| T-API-05 | Tampered or filter-mismatched cursor → `400 INVALID_CURSOR` | B02.2 |

### Runs and tasks
| ID | Test | Ticket |
|---|---|---|
| T-RUN-01 | `inputValidation` fixtures: valid inputs start; invalid return 422 with the listed `fieldErrors` pointers and create nothing | B03.8 |
| T-RUN-02 | Event sequence per run is gap-free and ordered; a poller with `after` never misses a late-committing event (concurrent writer test) | B03.8 |
| T-RUN-03 | Every `scenarios` entry reaches the expected lifecycle, outcome, visited nodes, procurement call count and outputs | B04.3, B05.3 |
| T-RUN-04 | Simple merge: `notifyRejected` executes once whether the manager or finance rejected | B05.8 |
| T-RUN-05 | Exactly one in-app notification per outcome, also when the job retries | B05.8 |
| T-RUN-06 | Run started on release 1 completes on release 1 after release 2 is published and activated | B03.5 |
| T-RUN-07 | `setActiveRelease` to release 1 (rollback) makes new starts use it; running instances unchanged | B03.5 |
| T-RUN-08 | Starting beyond `maxConcurrentRuns` → `429 QUOTA_EXCEEDED` | B03.8 |
| T-TASK-01 | Claimable/assigned/completed lists match eligibility (group, `task.act`, separation of duties) for every seeded user | B04.1 |
| T-TASK-02 | Concurrent claims: one success, one `409 TASK_ALREADY_CLAIMED` | B04.2 |
| T-TASK-03 | Concurrent decisions: one `task_decision` row, one engine continuation, one audit decision; loser 409 | B04.3 |
| T-TASK-04 | Decision replay after success → 200 replay; after the approver's binding is removed → refused (not replayed) | B04.3 |

### External effects
| ID | Stub fault / setup | Expected | Ticket |
|---|---|---|---|
| T-EFF-01 | none | One order, result stored, Fulfilled | B05.3 |
| T-EFF-02 | `status500 × 2` | 3 attempts, 1 order | B05.3 |
| T-EFF-03 | `commitThenTimeout × 1`, `idempotentWithKey` | Retry with same key returns original order; 1 order | B05.3 |
| T-EFF-04 | `commitThenTimeout × 1`, `nonIdempotent` copy of the operation | `needsAttention` / `EFFECT_UNKNOWN`; no automatic retry; retry API → 409 | B05.4 |
| T-EFF-05 | `status400` | `EFFECT_REJECTED` after one attempt | B05.4 |
| T-EFF-06 | After T-EFF-04: reconcile `confirmedApplied` with `procurementId` | Run completes; stub receives no further call | B05.5 |
| T-EFF-07 | After T-EFF-04: reconcile `confirmedNotApplied`, then retry | One new order; run completes | B05.5 |
| T-EFF-08 | Binding disabled between approval and dispatch | `CONNECTION_UNAVAILABLE`; re-enable + retry completes | B05.4 |
| T-EFF-09 | 201 with body missing `id` | `idempotentWithKey` → `EFFECT_UNKNOWN` (never shown as plain failure) | B05.3 |
| T-EFF-10 | `status429 × 1` on `nonIdempotent` | Retried automatically; 1 order | B05.3 |

### Security (details in S6 §9)
| ID | Test | Ticket |
|---|---|---|
| T-SEC-01 | Generated matrix: every operation × every seeded principal, including foreign-workspace IDs; expected status from `x-orqix-permission`; foreign resources always 404 | B02.5 |
| T-SEC-02 | Idempotent replay after permission revocation is refused | B04.3 |
| T-SEC-03 | Requester cannot see, claim or decide own task; allowed only in a test workspace with `selfApproval: allow`, flagged in the decision | B04.1, B04.3 |
| T-SEC-04 | Payloads with `actor`, `assignee`, `workspaceId`, `initiator` or unknown fields are rejected | B02.2 |
| T-SEC-05 | Unsafe browser requests without a valid CSRF token → 403 | B02.1 |
| T-SEC-06 | Literal/label injection fixtures blocked by the compiler; generated XML contains only allowed expressions; a non-allowlisted bean name in an expression fails at engine level | B03.3 |
| T-SEC-07 | Bindings to loopback, link-local/metadata, private ranges, userinfo URLs, non-allowlisted hosts, and DNS names resolving to private addresses are rejected; redirects are not followed | B05.2 |
| T-SEC-08 | Secret value and `Authorization` header never appear in API responses, problems, export bundles or captured logs | B08.4 |
| T-SEC-09 | Permission removal reaches a second instance within 60 s | B02.2 |
| T-SEC-10 | Bearer token can call only `startRun`, `getRun`, `listRunEvents` | B02.6 |
| T-SEC-11 | Every denied command leaves database and engine state unchanged (snapshot comparison) | B02.5 |
| T-SEC-12 | Script/HTML in labels, reasons and comments renders as text; no CSP violations | B08.6 |

### Resilience
| ID | Procedure | Pass | Ticket |
|---|---|---|---|
| T-RES-01 | Start a run, stop the app (SIGTERM), start it again | Task and inbox entry intact; decision completes on the restarted app | B08.3 |
| T-RES-02 | Two app instances, 50 runs approved concurrently | Each run has exactly one effect and one completion; jobs split across instances | B08.3 |
| T-RES-03 | Stub `delay` 25 s; `kill -9` the instance executing the job | Other instance resumes within lock time + reset interval (≤ 120 s); idempotent → one order | B08.3 |
| T-RES-04 | Stop PostgreSQL for 60 s during traffic | Commands return 503 with no false success; liveness stays up; readiness drops and recovers; pending jobs complete after recovery | B08.3 |
| T-RES-05 | SIGTERM during a running job | Job finishes or is safely re-run by the other instance; no duplicate effect | B08.3 |
| T-RES-06 | Restore a backup to an isolated database | Pending tasks and releases intact; elapsed time recorded; effect journal reconciled before jobs resume | B08.5 |

### User interface
| ID | Test | Ticket |
|---|---|---|
| T-UI-01 | Form renderer reproduces `inputValidation` outcomes client-side | B06.2 |
| T-UI-02 | Doc 06 demo script (all steps that do not need OpenShift) through the browser | B08.6 |
| T-UI-03 | Second manager sees "This task was completed by …" without a page reload error | B06.3 |
| T-UI-04 | Keyboard-only: change the finance threshold through the Steps tab and inspector, save, reload, undo | B07.4 |
| T-UI-05 | axe on every route: no serious or critical findings | B08.6 |
| T-UI-06 | No CSP violations or uncaught errors in the browser console during E2E | B08.6 |

### Operations and performance
| ID | Test | Ticket |
|---|---|---|
| T-OPS-01 | Image runs with a random UID, read-only root filesystem, writable `/tmp`; no root-owned writes | B08.1 |
| T-OPS-02 | Readiness false while DB down, liveness true; graceful shutdown completes within the grace period | B08.1 |
| T-PERF-01 | R0 smoke: 20 virtual users start and approve for 10 minutes; p95 list/task API < 1 s; start p95 < 2 s; no errors | B08.3 |
| T-PERF-02 | GA: 50 concurrent users with 10,000 stored runs (doc 01 targets) | GA gate |

## 4. Coverage map to requirements

| Requirement | Tests |
|---|---|
| FR01 sign-in and membership | T-SEC-01, T-SEC-05, T-SEC-09 |
| FR02 drafts | T-API-02, T-UI-04 |
| FR03 canvas + keyboard alternative | T-UI-04, T-UI-05 |
| FR04 forms | T-RUN-01, T-UI-01 |
| FR05 human tasks | T-TASK-01…04, T-SEC-03 |
| FR06 rules | T-COMP-04, T-COMP-05, T-RUN-03 |
| FR07 REST connector | T-EFF-01…10, T-SEC-07, T-SEC-08 |
| FR08 publish | T-COMP-01…03, T-API-03 |
| FR09 start + idempotency | T-API-04, T-RUN-08 |
| FR10 run detail + persistence | T-RUN-02, T-RES-01, T-UI-02 |
| FR11 versioning | T-RUN-06, T-RUN-07 |
| FR12 notifications | T-RUN-05 |
| FR13 export | T-SEC-08 (bundle), export golden test in T-COMP-03 |
| FR14 audit | Audit assertions inside T-TASK-03, T-API-03, T-EFF-06, T-SEC-01 |

## 5. Evidence pack (B09.4)

`evidence/R0-<date>/` (attached to the release, not committed if it contains environment data):

- Image digest, source commit, ADR 0001 dependency versions, `compilerVersion`.
- CI reports for every stage; Playwright report with trace for failures; axe output.
- Resilience run logs with timings (lock recovery time, attempts, order counts).
- Demo script checklist with pass/fail per step and what was real vs stub (the procurement endpoint and identity provider must be named explicitly).
- `docs/feature-status.md` snapshot and the open-gate list (doc 06 production gates with Pass / Fail / Not run).
