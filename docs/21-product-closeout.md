# Product closeout and remaining work

Updated 15 October 2026. This is the current remaining-item register. The R0 implementation checklist, current build batch, future product roadmap and customer acceptance are separate scopes. Closing a bounded batch does not mean every R1–R3 epic is implemented. [Build evidence](19-product-build-tracker.md) records final verification and its limits; [competitive delivery](22-competitive-gap-delivery.md) tracks every authorized gap.

## Previous build batch - complete

| Item | Result | Acceptance |
|---|---|---|
| Cross-file migration findings | Complete; frontend checks pass | Reason/source-kind/file grouping; full JSON/CSV with hashes; formula-safe CSV; exports blocked when a file report fails. [S18](build/S18-project-findings.md) |
| Task priority and aging | Complete; real backend and frontend checks pass | Durable 0–100 priority, assignee/operator authorization, version conflicts, required reason, audit/timeline, filter-bound signed pagination, oldest/newest sort and displayed age. Aging does not automatically escalate tasks. [S19](build/S19-task-priority.md) |
| Microsoft historical version selection | Complete with scripted Graph and frontend checks | Integration administrator explicitly loads an approved drive/item history and chooses a pin. Complete bounded results, stale response rejection and no latest fallback. This is a connection test/configuration tool; requester tenant browsing is not implemented. [Documents](microsoft-documents.md) |
| Compare published workflow versions | Complete; frontend and browser checks pass | Choose any two loaded immutable releases; load more to select older releases; display definition changes and fetch failures; layout ignored. Same-version comparison is supported. |
| Step notes | Complete; compiler, frontend and browser checks pass | Plain text, 2,000 characters per step, at most 100 entries; draft autosave, rename/delete cleanup, immutable published definition/export and version diff. Compiler rejects orphan notes. Notes change source hash, including exact-draft migration review, but do not change generated execution semantics. These are annotations, not threaded discussions. |
| Existing projects/review, DMN/operators, banking examples, date waits and Assistant | Retained | Existing committed implementation and regressions are included in coordinated checks. Banking examples remain illustrative. |

