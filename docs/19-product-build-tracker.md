# Product build tracker

Updated 9 October 2026. This is the active tracker for the next build. Existing verified scope is recorded in [feature status](feature-status.md); the broad backlog describes the full product, not a completion claim.

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
| UX01 | Request titles in lists, "Changes not published" label, plain-language Assistant | Verified | Title tests (4 backend, 9 frontend), 12 Assistant API tests, Assistant browser spec on mock; real-stack Assistant spec passes |
| MIG03 | Native Pega semantic conversion and equivalence | Needs external input | Genuine exports, source versions and expected business scenarios; no universal automatic-conversion claim |
| DOC01 | SharePoint/OneDrive approved document operations | Verified | Stable identifiers, explicit version, bounded content, binding credentials and redirect isolation; scripted Graph tests |
| DOC03 | PDF/OOXML extraction and retained snapshots | Verified locally | 7 parser tests, 14 Graph adapter tests, 4 snapshot workflow tests, 1 historical-version workflow test; PDF/Office parser runs in hardened standard image |
| DOC02 | Real Microsoft tenant acceptance | Needs external input | Tenant/app permissions, approved sites/files and credential provisioning |
| CHAT01 | Conversational request/task execution | Verified API and UI slice | Multi-turn field collection, explicit reviewed submission/decision, existing authorization and idempotency, resume and isolation tests |
| CASE01 | Request collaboration | Verified: comments, delegation and reminders | 3 backend collaboration tests, 5 delegation UI tests, real-browser handoff and decision; attachments, stages and business calendars remain planned |
| OPS01 | Standard image build and browser validation | Verified: standard image, migrations, healthy stack and browser checks | Standard build or accurately recorded registry limitation; healthy local stack, real browser checks |
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
| Multi-file migration projects, review severity and mapping maker-checker | Planned | Separate durable contracts, role separation and real project fixtures |
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

## Current completed build evidence

- Backend: 267 compiler tests and 241 application cases (239 passed, 2 opt-in live tests skipped), zero unresolved failures/errors in the final unique Surefire reports. The initial isolated run exposed test-directory filtering in architecture checks and the new delegation request fixture in the security matrix; both were fixed and their complete focused suites passed. Coverage and executable-jar packaging pass in the final `verify -DskipTests` step.
- Workflow runtime: all 14 pattern tests pass, including simple/medium/complex definitions, reverse child completion, ordered collected arrays, restart, cancellation and distinct child effects. All 3 collaboration tests pass.
- Frontend: 452 tests pass; typecheck, lint, production build and API drift checks pass. E2E TypeScript also passes; corrected the existing pattern helper's username type.
- Contracts: 57 checks pass. Migration comparator/archive tests: 4 pass. The six live local migration golden cases pass against the rebuilt image; their source observations remain explicitly synthetic.
- Browser: 4 real-stack checks pass (Camunda import, Pega reviewed mapping, guided conversation/comments, eligible-user delegation and recipient decision), including focused axe scans.
- Documents: PDF and DOCX parse through both the packaged executable jar and the standard hardened runtime image. Parser, historical-document and snapshot integration tests pass. Real tenant access is not established by these scripted Graph fixtures.
- Deployment: standard image build passes, V15/V16 apply without resetting the database, Compose configuration validates, and all 4 long-running services are healthy. Reminder environment settings are forwarded by local Compose; scheduling is disabled in the migration profile.

Logs are retained under `evidence/remaining-*`; the combined backend run and its architecture/security corrections are retained separately. `evidence/migration-live.zip` contains the latest six-case acceptance evidence. Its identifiers/checksum change on a new live execution; archive determinism applies to identical input evidence.

The implemented build batch is complete. Remaining roadmap work includes real-source Pega resolution/conversion and live-case migration, multi-file migration projects and mapping maker-checker, banking templates/domain decisions, document picker/OAuth automation/OCR, object or mutable collection rework, recurring escalation/business calendars, and enterprise production acceptance. These are not represented as completed capabilities. See the decision register and competitor analysis for their inputs and acceptance criteria.

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

### Merged-tree evidence (10 October 2026)

| Check | Result |
|---|---|
| Backend `./mvnw -B verify` ([log](../evidence/merged-backend-verify-1010.log)) | BUILD SUCCESS. Compiler 267 tests, application 436 tests, 0 failures, 0 errors, 2 skipped (opt-in live-provider tests); coverage gate passes |
| Frontend | 490 tests, typecheck, lint, production build and API drift check pass |
| Contract check | 67 checks pass; migration-equivalence offline tests pass |
| Mock-mode browser suite, Edge ([log](../evidence/merged-e2e-mock-1010.log)) | 32 passed, 4 skipped (real-stack only), 1 failed: the AI-draft spec failed once while the backend suite was using the machine and passed on an immediate rerun (2/2). Treat it as timing-sensitive, not proven stable |
| Standard image build ([log](../evidence/merged-image-build-1010.log)) | `docker build` of the multi-stage Dockerfile succeeds and is tagged `orqix/orqix:merged-1010` |

**Not verified:** the local Compose stack was **not** recreated with this image (the action was declined), so the running stack, the real-stack browser checks, the live migration acceptance run and the performance and resilience scripts were not run against this code. Production, native Pega and real-tenant acceptance remain open as before. Banking remains a proposed domain extension until B1–B6 are answered. The dev realm needs a third publisher in `orqix-purchasing-publishers` before the three-person migration review can be shown on the real stack.
