# Product build tracker

Updated 15 October 2026. This is the active tracker for the current build. The authoritative remaining-item register is [product closeout](21-product-closeout.md), with the authorized competitive gaps in [doc 22](22-competitive-gap-delivery.md). The latest checkpoint is [the current competitive verification](#competitive-batch-verification--15-october-2026); older green counts below describe their historical trees. The broad backlog is not a completion claim.

Statuses: **In progress** means implementation is underway; **Implemented** means code exists but final checks remain; **Verified** means the listed checks pass; **Needs external input** identifies acceptance that cannot be established with local fixtures.

| ID | Slice | Status | Completion evidence |
|---|---|---|---|
| WF01 | Bounded child repetition and rework | Verified | Rejected child followed by approval, exhaustion, pinned release, restart, distinct occurrences and immutable history |
| WF02 | Collection iteration | Verified: sequential/parallel scalar collections | Bounded sequential/parallel scalar collections, ordered collected child outputs and allSucceeded; object iteration remains planned |
| WF03 | Simple, medium and complex executable examples | Verified | Published examples execute approval/rejection, parallel/timer/child paths and bounded repetition through real Flowable/PostgreSQL |
| MIG01 | Camunda parallel/timer/child import | Verified | Supported BPMN converts, publishes and executes; unsupported semantics remain blockers |
| MIG02 | Pega rule inventory and dependencies | Verified | Bounded XML metadata analysis, provenance, useful reviewed mappings and negative parser tests |
| MIG04 | Migration target acceptance and evidence packs | Verified locally | Six live golden scenarios, matching stub effects/orders, four comparator/archive tests and deterministic ZIP; source fixtures are synthetic |
| MIG05 | Camunda importer breadth: `review` severity, grouped service tasks, operators `eq ne gte gt lt lte` with flat all/any groups, DMN import, multi-instance call activities | Verified on the merged tree | 150 migration tests in the agent's tree, then the full merged backend run below |
| MIG06 | Multi-file migration projects and independent mapping review (mapper, reviewer, publisher differ) | Verified on the merged tree (mock browser; real stack not run) | 12 backend tests; 38 new frontend tests; mock-mode browser run on the merged tree below |
| BANK01 | Banking templates (KYC onboarding, credit approval, card dispute) executed on the real engine | Verified on the merged tree | 42 execution tests; see [templates](banking-templates.md) and [capability matrix](banking-capability-matrix.md) |
| WF04 | Date questions and "wait until a date" | Verified | Compiler (12 rule/golden tests), 7 Testcontainers tests incl. restart, 26 API checks on the real stack with a real timer, Playwright mock spec; UTC rule, 366-day horizon, past date completes at once |
| WF05 | "Test this workflow": dry run with trace, coverage and saved cases | Verified | Compiler: 54 simulator tests incl. 23 golden traces shared with the browser port; API tests (access, validation, contract, no side effects, audit counts only, rate limit); Vitest for the mock port (golden), model, saved cases, playback and panel; Playwright mock spec t-ui-15-test-workflow. Not the engine: see S4 section 13.4 |
| UX01 | Request titles in lists, "Changes not published" label, plain-language Assistant | Verified | Title tests (4 backend, 9 frontend), 12 Assistant API tests, Assistant browser spec on mock; real-stack Assistant spec passes |
| MIG03 | Native Pega semantic conversion and equivalence | Needs external input | Genuine exports, source versions and expected business scenarios; no universal automatic-conversion claim |
| DOC01 | SharePoint/OneDrive approved document operations | Verified | Stable identifiers, explicit version, bounded content, binding credentials and redirect isolation; scripted Graph tests |
| DOC03 | PDF/OOXML extraction and retained snapshots | Verified locally | 7 parser tests, 14 Graph adapter tests, 4 snapshot workflow tests, 1 historical-version workflow test; PDF/Office parser runs in hardened standard image |
| DOC02 | Real Microsoft tenant acceptance | Needs external input | Tenant/app permissions, approved sites/files and credential provisioning |
| CHAT01 | Conversational request/task execution | Verified API and UI slice | Multi-turn field collection, explicit reviewed submission/decision, existing authorization and idempotency, resume and isolation tests |
| CASE01 | Request collaboration | Verified: comments, delegation and reminders | 3 backend collaboration tests, 5 delegation UI tests, real-browser handoff and decision; attachments and declared stages are implemented in the 11 October batch below; business calendars remain planned |
| CASE02 | Request attachments | Verified locally (backend, mock browser); real-stack scenario 50 written, not run on a refreshed deployment | `AttachmentsApiTest` (20: types by name + declared type + bytes, size and quota, access matrix, soft delete and audit, scan status, form upload flow), `ClamAvScannerTest`, `SecurityMatrixTest` sweep over the 5 new operations, `t-ui-17-case-stages-files.spec.ts`, `tools/api-e2e/e2e_cases.py`. The default scanner does not scan (`notScanned`); ClamAV over TCP is optional and disabled by default; production must enable a real scanner. Design: [S25](build/S25-attachments-stages.md) |
| CASE03 | Case stages and SLAs | Verified locally (compiler, backend, mock browser) | `StagesAndFilesTest` (23: `WF_STAGE_*` rules, no BPMN change), `CaseStagesTest` (5: sequential and parallel stages, overdue recorded once with two executors, notifications, Runs filter, cancel), Vitest for the Case stages inspector, progress bar and chips. Stages are a presentation and monitoring layer: no escalation or rerouting. Example `examples/case-with-stages.json` |
| OPS03 | Where is my request: run diagram | Verified locally (mock browser and backend) | `RunPathBuilderTest` (9), `RunPathApiTest` (4: access, silent steps inferred, taken routes), 6 mock browser cases in `t-ui-14-diagram-insights.spec.ts` incl. axe and 375 px; real-stack browser run not repeated |
| OPS04 | Insights: workflow analytics | Verified locally (mock browser and backend) | `InsightsApiTest` (5: access matrix, ranges, aggregates, no request data, timeouts and overdue), `InsightsServiceTest`, Vitest page and CSV tests, mock browser cases with axe and 375 px; bounded queries and a statement timeout; no fresh capacity acceptance |
| OPS01 | Standard image build and browser validation | Historical local evidence; current deployment excluded | Historical stack/browser checks do not certify this new priority migration or UI batch |
| OPS02 | Production gates | Needs external input | Enterprise identity/integrations, cluster, CI execution, backup/restore and capacity acceptance |

## Execution matrix

| Level | Required behavior | Evidence |
|---|---|---|
| Simple | Request, one approval, rejection and completion | simple-review.json published/executed via WorkflowPatternsTest; accepts and rejects |
| Medium | Decisions, matched parallel work, timeout, wait and child results | medium-bounded-rework.json and imported parallel/timer/child fixture publish and execute |
| Complex | Nested pinned children, bounded rework/iteration, integrations and concurrency/restart recovery | complex-batch-review.json executes parallel approvals, durable timer and item children; earlier rejection survives later approval |

## Work boundaries

The independent execution, migration, Microsoft document and collaboration streams were integrated in parallel. Shared API contracts and generated types are integrated centrally before final verification. Customer-specific Pega equivalence, Microsoft access and production acceptance retain explicit external requirements. The user's existing deletion of `design/business-workspace.svg` is preserved.

## Competitor-analysis integration

Reviewed [doc 20](20-competitive-analysis.md) and the other model's agent configuration, pattern editor, mock engine, timeline, seed, tests and documentation additions. The coordinated check uses the complete workspace, including these edits. Banking is a proposed domain extension; existing platform slices proceed while B1–B6 and source-owner inputs remain open.

| Item | Status | Next acceptance |
|---|---|---|
| Findings JSON/CSV export | Verified; final frontend: 444 tests pass | JSON preserves the whole job; CSV preserves every finding and source hash, with spreadsheet formula protection |
| Guided conversation | 7 API tests and 4 UI tests pass, including collection answers | 200-turn bound and serialized 50-session admission; no AI intent interpreter claim |
| Other-model agent and pattern features | Verified in combined suites | AI-unavailable warnings, timeline summaries, mock patterns, studio settings and exact workflow outcomes |
| Multi-file migration projects, review severity and mapping maker-checker | Verified in the committed baseline; retained in current regression | Durable contracts, exact-draft independent review, role separation and bounded project fixtures |
| Pega/native equivalence runner and evidence packs | Verified local harness; real-source acceptance needs input | Six live golden cases and deterministic evidence pack; source fixture explicitly synthetic |
| Banking templates and regulatory rule packs | Needs domain decisions | B1–B6 in competitor analysis; no regulatory compliance claim |

## Previous integration baseline

- Backend clean tests: 266 compiler tests; 223 application tests with 2 opt-in live tests skipped; zero failures. Architecture and compiler coverage gates pass.
- Frontend: 444 tests; typecheck, lint, production build and API type drift check pass.
- Contract checker: 55 checks pass.
- Standard Docker application and stub builds pass; V13/V14 migrations applied; existing volume retained; all 4 long-running services healthy.
- Browser: 11 real-stack regression checks plus 4 agent/pattern mock checks pass.
- Real configured-agent API demo: 17/17 checks pass (scripted MCP registry; real provider).
- Extended pattern API demo: 33/33 checks pass, including parallel work, durable wait, pinned children, exactly-one effect, cancellation and rejection. Fixed timeline pagination to use the 100-item limit and nextAfter.

Evidence logs are local under `evidence/product-*`. The simple, medium and complex examples are published and executed by `WorkflowPatternsTest`. This completes the implemented platform slice, while external source/tenant/production acceptance and the explicitly planned roadmap remain open.

Extended pattern API demo passed 33/33. Assistant and request-comment pages also passed axe checks with no serious/critical findings in the focused real-browser flow.

Final focused conversation suite: 7/7 pass, including invalid JSON, item constraints and collection bounds before submission. The full application run above predates this one additional test case.

## Remaining-items integration pass

Parallel collection execution, document extraction/snapshots, task delegation and durable reminders are integrated. Their focused backend, frontend, browser and standard-image checks pass. The six-case migration acceptance harness passes against the rebuilt local stack, with simulated source observations explicitly identified. The current checks are recorded below. Native Pega evidence, real Microsoft tenant access and production acceptance still require external inputs.

## Historical remaining-items build evidence (9 October)

- Backend: 267 compiler tests and 241 application cases (239 passed, 2 opt-in live tests skipped), zero unresolved failures/errors in the final unique Surefire reports. The initial isolated run exposed test-directory filtering in architecture checks and the new delegation request fixture in the security matrix; both were fixed and their complete focused suites passed. Coverage and executable-jar packaging pass in the final `verify -DskipTests` step.
- Workflow runtime: all 14 pattern tests pass, including simple/medium/complex definitions, reverse child completion, ordered collected arrays, restart, cancellation and distinct child effects. All 3 collaboration tests pass.
- Frontend: 452 tests pass; typecheck, lint, production build and API drift checks pass. E2E TypeScript also passes; corrected the existing pattern helper's username type.
- Contracts: 57 checks pass. Migration comparator/archive tests: 4 pass. The six live local migration golden cases pass against the rebuilt image; their source observations remain explicitly synthetic.
- Browser: 4 real-stack checks pass (Camunda import, Pega reviewed mapping, guided conversation/comments, eligible-user delegation and recipient decision), including focused axe scans.
- Documents: PDF and DOCX parse through both the packaged executable jar and the standard hardened runtime image. Parser, historical-document and snapshot integration tests pass. Real tenant access is not established by these scripted Graph fixtures.
- Deployment: standard image build passes, V15/V16 apply without resetting the database, Compose configuration validates, and all 4 long-running services are healthy. Reminder environment settings are forwarded by local Compose; scheduling is disabled in the migration profile.

Logs are retained under `evidence/remaining-*`; the combined backend run and its architecture/security corrections are retained separately. `evidence/migration-live.zip` contains the latest six-case acceptance evidence. Its identifiers/checksum change on a new live execution; archive determinism applies to identical input evidence.

That historical batch completed its implemented slice. Projects, migration maker-checker, banking examples and a historical-version selection tool have since been added. Current product/customer/infra boundaries are recorded in [product closeout](21-product-closeout.md); the historical checks above do not certify later code.

## Competitor-analysis build pass (10 October 2026)

Three independent streams were built from [doc 20](20-competitive-analysis.md) in isolated worktrees, merged, and reconciled. The merged tree is verified only where stated.

| Stream | Result | Status of evidence |
|---|---|---|
| Head repair | Three application tests and one compiler test that the previous commits left red were corrected (see below) | Verified in the full backend run below |
| Banking templates | KYC, credit and dispute workflows with 42 execution tests; a Viewer-visible `requestTitle` data leak fixed | Passed in the stream's own tree |
| Projects and independent review | Backend tests, project import page, review policy setting, review panel before publish | Backend tests passed in the stream's tree; frontend verified on the merged tree |
| Importer breadth | Review severity, grouped service mappings, expression operators, DMN import, bounded multi-instance calls | Passed in the stream's tree |
| Merge reconciliation | Duplicate security-matrix cases removed; contract completed (`status` and `convertedWorkflowId` on project files, 201 for convert-next, audit names); the test-side contract shim deleted; UI sends `acknowledgeReview` and shows review, DMN and grouped mappings | Backend compiles (main and test sources). Contract check: 67 pass. Frontend: 490 tests, typecheck, lint, build and API drift pass |

Red-head repairs: `DecisionEvaluatorTest` expected `ne` to be rejected although the compiler supports `ne`, `gt` and `lte`; `ConversationApiTest` expected field labels that the fixture does not have and a provider-call count that contradicted its own earlier assertion; `SecurityMatrixTest` sent empty bodies to the four new migration operations and omitted their tables from its change snapshot; four new operations lacked `x-orqix-audit`; the document-link fixture let an unconstrained string flow into an allow-listed drive field, which the compiler rightly rejected.

### Historical merged-tree evidence (10 October, morning)

| Check | Result |
|---|---|
| Backend `./mvnw -B verify` ([log](../evidence/merged-backend-verify-1010.log)) | BUILD SUCCESS. Compiler 267 tests, application 436 tests, 0 failures, 0 errors, 2 skipped (opt-in live-provider tests); coverage gate passes |
| Frontend | 490 tests, typecheck, lint, production build and API drift check pass |
| Contract check | 67 checks pass; migration-equivalence offline tests pass |
| Mock-mode browser suite, Edge ([log](../evidence/merged-e2e-mock-1010.log)) | 32 passed, 4 skipped (real-stack only), 1 failed: the AI-draft spec failed once while the backend suite was using the machine and passed on an immediate rerun (2/2). Treat it as timing-sensitive, not proven stable |
| Standard image build ([log](../evidence/merged-image-build-1010.log)) | `docker build` of the multi-stage Dockerfile succeeds and is tagged `orqix/orqix:merged-1010` |

**At that morning checkpoint, not verified:** the local Compose stack was **not** recreated with this image (the action was declined), so the running stack, the real-stack browser checks, the live migration acceptance run and the performance and resilience scripts were not run against this code. Production, native Pega and real-tenant acceptance remain open as before. Banking remains a proposed domain extension until B1–B6 are answered. The dev realm needs a third publisher in `orqix-purchasing-publishers` before the three-person migration review can be shown on the real stack.

## Current product closeout pass (10 October, evening)

Reviewed the other model's committed `6fa9fe6` baseline and preserved its date waits, titles, natural-language Assistant, projects/review, DMN import and banking workflows. The status totals in feature-status.md preceding this batch remain prior evidence. This implemented build batch is complete. Its checks and limits are recorded below; future roadmap and customer acceptance remain open.

| ID | Item | Status | Scope |
|---|---|---|---|
| MIG07 | Cross-file findings review and full JSON/CSV export | Complete; frontend checks pass | Complete bounded project reports, provenance, formula-safe CSV and partial-load export protection; S18 |
| MIG08 | Camunda 8 importer: connectors, FEEL subset, due time and boundary timer as approval limits, date timers, call activity propagation, Camunda Forms (adapter `camunda-bpmn-0.5`, V24) | Verified on a clean mirror (HEAD plus these files); mock browser spec passes; real stack not run | 25 unit and 5 PostgreSQL/Flowable tests (`Camunda8ImportTest`, `Camunda8MigrationApiTest`); S11 revision 0.5; hand-authored fixtures only, no customer export; message catch events with a plain correlation variable convert, other message events blocked |
| TASK01 | Priority and aging | Verified locally; complete | Durable audited priority, eligible assignee/operator changes, version conflicts, signed filter/sort/subject-bound keyset pagination, age display; V20 and S19 |
| DOC04 | Historical-version selection | Complete; scripted Graph and UI checks pass | Approved drive/item input and explicit pin in connection administration; requester tenant browsing remains roadmap |
| DESIGN01 | General published-version comparison | Complete; frontend/browser checks pass | Any two loaded releases, load-more for older versions, safe text display and visible fetch failure |
| DESIGN02 | Step notes | Complete; compiler/frontend/browser checks pass | Autosaved plain text, immutable definition/export, rename/delete cleanup and hash distinction |

[Product closeout](21-product-closeout.md) separates customer/source acceptance, excluded infrastructure and future engineering work. **All non-infrastructure roadmap work is not closed.** Existing R0 checklist items that require no customer environment are implemented; the product roadmap retains explicit future features.

### Closeout verification

| Check | Final result and evidence |
|---|---|
| Compiler | 281 cases, zero failures/errors; clean isolated full run; compiler coverage gate passes |
| Application | 457 unique cases: 455 pass, 2 opt-in live-provider cases skipped, zero unresolved failures/errors. The full run exposed the new priority audit registration/action-name and validation expectations; the corrected priority, security matrix and both secret-isolation suites then pass (8/8). [Full run](../evidence/closeout-backend-final-1010.log), [correction/security run](../evidence/closeout-priority-security-final-1010.log), [combined reports](../evidence/closeout-summary-1010.json) |
| Packaging | Isolated `verify -DskipTests` passes after the tested corrections, including the compiler coverage gate and executable jar. [Log](../evidence/closeout-package-1010.log). This packaging invocation does not claim another full test run |
| Frontend | All 554 tests pass in the final full run; typecheck, lint, production build, API type drift and E2E typecheck pass. [Tests](../evidence/closeout-frontend-final-1010.log), [build](../evidence/closeout-frontend-build-1010.log) |
| Contracts and migration harness | 69 contract checks pass, including audit names matching the database syntax; 4 offline equivalence/archive tests pass. [Contract log](../evidence/closeout-contracts-1010.log) |
| Browser | Mock Edge suite: 39 pass, 4 real-stack-only checks skipped. Priority and notes/version comparison pass with axe scans. Both new review flows pass again on the final rebuilt mock bundle. [Full suite](../evidence/closeout-browser-1010.log), [final focused check](../evidence/closeout-review-browser-final-1010.log) |
| Execution examples | Full backend run includes all 14 `WorkflowPatternsTest` cases and the banking examples on real PostgreSQL/Flowable: simple approval, medium bounded rework, complex parallel batch, ordered outputs, cancellation and restart. No external customer equivalence is inferred |

Audit reasons are retained in permission-scoped run history; global audit contains only task ID and prior/new priorities. The targeted security rerun uses explicit normal INFO logging and `debug=false`: the first focused security run captured bootstrap DEBUG output containing a test-only temporary secrets path. The successful rerun verifies normal INFO logging with debug disabled. No real credential or customer acceptance is established by these fixtures.

No deployment/image/production capacity or fresh real-stack browser acceptance was performed for V20 in this pass. Infrastructure remains excluded. Native Pega/source approval, customer Microsoft/integration acceptance and the future product features in doc 21 are still open.

## Resumed five-stream integration - 11 October 2026

The other model's partial implementation was reviewed before changes. Existing Camunda 8 import, message events, run diagram/Insights, simulation, attachments and stage work was preserved. Integration fixes cover duplicate DMN IDs, immutable message acceptance and concurrent buffer admission, global simulation limits, upload lifecycle/quotas/scanner framing, stage authoring, pinned document testability, message timeout reporting and long-history diagram disclosure. No existing Compose volumes were reset.

**Complete and verified locally.** The later `4ced030` checkpoint was reviewed as a separate delta. Its plain-variable Camunda message-catch import and stage chips were preserved. Review added ambiguity blockers for message declarations/subscriptions, an imported-message Flowable/API regression and message-wait timeout handles on the run diagram. No source changes occurred after the final verification manifest was captured.

| Check | Final evidence |
|---|---|
| Backend full clean verify | BUILD SUCCESS: compiler 380, application 582 cases, zero failures/errors, 2 optional live-provider skips. Compiler coverage gate and packaging pass. [Full log](../evidence/resume-backend-final-1011.log) |
| Post-checkpoint Java review | 35/35 pass, including 25 Camunda importer cases, 5 Camunda API/Flowable cases and 5 Insights API cases; `verify` and packaging pass. The combined unique reports now contain 586 application cases (584 pass, 2 optional skips), zero failures/errors. This combines the full run with the reviewed-delta rerun, not another full backend run. [Review log](../evidence/resume-checkpoint-review-1011.log), [combined summary](../evidence/resume-review-summary.json) |
| Frontend | 676/676 tests across 71 files pass on the final tree; typecheck, lint, API drift, production build and E2E typecheck pass. [Tests](../evidence/resume-checkpoint-frontend-1011.log), [build](../evidence/resume-checkpoint-build-1011.log) |
| Contracts and tools | 72 contract checks and 4 offline equivalence/archive tests pass; OpenAPI lint passes with 42 existing documentation warnings; procurement stub 87/87 passes; Python tool syntax checked. [Contracts](../evidence/resume-reviewed-contracts-1011.log), [stub](../evidence/resume-stub-1011.log), [OpenAPI lint](../evidence/resume-openapi-lint-final-1011.log) |
| Browser | Fresh mock Edge full suite: 59 pass, 4 real-stack-only skips. After the checkpoint/pure-helper review, all 18 five-stream cases pass again on a rebuilt bundle and restarted private preview. Includes stage authoring/overdue progression, attachment limits/download/privacy and accessibility. [Full suite](../evidence/browser/mock-final-closeout.log), [final reviewed cases](../evidence/browser/mock-reviewed-final.log) |
| Image | Final multi-stage Docker image builds successfully as `orqix/orqix:resume-1011`; not deployed over the existing stack. [Build log](../evidence/resume-reviewed-image-build-1011.log) |
| Execution | All 14 `WorkflowPatternsTest` cases pass: simple, medium and complex workflows, bounded rework, parallel collections, ordering and cancellation. Recovery, message concurrency, upload quotas/scanner failures, stage monitoring, document workflows and the security matrix also pass in the full run |

The existing Assistant, approved SharePoint/OneDrive references, bounded iteration, migration governance and decision evidence remain covered. The browser tests initially exposed disappearing delivery feedback, demo buffering loss on reload and a mock multipart transport bypass; these were fixed before the green final runs. A live-client import from file mocks also broke the login regression; pure file helpers now avoid that dependency.

This closes the five authorized feature slices and the S20/S21/S22 follow-up. It does not close generic BPMN error/compensation/signal/subprocess/CMMN support, adaptive Pega cases, a UI designer, skills-based work queues, document generation or an external-worker SDK. Genuine Pega/source equivalence, customer Graph/scanner/integration acceptance and production infrastructure remain open in [product closeout](21-product-closeout.md). New real-stack scripts are written but were not run against a refreshed deployment. Local evidence logs are retained in the workspace and ignored by Git. No production or source-runtime parity is inferred.

The remaining competitive engineering gaps are now authorized and tracked in [the competitive gap delivery register](22-competitive-gap-delivery.md). Current slices are in progress; prior deferment no longer means these features are outside the build objective.

## Competitive batch verification — 15 October 2026

The partial work from other models has been reviewed and integrated. S27–S32, S35 and S36 document 14 bounded implementations now verified locally; generic enterprise platform parity and every future epic are not complete.

| Check | Current evidence |
|---|---|
| Compiler | All 440 tests pass with zero failures/errors; the unchanged coverage gate passes. [Full clean run](../evidence/resume-full-verify-latest.log) |
| Application and packaging | 651 unique PostgreSQL/Flowable cases: 647 pass, four intentional skips, zero unresolved failures/errors. The clean run found two invalid new aggregate fixtures (`minItems: 0`); only the fixtures changed to the supported minimum of one, then both tests and `verify`/packaging passed. This combines the full run with its focused correction, not a second full-suite run. [Correction and final verify](../evidence/resume-aggregate-final-verify.log), [combined reports](../evidence/resume-backend-summary-1015.json). Skips are two optional live-provider checks and two disabled migration diagnostics, not acceptance of EX04 |
| Frontend | All 716 tests across 84 files pass. Studio's test helper now awaits actual lazy-route initialization without raising timeouts. Final import UI checks pass again (3/3) after the keyboard-accessibility fix. [Full suite](../evidence/resume-frontend-final.log), [final import checks](../evidence/resume-openapi-ui-tests-final.log) |
| Static checks and contracts | Typecheck, ESLint, API type drift and E2E typecheck pass; 88 contract checks, four offline migration tests and 132 manifest checks pass. OpenAPI lint passes with 48 documentation warnings. [Contracts](../evidence/resume-contracts-final.log), [typecheck](../evidence/resume-typecheck-final.log), [lint](../evidence/resume-lint-final.log), [final import lint](../evidence/resume-openapi-ui-lint-final.log), [API drift](../evidence/resume-api-check-latest.log), [OpenAPI lint](../evidence/resume-openapi-lint-final.log) |
| Browser | Fresh mock Edge suite: 62 pass, four expected real-stack-only skips, including conditional forms, shared publication tests, stage/files and axe scans. The subsequently added real-only OpenAPI spec passes separately on the refreshed Docker application: actual Keycloak login, review, explicit confirmation, keyboard focus, axe, registration and unchanged bindings. [Mock suite](../evidence/resume-browser-full-latest.log), [real import](../evidence/openapi-real-browser-final-1015.log) |
| Real Docker execution | 94/94 API checks: authoring/import/rows/DMN 13, attachment/stages 27, simple/medium 21, complex parallel/timer/child/pinning/cancellation 33. The final UI-only image rebuild is followed by the real import browser check and all 13 authoring API checks again. [Authoring](../evidence/resume-real-authoring-final.log), [files/stages](../evidence/resume-real-cases-final.log), [simple/medium](../evidence/resume-real-simple-medium-final.log), [complex](../evidence/resume-real-complex-final.log) |
| Docker | Final image `orqix/orqix:validated-1015`, image ID `sha256:62d95c5776c2fa5afe241cb4217505fc35837a536408f5235d84dfabc88d523f`, builds and runs healthy in private project `orqix-verify-1012`. App http://localhost:18080, management 18081, Keycloak 18180, PostgreSQL 15432 and stub 18095. Migrations through V29 succeed. Existing stack/volumes are preserved. [Build](../evidence/resume-docker-build-final.log), [image identity](../evidence/resume-image-identity-1015.json) |

Bounded implementations include skill-restricted queues, working calendars, recurring overdue notices, shared publication tests, conjunctive decisions, generated PDF/DOCX, approved Microsoft folder/file/version selection, controlled input correction, stale-empty request detection, known HTTP failure routes, conditional forms/typed rows/counts/totals and reviewed OpenAPI import. Limits are explicit in [doc 21](21-product-closeout.md) and ticket-by-ticket in [doc 22](22-competitive-gap-delivery.md).

Still open: advanced cases/CMMN, general task correction and object-row iteration, automatic skills/load dispatch, broader BPMN events/subprocesses/human multi-instance, general worker SDK, migration beyond a single waiting approval/wait, full application UI design/shared assets, richer rule policies, OCR/rich document administration, additional integrations/OAuth lifecycle and environment/Git promotion. Genuine customer source equivalence, tenant/scanner/integration/banking acceptance and excluded production infrastructure cannot be closed by local fixtures.

## EX04 suspend/resume and release migration — 16 October 2026

Specs: [S37](build/S37-suspend-resume.md), [S38](build/S38-release-migration.md); register row EX04 in [doc 22](22-competitive-gap-delivery.md). Built on the 15 October tree (migration V30 adds `run_suspension`; no other schema change).

| Check | Result |
|---|---|
| Backend | Clean `verify`: compiler 440 pass; application 667 cases, 663 pass, four intentional skips (same as 15 October), zero failures/errors; coverage gate and packaging pass. [Log](../evidence/ex04-backend-verify-1016.log) |
| New backend tests | `RunSuspensionTest` 3, `RunMigrationTest` 4 (PostgreSQL + Flowable, public API), `ReleaseCompatibilityTest` 9; `SecurityMatrixTest` covers suspendRun, resumeRun, migrateRun and getRunMigrationOptions |
| Frontend | 719/719 tests (84 files); typecheck, lint and API type drift pass. [Log](../evidence/ex04-frontend-1016.log) |
| Contracts | 88 checks pass (OpenAPI problem codes, audit actions, message table, generated types) |
| Browser | Mock-mode Edge spec `t-ex-04-suspend-resume` passes with axe scans. [Log](../evidence/ex04-browser-mock-1016.log). Full mock suite was not rerun |
| Not run | Real Docker stack and real-stack browser suite, a refreshed image, OpenAPI lint warnings count, and a browser spec for the migration dialog |

Defect found by the tests: the first compatibility rule compared node settings only, so a request that had passed a decision could move to a release with a different decision table. The rule now compares the decision table and connection version each travelled step references (`HISTORY_CHANGED`). Engine behavior confirmed by execution: Flowable migrates a suspended instance directly, keeping its task, timers and variables.

Limits: due dates, stage deadlines and overdue notices keep running while suspended; timers fire after resume if they fell due; only a request waiting at exactly one approval, timer wait or message wait can move; a request with running child requests cannot be suspended.

## Synthetic Pega reviewed migration acceptance - 16 October 2026

MIG10 is verified for explicitly synthetic small, medium and complex targets. The user confirmed that genuine Pega exports are unavailable and requested generated fixtures. The archives contain descriptive Pega-like XML; their canonical definitions are manually authored from existing examples. This tests the existing reviewed mapping path, not an automatic native Pega parser.

| Check | Observed result |
|---|---|
| Fixtures | Three deterministic archives, canonical definitions and complete per-file dispositions; schema, dependency, checksum, target provenance and separate-process regeneration checks pass |
| Python tooling | 14/14 tests pass, including the four earlier equivalence tests plus ten synthetic acceptance safety tests. [Log](../evidence/pega-tools-tests-1016.log) |
| Live migration/execution | 105/105 checks across ten scenarios pass through real development OIDC and the migration/workflow/task APIs on the healthy `orqix/orqix:validated-1015` stack at http://localhost:18080. All three workflows are separately imported and published. [Log](../evidence/pega-synthetic-live-final-1016.log) |
| Small | Approval, rejection and invalid input |
| Medium | Reject-then-approve child rework, three-review exhaustion, bounded child count and imported release pins |
| Complex | Parallel approvals/join, actual one-minute timer waits, parallel child reviews, reversed completion with input-ordered results, mixed outcomes, early rejection, cancellation propagation and collection bounds |
| Negative cases | Requester upload/conversion denial, outsider inventory concealment, bad checksum refusal, unresolved dispositions and compiler-invalid mapping refusal, explicit acknowledgement, conversion replay and no implicit publication |
| Evidence integrity | 79 ZIP entries, including 19 run snapshots and 19 event timelines; every manifest byte count and SHA-256 verified. [Pack](../evidence/pega-synthetic-live-final-1016.zip), SHA-256 `bfff388ec26c11da58483bb6471b89176d976ae6cb0566148d3ab1413e79a16c` |

[S39](build/S39-pega-synthetic-acceptance.md) records scope and rerun commands are in [the harness guide](../tools/migration-equivalence/README.md). Evidence always records `nativePegaConversionVerified: false` and `sourceRuntimeEquivalenceEstablished: false`. AC01 remains open for genuine exports, independently observed source scenarios and owner acceptance. These tooling results do not certify concurrent changes to unrelated compiler/runtime features.