Verification: 281 compiler cases; 457 unique application cases (455 pass, 2 optional live-provider skips), with priority/security corrections verified in a focused rerun; 554 frontend tests; 69 contract checks; 39 mock browser passes and 4 real-stack-only skips. Packaging, typecheck, lint, build and API drift pass. Detailed logs and the limits of combined evidence are in [doc 19](19-product-build-tracker.md#closeout-verification).

## Gap completion follow-up

| ID | Item | Current scope |
|---|---|---|
| MIG09 | DMN files within migration projects | Complete within the supported subset. Namespace detection, explicit multi-decision selection, missing/duplicate/unsupported diagnostics, dependency order, editable caller mappings and legacy records. [S20](build/S20-project-dmn.md), [S45](build/S45-multi-decision-projects.md) |
| MIG10 | Current release's reviewed Pega migration | Closed under [ADR 0002](adr/0002-pega-migration-delivery-scope.md): archive assessment, explicit reconstruction and synthetic small/medium/complex target acceptance. 105/105 Docker checks across 10 scenarios and 14 Python tests pass. Native adapter implementation was later delivered as MIG11 on a synthetic grammar ([S112](build/S112-pega-native-conversion.md)); genuine source qualification remains EXT01/AC01. [S39](build/S39-pega-synthetic-acceptance.md) |
| MIG11 | Native Pega rule archive conversion | Implemented on a synthetic grammar, 29 October 2026: strict `<pagedata>` decoding, effective-rule resolution with shadowed candidates, per-rule conversion of flows, decisions, when rules, properties, simple sections, service levels and case type stages, source-linked blockers for everything else, explicit group and approval mappings, compile and separate publication. Round 2 adds circumstance and date variants of decision rules, subflows and split-joins as child workflows, business calendars, escalations and case-level service levels; spin-offs stay refused with reasons. Round 3 adds Connect-REST connectors as call steps on a mapped connection, simple data transforms as mappings, creation validate rules as form validation, and case types as CMMN case definitions with optional processes, alternate stages and timers; declare expressions and most data pages stay refused. Grammar fidelity against genuine exports remains AC01/EXT01. [S112](build/S112-pega-native-conversion.md), [S115](build/S115-pega-native-completion.md), [S117](build/S117-pega-native-round-3.md) |
| RULE01 | Automated decision evidence | Complete; verified locally. Actual pinned DMN output plus input and every bounded candidate row, expandable history and request-data permission filtering. Metadata readers receive no matched row, outputs or evidence. [S21](build/S21-decision-evidence.md) |
| UI04 | HTTP failure-route authoring | Closed: independent Next/When failed selectors and canvas handles, correctly styled/attached edges, add-after insertion and compensation-reference rename. 52 focused frontend tests, one mock Edge/axe journey, static checks and mock build pass. Existing HTTP runtime boundaries still apply. [S32](build/S32-known-failure-routes.md#studio-authoring-gap-closed---16-october-2026) |
| DOC05 | Approved document reference authoring | Complete; verified locally. Studio creates exact required request choices and field mappings for a known historical version or retained snapshot. Administrator-only lookups/capture preserve existing authorization. [S22](build/S22-document-authoring.md) |

## Resumed five-stream batch

The five authorized slices are complete and verified locally. Full runs, reviewed-delta checks and their limits are recorded in [doc 19](19-product-build-tracker.md).

| Item | Implemented scope | Limits |
|---|---|---|
| Camunda 8 import | HTTP connector hints with approved operation mappings, bounded FEEL, task settings/timers/child calls and Camunda Forms; S11 | Task forms are notes. Unsupported source semantics have explicit reasons; no generic native connector; only plain-variable message catch import is supported |
| Message events | Workspace-authorized inbound API, message wait, bounded buffer/fan-out, timeout routes, idempotent acceptance; S23 | No signal/error/compensation events or unbounded broadcast |
| Run diagram and Insights | Pinned-release derived path, current steps, throughput, cycle times, bottlenecks, overdue approvals/waits and CSV; S24 | Diagram history cap is explicit; no process mining or arbitrary BI query designer |
| Test workflow | Pure dry-run trace/reasons, route coverage, saved browser cases, nested child simulation with global limits; S26 | Simulated outcomes do not prove external effects or engine timing; cases do not gate publication |
| Attachments and stages | Request-form uploads, request file lifecycle/quotas, optional scanner hook, declared case stages/deadlines/notice history and Studio authoring; S25 | Scanner-disabled files show Not scanned; no document generation, adaptive CMMN or stage-driven rerouting |

## Current competitive batch: verified locally

| Bounded implementation | Current boundary |
|---|---|
| Skills, working calendars and overdue notices; [S27](build/S27-work-management.md) | Fresh group-based skill intersection and existing candidate queues; pinned working-hour due dates; bounded assignee in-app notices. No proficiency/load dispatch, shared calendar administration or automatic reassignment |
| Shared tests and multi-input decisions; [S28](build/S28-rules-authoring.md) | Designer-only samples and exact suite/draft/dependency publication attestations; flat 2–8-field AND rules with disjoint UNIQUE behavior. Other hit policies/effective-dated packs remain open |
| Generated documents and Microsoft browsing; [S29](build/S29-documents-integrations.md) | Plain-text PDF/DOCX templates pinned in releases, attachment authorization/limits/deduplication, and explicit approved-folder file/version choices with separate grants/audiences. No rich document designer or unrestricted tenant search |
| Incident correction and watchdog; [S30](build/S30-incident-correction.md), [S31](build/S31-run-watchdog.md) | Explicitly editable scalar input before any captured effect/prior consuming step; separate retry. Proven-empty stale requests become operator incidents with controlled cancellation; no arbitrary token reconstruction |
| Known HTTP failure routes; [S32](build/S32-known-failure-routes.md) | Journal-confirmed permanent rejection or read-only invalid response, guarded failure facts and replay without resend. Failed business outcomes have distinct presentation/filtering. Unknown effects and retry exhaustion remain incidents; generic compensation/signal support is open |
| Conditional questions/layouts and typed rows; [S35](build/S35-conditional-forms.md) | Pinned server validation, hidden-value rejection, guarded consumption, bounded scalar row columns and Studio/request integration. Count/sum use a shared bounded resolver; object-row workflow iteration/general task correction remain open |
| Suspend/resume and release migration; [S37](build/S37-suspend-resume.md), [S38](build/S38-release-migration.md) | Operators freeze a request (tasks, timers, messages) and move a suspended request waiting at one approval/wait to a release whose travelled graph, referenced decisions/connections and waiting step are identical. Wall-clock due dates continue; no child-tree suspension, parallel-section or bulk migration |
| OpenAPI connection import; [S36](build/S36-openapi-import.md) | Bounded local JSON/YAML review, explicit selected operations/retry confirmation, separate immutable registration and ordinary binding/security rules. Unsupported shapes have reasons; remote compatibility requires acceptance |

Final combined verification: 440 compiler cases and 651 application cases (647 pass, four intentional skips), zero unresolved failures/errors, coverage gate and executable packaging pass. The full run plus corrected two-test aggregate fixture rerun is explicitly distinguished from a second full run. Frontend passes all 716 tests; final import UI regression passes 3/3. Contracts/static checks pass, the mock browser suite passes 62 with four real-only skips, the real import browser test passes, and Docker API scenarios pass 94/94 checks. The rebuilt private stack is healthy at http://localhost:18080. [Doc 19](19-product-build-tracker.md#competitive-batch-verification--15-october-2026) records exact logs, image identity and scope. Fourteen bounded slices are verified; nineteen broader engineering epics and customer/infra acceptance gates remain open.

16 October reporting follow-up: AN01 is complete within [S43](build/S43-insights-reports.md)'s bounded scope. Insights now downloads aggregate JSON and daily CSV with current filters, provenance/list-limit notes and stale-query/error guards. All 16 focused frontend cases and one mock Edge download/desktop/mobile accessibility journey pass; static checks and mock build pass. The broader PL02 analytics/environment epic remains open. No Docker rollout or fresh backend suite is claimed.

## Customer and source acceptance still open

These are customer/cutover gates and cannot be closed using synthetic fixtures. They are not prerequisites for a clearly labelled synthetic local demo. [ADR 0003](adr/0003-minimum-demo-pega-scope.md) closes the minimum Pega demo scope using MIG10 + MIG13 and defers MIG11 native-RAP development; MIG11 was later implemented on a synthetic grammar ([S112](build/S112-pega-native-conversion.md)), which does not close these gates. The latest merged build still requires deployment and real-browser rehearsal; see [the walkthrough](demo-pega-migration.md).

| ID | Required input and acceptance | Current boundary |
|---|---|---|
| EXT01 | Genuine Pega/LWFS exports, versions, resolved rules and owner-approved expected scenarios (D03/D04, B2/B3) | Customer-source qualification only; not a minimum-demo blocker. Automatic bounded XPDL conversion and reviewed archive reconstruction are delivered. Native RAP conversion is deferred, and genuine source-runtime equivalence remains unaccepted. |
| EXT02 | Microsoft tenant app consent, resource grants, approved files/drives/download hosts and securely provisioned credentials | Scripted Graph tests establish adapter behavior, not customer access. OAuth provisioning/automatic refresh needs a defined credential contract. |
| EXT03 | Approved procurement/banking/MCP contracts and observed outcomes (D07/D09) | Stub/provider checks are not acceptance against customer systems. |
| EXT04 | Banking process owner, policy, jurisdiction and representative business scenarios (B1–B6) | KYC/credit/dispute examples are configurable samples; domain or regulatory approval is not inferred. |

## Product roadmap still open

These are engineering features, not infrastructure. They remain in the [full backlog](10-full-product-backlog.md) and must not be checked off because the current build passes.

| Area | Remaining functionality |
|---|---|
| Migration breadth | Broader/dynamic form import, optional reviewed AI mapping proposals, additional native connectors, native user-task multi-instance and broader BPMN loss-aware import; bounded multi-decision project selection is delivered in S45 |
| Execution and cases | Generic boundary/error/compensation/signal events beyond S32, subprocesses/CMMN, mutable task/case correction, nested collections and object-row workflow iteration, adaptive case stages, centrally administered calendars and broader escalation/dispatch policies |
| Banking and rules | Effective-dated rule packs, additional hit policies/OR/nested decision rows and broader FEEL, cross-stage separation of duties and multi-currency rules |
| Operations and authoring | Arbitrary incident variable/token correction beyond S30/S31, migration of requests beyond a single waiting approval/wait (parallel sections, HTTP/child steps, bulk or automatic migration), swimlanes, threaded element discussion, full application UI designer, proficiency/load-based dispatch, external-worker SDK and canary releases |
| Documents and integrations | OCR, rich/versioned template library and lifecycle administration, unrestricted tenant search, OAuth lifecycle automation, Teams/mail/JDBC/Kafka adapters and approved write-capable agent tools |
| Platform organization and delivery | Remaining R1–R3 folder/shared-asset organization, advanced triggers/search/analytics, Git promotion and environment lifecycle contracts described by the backlog |

## Infrastructure excluded from this build

Production OpenShift deployment, PingFederate, enterprise network/secret setup, CI execution, HA database/restore exercises, production load/capacity/retention/on-call acceptance and a refreshed deployment of these changes remain open. Testcontainers integration checks do not establish these gates. No existing Compose volumes or user data are reset by this closeout.

## How to run the implemented subset

The simple, medium and complex definitions in `examples/simple-review.json`, `examples/medium-bounded-rework.json` and `examples/complex-batch-review.json` are exercised by `WorkflowPatternsTest` on PostgreSQL/Flowable. Conversational requests/tasks use the Assistant with explicit final confirmation. Workflow document references use registered approved connection operations with stable drive/item identifiers and an explicit version or retained snapshot. Iteration uses bounded scalar `forEach` children (sequential or parallel), ordered collected outputs and bounded repeated children. The outer graph remains acyclic.

For local setup and demo execution, use the repository README and [demo guide](demo-guide.md). Deployment validation belongs to the excluded infrastructure stream; customer source and tenant checks require the inputs above.

The remaining competitive engineering gaps are now authorized and tracked in [the competitive gap delivery register](22-competitive-gap-delivery.md). Current slices are in progress; prior deferment no longer means these features are outside the build objective.


## Worklist presentation and visual case-model authoring - 23 October 2026

[S81](build/S81-full-non-infrastructure-completion.md) delivers UI02e and WM04g within bounded acceptance: exact published workspace worklist assignment/reset with immutable snapshots and authorized task navigation, plus visual authoring/export of admitted human tasks, nested stages, milestones and required/manual controls. Source export preserves the deployed XML. A browser-found worker read-access transition is corrected without changing the server read policy.

Final selected backend verify passes 22/22 with coverage/packaging; all 956 frontend tests pass across 133 files. Types, lint, API drift, E2E types and 88 contracts pass. Two real Edge journeys pass authoring/publication/runtime/export/history and desktop/mobile accessibility. The source-identified local image is backed up, migrated through V113 and refreshed with retained dependencies/volumes and healthy readiness. This is local product verification; the full backend/compiler suites and production/customer qualification are not newly claimed.

The user explicitly retained the full original non-infrastructure objective. Full CMMN planning, repetition, listeners/nested cases, normative suspension/case-file semantics, workflow case tasks, exchange/notation/conformance, and general application composition/actions/responsive-localized authoring/migration/sandboxed extensions remain open in S81. These deliveries do not close WM04 or UI02 or redefine the original scope.


## Native case user events and sequential repetition - 23 October 2026

[S82](build/S82-case-user-events-repetition.md) delivers WM04h within its stated boundary: role-authorized native user events, occurrence-triggered work, finite sequential human-task repetitions and visual authoring. Commands preserve current permission/eligibility, workspace scope, expected version, idempotency, holds and protected history. Required live repetitions block case/stage completion. Individual repeated-item cancellation is withheld because the pinned engine does not evaluate repetition on termination; full conformance remains open.

Broad selected backend verify passes 35/35, plus a final targeted 8/8 rerun including the new nested-stage guard (36 unique tests). All 959 frontend tests, types/lint/API drift/E2E types and 88 contracts pass. Two real Edge journeys and an explicit application-restart continuation pass; exactly three task occurrences and seven history entries survive. The source-identified local stack is backed up, migrated through V114 and refreshed with retained dependencies/volumes and healthy readiness. Full backend/compiler suites and production/customer qualification are not newly claimed. Full original WM04/UI02 acceptance remains required.


## Responsive/localized reusable view presentation - 23 October 2026

[S83](build/S83-view-presentation.md) verifies UI02f: authorable responsive breakpoints, safe density/theme tokens and localized section/field/action/choice labels across request, approval, case and worklist snapshots. The editor includes language-aware preview and publication review. Exact request presentation copies are checked against their published workspace version; locale changes retain canonical values, input validation and current operation grants.

Selected backend verify passes 28/28 plus a final 3/3 request-pin rerun; full compiler 524/524 and all 964 frontend tests pass. Types, lint, API drift, E2E types and 88 contracts pass. Three real Edge journeys verify authoring/pinning/publication, responsive grids/cards, translated request submission/approval, case choice correction/read-only roles and mobile accessibility. The source-matched local image is backed up/refreshed with migration 114 and healthy readiness; dependencies/volumes/topology are retained. Full application-backend and production/customer qualification are not newly claimed. General composition/actions/migration/extensions and full CMMN remain required under the full original non-infrastructure scope.

## Application pages, composed actions and guarded extensions - 29 October 2026

[S111](build/S111-application-designer.md) delivers UI02g: workspace-owned, versioned application pages in the view library (regions and layouts, exact view/extension version pins, data-bound lists and record details, conditional visibility, a palette/outline/inspector editor with responsive preview, problem inspection, undo and review), revision-checked routes with append-only history, and an Apps runtime that reads all data through the caller's existing permissions. Chains of allowlisted steps (submit, save draft, claim, approve, reject, save case data, navigate) are pinned with the page, always stop at the first failure and run on the server step by step through the existing services, with idempotent replay. Extensions are registered, versioned bundles with declared capabilities that a different workspace administrator approves; they run in an opaque-origin sandbox under a hash-pinned policy, receive only server-filtered granted data, can request only granted chains, and fall back on crash, timeout, missed heartbeat or navigation. V190-V192.

Selected backend verify passes 30/30 (new page, chain and extension API tests plus security matrix, database privileges, architecture and existing view tests). New frontend tests pass 25/25 (designer, chain editor, runtime, sandbox host, registry, with jsdom axe) and the full frontend suite 1213/1213. A real-browser check against the mock-mode dev server covered draft restore, the live sandboxed extension running its granted chain through the server, revocation fallback and designer problem reporting. Types, lint, API drift and 88 contract checks pass. No real-stack browser journey was run. UI02 remains open: real-stack end-to-end acceptance (including the server-served extension frame) and version migration beyond explicit per-pin updates are still required by S56.

[S110](build/S110-cmmn-canvas-planning-workflow-tasks.md) adds WM04i: drawable CMMN canvas, discretionary planning, cases that call pinned workflow releases with result mapping and late-result fencing, general repetition and CMMN import with source-linked diagnostics. WM04 remains open for nested cases, timers, if-parts, case-file semantics, conformance and live-stack acceptance.

## Application designer completion - 29 October 2026

[S114](build/S114-application-designer-completion.md) completes the UI02 product gaps S111 left open. A reviewed version migration lists every pin of a view or page (workflow drafts and active releases, the newest case definition versions, the worklist, page drafts and published pages, page routes) with diagnostics against a chosen published version and a plan hash; applying the selected pins is refused when the plan changed, moves every holder through its own service in one transaction (new draft revisions, a new case definition version, new worklist and route revisions) and is recorded append-only (V193). Published releases and page versions never change. Pages render file and row controls with the request form's own renderer and staged uploads. Chains gain request correction (the holder's declared-field correction) and case suspend, resume, close, start/complete plan item and plan discretionary item, each through its existing operation with the carried version, a reason and a derived idempotency key (S110 merged for planning). The extension frame adds a permissions policy, no-referrer and an unloading signal that makes the host stop at once.

Full backend verify passes (compiler 640/640, application 1037 with 0 failures). the full frontend suite passes 1237/1237. Types, lint, API drift and the contract checks pass. A real-browser check against the mock-mode dev server covered the review and update of a page-draft pin and the hardened frame (unloading before a self-navigation, a busy frame not blocking the page). No real-stack browser journey was run; UI02 stays open only for S56's real-stack authoring-to-runtime acceptance.

## Application designer round 3 - 29 October 2026

[S116](build/S116-application-designer-round-3.md) closes the remaining UI02 product gaps with a sound design. An approver can send a request back to its requester for the fields the step declares correctable: the decision waits, the requester answers on the request page through the same validated, audited correction path, and the decision then needs the new version (V194). Chain steps may declare their own inverse (cancel request, restore draft, release task, restore case data, resume or hold a case, withdraw a send-back), run authorized again in reverse order when a later step fails and recorded per step; plan-item steps can take the newest occurrence or let the person choose one. Extension versions may carry hashed multi-file bundles (modules, styles, fonts, images) served to the sandboxed frame only through a short-lived signed path, with imports confined to the bundle (V195). The pin review also lists inactive releases and older case versions read-only. The two UI02 specs that failed on the first live run are fixed; the extension failure was a busy frame stalling the host page in Chromium's shared process, now documented.

Full backend verify passes (compiler 641/641, application 1061 with 0 failures). the full frontend suite passes 1252/1252. Types, lint, API drift and 88 contract checks pass; the mock-capable E2E specs pass in mock mode. No real-stack browser run of this round.
