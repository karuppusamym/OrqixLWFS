# Feature implementation status (R0)
Updated 9 October 2026 · deliverable 18 · status vocabulary from [doc 09](09-master-plan.md#definition-of-a-build-ticket)

**Verified** = automated tests pass against real PostgreSQL/Flowable (and, where stated, the Docker stack with real Keycloak and the procurement stub). **Implemented** = code exists and works in manual checks, but its S9 tests are not automated yet. Nothing is marked Verified on mocks alone.

## Evidence at this revision

The earlier broad verification was run on 9 October 2026 against a freshly rebuilt Docker image and clean database. The subsequent [release code audit](18-release-code-audit.md) reran compiler/application, frontend and browser checks while preserving existing local data; other rows retain the earlier evidence.

| Suite | Result |
|---|---|
| Compiler (`backend/compiler`) | 263 tests pass; JaCoCo gate passes |
| Application (`backend/app`, Testcontainers PostgreSQL 18, WireMock) | 180 tests run, 1 skipped (opt-in live AI test), no failures; API responses validated against `contracts/openapi.yaml` |
| Frontend (`frontend`, Vitest) | 421 tests pass, including contrast checks of both palettes and a no-hard-coded-color scan; typecheck, lint, build and API-type drift check pass |
| Browser E2E, Docker stack (`tests/e2e`, Playwright, Edge) | 25 checks pass across the final runs: 24 in the broad run plus the corrected Procurement connection check; 3 mock-only checks skipped. All five accessibility checks pass; no serious or critical axe findings or CSP violations |
| Browser E2E, mock mode | Final selected checks: 7 pass (both AI flows and five theme/canvas/mobile checks). Earlier broad evidence: 22 pass, 2 migration checks skipped |
| API E2E, Docker stack (`tools/api-e2e`) | Scenarios 1–9 with fault injection: 26 / 26. Scenarios 10–14 (rollback, reconcile, cancel, forged fields): 33 / 33 |
| Resilience, Docker stack (`tools/resilience`) | T-RES-01, T-RES-03 (kill -9 recovered in 73–76 s), T-RES-04 (60 s database outage), T-RES-05 (SIGTERM mid-call) and T-SEC-09 all pass. Evidence in `evidence/resilience/` |
| Performance smoke (`tools/perf`, T-PERF-01) | 20 users for 10 minutes: 43,167 requests, 0 errors. p95 is 26 ms for list and task APIs and 117 ms for starting a request (targets: 1 s and 2 s) |
| AI on the Docker stack (live OpenRouter) | Status, economy draft (1 attempt, 41 s, USD 0.0025), idempotent replay, explanation (USD 0.0005) and permission checks: 7 / 7 |
| AI evaluation (`tools/ai-eval`) | Economy profile, 8 cases: 8/8 valid, judge average 3.75/5, USD 0.031, p50 29 s. Auto profile (Jev Router), 2 cases: 2/2 valid, judge 4.0/5, p50 8 s. Total spend about USD 0.28 |
| Contract check / manifests | 50 checks / 132 checks across 3 overlays pass |
| Procurement stub | 87 tests pass, including MCP authentication, endpoint and fault modes |
| Compatibility spikes ([ADR 0001](adr/0001-dependency-baseline.md)) | 10 of 10 pass. S-5 (two engines, kill) now passes via T-RES-02 and T-RES-03 |

Defects found and fixed this round:
- Graceful shutdown unlocked running jobs too early and recorded a spurious failure. Fixed by setting `unlock-owned-jobs: false` (T-RES-05).
- A database outage returned an HTML 500 after 90 s. A new `DatabaseUnavailableFilter` returns `503`, and the 10 s Hikari timeout makes it fail fast (T-RES-04).
- The policy cache took 60.1 s to drop removed access, so its TTL is now 50 s to keep the 60 s promise.
- The run timeline could lag one poll behind the status.
- Real-stack E2E specs depended on test order.

## R0 requirements

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| FR01 | OIDC sign-in and workspace membership | Verified | T-SEC-01 (generated foreign-workspace sweep over all operations), T-SEC-05, IdentityApiTest; Docker login through Keycloak with PKCE; RP-initiated logout |
| FR02 | Workflow list and draft CRUD | Verified | T-API-02 (ETag/If-Match), CatalogApiTest, T-UI-04 |
| FR03 | Business canvas with keyboard alternative | Verified | T-UI-04 (keyboard-only edit through the Steps tab), T-UI-05/06 |
| FR04 | Schema-based forms (browser and server) | Verified | T-RUN-01, FormValidator fixture tests, frontend renderer tests |
| FR05 | Human tasks: claim, decide, one winner | Verified | T-TASK-01…04, T-SEC-03, T-UI-03 |
| FR06 | Rules (decision tables) | Verified | T-COMP-04 (DMN parity), T-COMP-05, decision boundaries 4,999.99 / 5,000 / 5,000.01 |
| FR07 | REST connector with honest outcomes | Verified | T-EFF-01…10, T-SEC-07, API E2E scenarios 7–9 |
| FR08 | Publish (validate, immutable deployment) | Verified | T-COMP-01…03, T-API-03, T-UI-02 |
| FR09 | Start workflow, idempotent | Verified | T-API-04, T-RUN-08, API E2E scenario 6 |
| FR10 | Run detail, persistence, recovery actions | Verified | T-RUN-02, T-UI-02, operator retry in API E2E; RestartTest and local Compose restart runner (T-RES-01) |
| FR11 | Versioning and rollback | Verified | T-RUN-06 (version pinning), T-UI-02; T-RUN-07 rollback and pinned-run assertions |
| FR12 | In-app notifications | Verified | T-RUN-05 |
| FR13 | Secret-free export | Verified | CatalogApiTest deterministic ZIP; SecretIsolationTest file-backed credential canary across responses, logs, audit metadata and decoded ZIP entries (T-SEC-08) |
| FR14 | Audit of sensitive actions | Verified | Audit assertions in catalog, task, access and security tests (`auth.denied` included) |

## Open R0 items

| Item | Ticket | Status |
|---|---|---|
| Resilience suite T-RES-01…05 | B08.3 | Verified (in-JVM tests plus Docker scenarios; see evidence) |
| Reconcile `confirmedApplied` (T-EFF-06), rollback (T-RUN-07), secret leak (T-SEC-08), safe text (T-SEC-12) | B05.5, B03.5, B08.4, B08.6 | Verified. `ReconcileResumeTest`, `RollbackPinningTest` and `SecretLeakScenariosTest` (scans 574 database columns) were added. Production procurement verification remains open |
| Cross-instance revocation (T-SEC-09) | B02.2 | Verified. 60.1 s with the old 60 s TTL; the TTL is now 50 s |
| Pilot performance smoke (T-PERF-01) | B08.3 | Verified on the local Docker stack; repeat on the pilot cluster |
| Database restore exercise (T-RES-06) | B08.5 | Needs input (D06 database access) |
| OpenShift deployment on a real cluster | B08.1 | Implemented: manifests render and pass validation (including the optional `orqix-ai` Secret and a 180 s Route timeout). Not applied; needs D06 |
| CI pipeline run | B01.5 | Implemented, not executed; needs D15 |
| Real procurement endpoint | D07 | Needs input |
| PingFederate login | D05 | Needs input; Keycloak stands in |

## Pulled forward from later releases

| Capability | Spec | Status | Evidence / limits |
|---|---|---|---|
| AI draft from a description, Explain, Settings → AI | [S10](build/S10-ai-assistance.md) | Verified with stubbed and live provider | `AiApiTest` (21), `AiDisabledTest`, `DraftRulesTest`; live check 7/7 and eval above. Background generation (202 plus polling), concurrent limits, interruption handling, access rechecks and retention are verified with a stub; earlier live-provider evidence predates that transport change. A router's chosen model is reported only after the call, so the exclusion list can't block it in advance; budgets limit the cost |
| Model policy: low-cost default, excluded costly models, price caps, budgets | S10 §1 | Verified | Economy is `deepseek/deepseek-v3.2` and auto is `typesafe/jev-router`. Opus, Fable, Mythos, `*-pro*` and `:batch` models are excluded. Caps are USD 1/3 per million tokens, USD 10 a month and USD 0.50 per request |
| Camunda 7/8 BPMN import with compatibility report | [S11](build/S11-camunda-import.md) | Verified | `MigrationApiTest`, `MigrationSafetyTest`, `FixtureDispositionTest` (every element gets a disposition; one test per blocked construct; XXE rejected); browser spec t-ui-09 |
| Pega archive assessment and reviewed manual mappings | [doc 17](17-migration-workbench.md) | Verified on synthetic fixtures | No automatic native Pega rule conversion |
| Brand palettes (`wf` default, `orqix`), studio auto-layout, request references `ORQ-n` | S7 §1, §5, §8 | Verified | Contrast tests, t-ui-10, references test; before and after screenshots in `evidence/design/` |

## Design review follow-up (9 October 2026)

- **My tasks:** fixed automatic selection of available work and stale panels when tabs change; explicit tab choices are preserved.
- **Workflows list:** version state now has an icon and text; Start and Edit already had different emphasis.
- **Runs:** added permission-scoped recovery links on attention rows; the filter already had a pressed state.
- **Run detail:** recovery guidance leads with reconciliation for unknown outcomes and otherwise the allowed retry or ownership review.
- **AI explanation:** `explain-v2` receives compiler findings; authoritative readiness is shown separately from AI prose.
- **AI generation:** completed async transport (202 plus polling), workspace notices, retention and concurrent admission checks.

Current code review and verification: [release audit](18-release-code-audit.md). The audit reran backend, frontend and browser checks and added Pega mapping-integrity regressions; earlier live AI, performance and resilience evidence above was retained rather than claimed as rerun in this audit.

## Workflow and integration extensions (9 October 2026)

| Capability | Spec | Status | Evidence / limits |
|---|---|---|---|
| Matched parallel branches, wait timers, approval time limits and pinned child workflows | [S12](build/S12-workflow-patterns.md) | Verified | 58 compiler pattern tests and 7 runtime tests; editor route, configuration and reference tests. Graphs remain acyclic; bounded rework is future work |
| Bounded agent connections and approved read-only MCP tools | [S13](build/S13-agents-mcp.md) | Verified with scripted services; one live run | 24 executor, 12 MCP client and 10 full-stack scenario tests (PostgreSQL, Flowable, WireMock OpenRouter and MCP), 2 AI-off tests, 87 stub tests (29 MCP); opt-in live DeepSeek tool-calling run cost USD 0.0006. Real provider accuracy and customer tools require acceptance |

## Later milestones

The [migration workbench](17-migration-workbench.md) now provides a tested Camunda 7/8 subset and Pega archive assessment with compiler-validated manual mappings. Synthetic fixtures verify these boundaries; native Pega automatic conversion and customer equivalence remain open. Remaining R1–R3 capabilities (bounded rework, Teams, full PEGA/LWFS adapters, advanced BPMN/CMMN and Git promotion) remain **Planned** per the [full backlog](10-full-product-backlog.md). Production readiness requires the [production gates](06-delivery-plan.md#production-release-gates), none of which has been passed.
