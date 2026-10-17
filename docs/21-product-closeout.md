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
| MIG10 | Current release's reviewed Pega migration | Closed under [ADR 0002](adr/0002-pega-migration-delivery-scope.md): archive assessment, explicit reconstruction and synthetic small/medium/complex target acceptance. 105/105 Docker checks across 10 scenarios and 14 Python tests pass. Native adapter implementation is future MIG11; genuine source qualification remains EXT01/AC01. [S39](build/S39-pega-synthetic-acceptance.md) |
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

These are not infrastructure-only tasks and cannot be closed using synthetic fixtures.

| ID | Required input and acceptance | Current boundary |
|---|---|---|
| EXT01 | Genuine Pega/LWFS exports, versions, resolved rules and owner-approved expected scenarios (D03/D04, B2/B3) | Pega inventory and reviewed reconstruction exist; universal native Pega rule conversion and source-runtime equivalence are unbuilt/unaccepted. Synthetic target evidence is explicitly labeled. |
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
