# Feature implementation status (R0)
Updated 15 October 2026 · status vocabulary from [doc 09](09-master-plan.md#definition-of-a-build-ticket)

**Verified** = automated tests pass against real PostgreSQL/Flowable (and, where stated, the Docker stack with real Keycloak and the procurement stub). **Implemented** = code exists; integration or acceptance verification is pending or limited as stated in the row. Nothing is marked Verified on mocks alone.

## Current verified checkpoint - 15 October 2026

The resumed competitive batch verifies fifteen bounded tickets in the [delivery register](22-competitive-gap-delivery.md) (EX04 operator suspend/resume and compatible release migration, added 16 October, is recorded in [doc 19](19-product-build-tracker.md)). These include group-based skill restrictions, working calendars, overdue notices, shared publication test suites, conjunctive decisions, generated PDF/DOCX, approved Microsoft folder/version choices, guarded incident correction, empty-run detection, known HTTP failure routes, conditional forms/typed rows/counts/totals, and reviewed OpenAPI import. Their limits and remaining epics are explicit in [product closeout](21-product-closeout.md).

| Check | Final observed evidence |
|---|---|
| Backend | Compiler 440 pass; application 651 unique cases, 647 pass/four intentional skips, zero unresolved failures/errors. Full clean run plus corrected two-test aggregate fixture rerun; final verify/packaging and unchanged coverage gate pass |
| Frontend | 716 tests pass; final import UI checks 3/3; static checks and production/mock builds pass |
| Browser | Mock Edge 62 pass/four real-only skips; added real OpenAPI import browser test passes with actual Keycloak, keyboard focus, axe and separate registration |
| Docker API scenarios | 94/94 checks across authoring/import/rows/DMN, attachment streaming limits/stages, simple/medium flows and complex parallel/timer/child execution |
| Contracts/tools | 88 contract checks, four offline migration tests and 132 manifest checks pass; OpenAPI lint passes with 48 documentation warnings |
| Local deployment | Healthy isolated stack at http://localhost:18080; image `orqix/orqix:validated-1015`, migrations through V29. Existing stack and volumes are preserved |

[Doc 19](19-product-build-tracker.md#competitive-batch-verification--15-october-2026) records exact logs, combined-run methodology and image identity. Eighteen broader engineering epics and external/customer/infrastructure acceptance gates remain open. Historical totals and limits below describe their dated checkpoints; this section and the delivery register govern the current batch.

## Prior committed baseline - 10 October 2026 (demo release candidate, second pass)

The following evidence was recorded by the other model for the committed baseline before the current closeout changes. It supersedes older baselines for that revision; it does not certify the new V20 priority migration or review UI. Current closeout checks are recorded in [doc 19](19-product-build-tracker.md); remaining product/customer/infra work is in [doc 21](21-product-closeout.md).

| Suite | Result |
|---|---|
| Compiler | 280 tests pass |
| Application (Testcontainers PostgreSQL 18, WireMock) | 453 tests pass, 2 skipped (opt-in live AI and agent tests) |
| Frontend (Vitest) | 530 tests pass; typecheck, lint, build and API-type drift check pass |
| Browser E2E, Docker stack | 30 pass, 11 skipped (mock-only checks); no serious accessibility findings |
| Browser E2E, mock mode | 37 pass, 4 skipped (real-stack-only checks) |
| API E2E, Docker stack | Purchase scenarios with fault injection 26/26; rollback, reconcile, cancel, forged fields 33/33; agents (Demo 2) 19/19; AI drafting (Demo 7, live provider) 21/21; parallel, timer, child (Demo 4) 33/33; wait until a date 26/26 |
| Load smoke (T-PERF-01), 20 users for 10 minutes | 43,692 requests, 0 errors, 69 requests/s; p95 16 ms for list and task APIs, 82 ms to start a request; every approved run completed with exactly one purchase order |
| AI draft evaluation (economy, 8 cases) | 8/8 valid and 8/8 valid on the first attempt, judge average 3.5/5, one semantic miss (expense-claim threshold not modeled as at-least/below), USD 0.026; the provider was slow this time (p50 49 s) |
| Contract check / manifests | 68 checks / 132 checks across 3 overlays pass |

### Demo readiness

| Demo | Status | Evidence and limits |
|---|---|---|
| 1 Business approval | Verified | Real stack end to end including restart, finance routing, rejection, version pinning, cross-workspace denial, fault recovery. Real procurement endpoint (D07) still needed for the final acceptance |
| 2 Agent → MCP tool → rule | Verified | `AgentFlowsTest`, `AgentExecutorTest`, `McpClientTest`, stub MCP (87 tests), live DeepSeek run for USD 0.00065, API scenarios 20–23. One MCP server per agent connection; read-only tools only; no Teams or write tools ([S13](build/S13-agents-mcp.md)) |
| 3 Teams approval | Deferred | Secured in-app inbox is the baseline; needs a Teams tenant (R2) |
| 4 Child workflow | Verified | Parallel branches, timers and pinned child workflows: `WorkflowPatternsTest`, compiler rules (267), API scenarios 15–17 on the real stack including the real one-minute timer. Date waits were subsequently added and verified; cancelled child not restartable ([S12](build/S12-workflow-patterns.md)) |
| 5 Camunda import | Verified (supported subset) | Report with a disposition for every element, XXE and size limits, equivalence at the 5,000 boundary |
| 6 Pega import | Blocked | Needs a genuine Pega export; only inventory and manual mappings on a synthetic archive exist |
| 7 AI workflow builder | Verified | Background generation, review panel, explain, model policy and breaker; API scenarios 30–31 on the real stack; eval results in the older baseline below ([S10](build/S10-ai-assistance.md)) |

Defects found and fixed in this round: run comments returned 400 instead of the hidden 404 to outsiders (security matrix); the frontend did not typecheck after the Assistant and comments work; `openapi.yaml` had acquired Windows line endings, which broke a contract test; phone layouts overflowed sideways on long titles (819 px wide at 375 px).

### Known limits to state when asked
- AI generation takes 10–60 s and depends on the provider; budgets cap spend (USD 10 a month, 0.50 a request). Jev Router cannot be restricted to a candidate list, so its chosen model is checked after the call and the profile is paused for the day if it is forbidden.
- The Assistant (guided conversation) was reviewed on the real stack and rewritten in plain language: starting a request, reviewing and deciding a task with an explicit confirm step, and a mock mode. Limits: only the latest turn resumes after a reload, lists of structured items must use the form, My tasks and My requests show 25 items, no comment on a decision. Its first-turn AI matching is optional and was not reviewed.
- Dates in waits are UTC; a date cannot be used in conditions or decision tables yet.
- Nothing here has run on a real OpenShift cluster, real PingFederate, a CI host or the real procurement API; the production gates remain open.

## Historical combined verification baseline

The earlier broad verification was run on 9 October 2026 against a freshly rebuilt Docker image and clean database. The subsequent [release code audit](18-release-code-audit.md) reran compiler/application, frontend and browser checks while preserving existing local data; other rows retain the earlier evidence. The counts below are historical baseline totals; they do not include or certify every later extension described at the end of this document.

| Suite | Result |
|---|---|
| Compiler (`backend/compiler`) | 266 tests pass; JaCoCo gate passes |
| Application (`backend/app`, Testcontainers PostgreSQL 18, WireMock) | 223 tests run, 2 skipped (opt-in live AI/agent tests), no failures; API responses validated against `contracts/openapi.yaml` |
| Frontend (`frontend`, Vitest) | 444 tests pass, including contrast checks of both palettes and a no-hard-coded-color scan; typecheck, lint, build and API-type drift check pass |
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
| Matched parallel branches, wait timers, approval time limits and pinned child workflows | [S12](build/S12-workflow-patterns.md) | Verified | 58 compiler pattern tests and 7 runtime tests; editor route, configuration and reference tests. Outer graphs remain acyclic; bounded child rework and scalar collections are extended in S14, including parallel iteration and collected output arrays |
| Bounded agent connections and approved read-only MCP tools | [S13](build/S13-agents-mcp.md) | Verified with scripted services; one live run | 24 executor, 12 MCP client and 10 full-stack scenario tests (PostgreSQL, Flowable, WireMock OpenRouter and MCP), 2 AI-off tests, 87 stub tests (29 MCP); opt-in live DeepSeek tool-calling run cost USD 0.0006. Real provider accuracy and customer tools require acceptance |

## Later milestones

The [migration workbench](17-migration-workbench.md) now provides a tested Camunda 7/8 subset and Pega archive assessment with compiler-validated manual mappings. Synthetic fixtures verify these boundaries; native Pega automatic conversion and customer equivalence remain open. Remaining R1–R3 capabilities (mutable correction forms, nested/object collections, business-calendar SLAs, Teams, full PEGA/LWFS adapters, advanced BPMN/CMMN and Git promotion) remain **Planned** per the [full backlog](10-full-product-backlog.md). Production readiness requires the [production gates](06-delivery-plan.md#production-release-gates), none of which has been passed.

## Combined product build baseline (9 October 2026)

The [active tracker](19-product-build-tracker.md) records integration of the other model's changes and [competitive analysis](20-competitive-analysis.md). The historical coordinated clean backend run passed 266 compiler and 223 application tests (2 opt-in live tests skipped); verify/package passed the compiler coverage gate. At that revision, the frontend run passed 444 tests, typecheck and lint; production build and API drift checks passed. Standard application and stub images built, migrations completed without removing local data, and the refreshed stack was healthy. Subsequent extensions require their own checks.

| Capability | Status | Evidence and limits |
|---|---|---|
| Bounded repeat and scalar collection children | Verified: bounded repeat, sequential/parallel scalar collections and collected arrays | Historical repeat/sequential evidence: 3 focused compiler and 11 runtime pattern tests, including packaged simple/medium/complex definitions. The current extension supports sequential or parallel child iteration and ordered `collectionMapping` arrays, preserving occurrence identities and aggregate success. Same mapped inputs on rework; mutable correction forms and nested/object collections remain open. Current extension verification is tracked separately in [doc 19](19-product-build-tracker.md) and [S14](build/S14-bounded-iteration.md) |
| Camunda parallel/duration/child conversion | Verified | MigrationApiTest publishes and executes the imported fixture; unsupported timer/event/loop semantics remain blocked |
| Pega XML rule identities/dependency evidence | Verified on synthetic fixtures | Metadata inventory and reviewed mappings; source-rule resolution and native semantic equivalence remain external/unbuilt |
| SharePoint/OneDrive document reads | Verified baseline with scripted Graph | Historical evidence: 14 adapter tests and 1 runtime test; pinned text, version/hash output, size and redirect safeguards. Real tenant acceptance remains open; historical version selection in connection administration is added in the current closeout |
| PDF/OOXML snapshots and extraction | Verified locally | 7 parser tests, 4 snapshot integration tests, historical-document workflow tests and executable-jar/container PDF/DOCX probes pass. Version/hash-linked captured bytes, quotas and active-run retention are bounded. OCR and real-tenant acceptance remain open |
| Guided conversational execution | Verified | 7 API tests, 4 UI tests and real-browser request/resume/confirmation/comment flow. Guided execution and explicit confirmation, with subsequent optional first-turn intent matching and plain-language field answers; no unrestricted autonomous execution |
| Immutable request comments | Verified | 2 API tests and real-browser save/reload; data visibility, participants, idempotency, audit and immutable grants |
| Task delegation and durable reminders | Verified locally | Task holders can delegate only to eligible users with permission/separation-of-duties checks, version checks and audit. Durable reminders deliver one in-app notification to a currently eligible assignee; they are not group broadcasts, email reminders, recurring escalation or business-calendar SLAs |
| Migration findings review/export | Verified in focused UI checks | JSON job export and CSV findings/provenance; spreadsheet formula protection and per-process source-kind grouping with kind/severity/unresolved filters. Eight focused migration/export tests pass. Cross-file projects are implemented and cross-file grouping is added in the current closeout; XLSX export remains roadmap |
| Target migration golden scenarios and evidence packs | Verified on synthetic source fixtures | Six live imported-workflow cases pass: manager approval, below/at finance threshold, manager/finance rejection and invalid input. Checks include task route, terminal outcome, successful effects and matching stub orders. Four standalone Python tests pass; deterministic ZIP includes source/report/mappings/release/run/results/checksums. Offline simulated mode is labeled separately. Genuine customer-source equivalence and source-owner approval remain external |
| Other-model agent/pattern additions | Verified in combined suites | 10 agent-flow tests, 2 disabled-agent tests, 4 mock-browser checks; real agent API 17/17 passed with existing configured provider and scripted MCP registry |

Historical combined browser regression: 11 checks passed on the Docker stack (keyboard studio,5 accessibility checks, CSP tour,2 migration checks, conversation/comments). Four mock-browser checks pass for agent configuration and workflow patterns. Prior broad browser, performance, resilience and deployment evidence is retained above; these were not all rerun in this integration pass.

The extended Docker pattern API demo passed 33/33 checks: parallel approvals, real durable wait, pinned child execution, mapped output, exactly one external order, gap-free timeline, republish pinning, recursive cancellation and rejected-branch termination. Assistant/comment browser acceptance also includes axe scans with no serious or critical findings.

The earlier focused conversation rerun passed all 7 cases, including malformed JSON collections, item constraints, collection bounds and confirmed submission. This adds one regression case after the 223-test full application run.

## Extension reconciliation after the historical baseline

The historical suite totals above remain evidence for their original revision. Later additions do not inherit those counts automatically. Parallel scalar child iteration and collected child output arrays, delegation and durable one-shot assignee reminders are reflected in the current implementation rows; their current coordinated checks are recorded in [doc 19](19-product-build-tracker.md). PDF/OOXML snapshots/extraction are **Verified locally**: 7 parser tests, 4 snapshot integration tests, historical-document tests, and executable-jar/container parser probes pass. Scripted Graph fixtures do not establish customer tenant access.

The six-case migration acceptance harness has separate live local evidence at `evidence/migration-live.zip`, four standalone comparator/archive tests, and an explicitly simulated offline fixture. It verifies the target against declared synthetic expectations. Native Pega rule resolution, actual source-system equivalence, source-owner sign-off and production cutover acceptance remain open.

Current completed extension verification: 267 compiler tests; 241 application cases (239 pass, 2 opt-in live skipped) with corrected architecture/security suites; coverage/package verification passes. Frontend452, contracts57 and 4 live browser checks pass. The standard image is rebuilt, V15/V16 are applied and all four services are healthy. Full details and retained logs are in [doc 19](19-product-build-tracker.md).

## Product closeout (10 October, evening)

Completed cross-file migration findings exports, audited task priority/age ordering (V20), explicit historical-version selection in connection administration, published-version comparison and saved step annotations. Final frontend suite: 554 pass; compiler: 281 cases; unique application reports: 457 cases, 455 pass and 2 opt-in live skips after the focused priority/security corrections; mock browser: 39 pass and 4 real-stack-only skips, followed by 2/2 final rebuilt review-flow checks. Typecheck, lint, build, API drift, 69 contract checks, compiler coverage and executable packaging pass. The initial full backend run and successful focused corrections are separate evidence, not a claim that another full backend run was executed.

Current evidence is in [doc 19](19-product-build-tracker.md#closeout-verification). Remaining customer/source acceptance, future engineering features and excluded infrastructure are explicitly listed in [product closeout](21-product-closeout.md). The earlier Docker deployment evidence does not certify these new changes.

## Reviewed checkpoint closeout - 11 October 2026

The five resumed slices are complete within their documented boundaries: Camunda 8 import (including a plain-variable message catch subset), workspace message events, run diagram/Insights, designer simulation and saved cases, request attachments and declared case stages. S20/S21/S22 follow-up also passes. Latest evidence: compiler 380; 586 combined application cases (584 pass, 2 optional skips); frontend 676 pass; contracts 72; stub 87; full mock browser 59 pass/4 real-stack skips followed by 18/18 final rebuilt stream checks. Coverage, packaging, API drift, lint, typecheck and Docker image build pass.

The application total combines a full 582-case run with the later reviewed importer/API delta; it does not claim another full run. The image is built, not deployed over the current user stack. [The build tracker](19-product-build-tracker.md#resumed-five-stream-integration---11-october-2026) carries exact logs and acceptance limits; [product closeout](21-product-closeout.md) retains customer inputs, excluded infrastructure and future engineering scope.
