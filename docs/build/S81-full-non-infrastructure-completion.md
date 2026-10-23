# S81 - Full original non-infrastructure completion

22 October 2026. The user explicitly confirmed **full original non-infrastructure scope**, rather than a smaller release subset. This record does not redefine that scope, defer unfinished engineering, or close a parent epic after a limited increment. Existing S80 changes are retained. Infrastructure, production topology/deployment and customer-owned credentials/source acceptance remain separate from product implementation.

## Acceptance ledger

| Area | Required remaining product work | Current evidence/status |
|---|---|---|
| Worklist views | Versioned authoring, explicit workspace binding, authorized task-list rendering, concurrency, read-only roles, mobile/a11y | Implemented: published workspace pins, CAS/reset/history, task-summary columns and real Edge desktop/mobile acceptance; general list composition remains separate |
| General application pages | Composable case/worklist pages, reusable components, typed lists/details and conditional visibility | Request/approval/case-data views exist; general page composition remains open |
| Application actions | Submit/save draft/approval/correction/navigation and composed actions with server authority | Fixed request/approval/case-data actions exist; general composition/correction action binding remains open |
| Responsive/localized design | Authorable breakpoints, localization, theme tokens | Existing responsive renderers/themes are foundations; general authoring/runtime acceptance remains open |
| View migration | Reviewed explicit version migration and compatibility diagnostics across applications | Immutable pins and explicit reapplication exist; general migration remains open |
| Component extensions | Sandbox, capability grants, CSP and failure isolation; approved external data/actions | Not implemented; remains open |
| CMMN planning/lifecycle | Discretionary planning, repetition, event listeners, nested cases, normative suspension and termination | Restricted stages/human tasks/sentries/milestones, authorized native user events and finite sequential human-task repetition (S82), command hold and termination exist; full semantics remain open |
| Case file semantics | Native case-file items, data-triggered sentries and typed updates consistent with active work | Scalar case data exists separately from engine variables; normative semantics remain open |
| Workflow case tasks | Pinned workflow release, explicit result mappings, recovery and late-result fencing | Remains open |
| CMMN designer/exchange | Executable model authoring/preview, native 1.1 import/export preserving model/diagram IDs, source-linked diagnostics | Visual authoring of the admitted human-task/stage/milestone subset, generated native XML and exact deployed-source export are implemented; full notation/import/diagram exchange remain open |
| CMMN conformance | Clause-to-implementation matrix and executable lifecycle/concurrency/restart fixtures | Full conformance remains open; feature names do not establish conformance |

S56 remains authoritative for the two open parent epics, WM04 and UI02. Existing bounded tickets in the delivery register retain their exact evidence; their exclusion lists do not become implemented merely because another increment is delivered. Full completion must be demonstrated rather than inferred from aggregate test totals.

## Technical grounding

The conformance target is the [OMG CMMN 1.1 specification and machine-readable models](https://www.omg.org/spec/CMMN/1.1/About-CMMN/). The pinned Flowable 8.0.0 public CMMN API and its [CMMN guide](https://www.flowable.com/open-source/docs/cmmn/ch06-cmmn) are implementation inputs, not evidence that Orqix exposes or correctly implements every standard construct. The current admission validator explicitly refuses constructs outside its verified subset. That boundary must not be removed just to make an import appear successful; executable semantics, authorization and recovery must be delivered first.

The worklist implementation introduces V113, immutable published view snapshots, version-checked workspace assignment and append-only assignment history. It reuses the current task query and fixed Open task navigation. It does not add write/decision actions to list rows or broaden task data access. Verification and follow-up changes are appended here as work proceeds.


## Delivered worklist binding

V113 supports immutable workspace presentation assignments and append-only revision history. Only a current publisher can change a binding; task workers can read the pinned snapshot without view-library access. Assignment rejects drafts, foreign workspace pins, arbitrary task-data paths, incompatible columns and row write actions. A comparison-and-swap revision protects assignment and reset. The renderer reuses the existing authorized query, filtering, pagination, privacy-aware request title and task detail actions. The mock backend supports the same publication/pinning/reset path and persists its binding with the mock view library.

Verification: 18 selected backend tests pass (`WorklistViewsTest`, `CaseViewApiTest`, `ApplicationViewsApiTest`, `ArchitectureTest`, `SecurityMatrixTest`), including PostgreSQL/Flyway/security boundaries, coverage and packaging. A real Edge journey passes publication, assignment, authorized queue navigation, mobile accessibility and cleanup (`evidence/noninfra-worklist-real-browser.log`). PostgreSQL confirms migration 113 and denies the application role UPDATE and DELETE on `worklist_view_history`. The application was refreshed only after a database backup; no infrastructure topology was changed.

## Visual case model authoring

The case-definition form now builds human tasks, stages and milestones with stable generated IDs, workspace-group selection, literal required/manual controls and entry conditions referencing earlier items in the same stage. It supports nested stages, ordering, undo, removal with reference cleanup, validation and read-only native XML preview/download. Editing the model or changing its key/name invalidates the generated submission. The server admission/engine deployment boundary still validates every generated model. Existing deployed sources can be exported through the existing designer-authorized definition endpoint; the original XML is returned without reconstructing or renaming its IDs.

The exact generated fixture is compared to the frontend serializer and executed by `CaseLifecycleApiTest`: required manual work starts only through its authorized command, completion triggers the milestone, and the case completes through the engine. All six lifecycle tests pass with PostgreSQL and the runtime role. The serializer also rejects duplicate/generated-ID collisions, invalid group/key values, oversized plans, forward/cyclic dependencies and cross-stage references. This editor represents the admitted subset; it does not claim the complete CMMN graphical notation or lossless import of arbitrary models.

## CMMN conformance implementation map (partial; not certification)

The following map identifies the next engineering boundaries against the normative [CMMN 1.1 specification](https://www.omg.org/spec/CMMN/1.1/PDF). It is a starting map, not a complete clause audit. A full map and executable conformance fixtures remain acceptance work.

| Standard area | Current boundary | Required next acceptance |
|---|---|---|
| Planning tables/discretionary items (5.4.9) | Admission refuses planning tables; current work is definition-fixed | Authorized planning, applicability rules, task availability, required work, stale-version/race and restart fixtures |
| Process/case/decision tasks (5.4.10.5-7) | Admission refuses these definitions | Workspace-local pinned references, explicit typed mappings, durable dispatch/results, cancellation and late-result fencing |
| Repetition (5.4.11) | Admission refuses repetition rules | Repeated-instance counters, entry/repetition evaluation, completion/termination, bounded resource policy and restart fixtures |
| Lifecycle and state propagation (clause 8) | Engine-backed tasks/stages/milestones; application command hold is not native suspension | Native suspension/resumption propagation, listener/nested-case behavior and required/discretionary close invariants |
| Sentries (8.5) | Lexically scoped plan-item complete/occur events and entry/exit criteria | Case-file changes, if-parts, listeners, repeated event ordering and concurrent/restart evaluation |
| Behavior properties (8.6) | Constant required/manual controls | Full supported expression evaluation, repetition/planning interaction and suspension/termination fixtures |
| Exchange/notation | Exact admitted XML retained; generated stable IDs and deployed-source export | Native model/diagram ID round trips, source-linked diagnostics, full notation and conformant external-model fixtures |

No parent completion is recorded here: WM04 and UI02 remain open until their full S56 acceptance is met. The user-confirmed scope is retained.


## Access transition correction found by real browser verification

A worker who completes the last available case task may lose further case-read eligibility under the existing policy. The browser journey exposed an automatic expanded-plan refetch after that successful command. Case responses now include a current `canRead` hint; a false value stops automatic detail/plan/data reads and shows completion feedback. Requesters and readers retain their existing history access. This hint grants no server authority and leaves the read policy unchanged. Nullable/absent values are accepted for already stored idempotency snapshots. Backend and component regressions cover the transition.

Final source checks pass: 22 selected backend tests (case API/lifecycle, worklist, architecture and security), coverage and packaging; all 956 frontend tests across 133 files; TypeScript, lint, API drift, E2E types and 88 contract checks. This is not a fresh run of the entire backend/compiler test suite. Final browser acceptance and image evidence are recorded below.


## Final local acceptance - 23 October 2026

Both real Edge journeys pass against the refreshed PostgreSQL/Flowable/Keycloak application: `t-case-model-designer.spec.ts` and `t-worklist-views.spec.ts` (2/2). The model journey authors the CMMN, verifies byte-for-byte deployed-source export, starts as requester, activates/completes required manual work as an eligible worker, handles the worker read-access transition, and verifies the achieved milestone/reloaded history as requester. Desktop authoring and mobile history accessibility pass. The worklist journey verifies exact published assignment, authorized task navigation and desktop/mobile accessibility, then restores the previous binding. The shared browser fixture reports no console errors, uncaught exceptions or CSP violations.

At this acceptance checkpoint, the local application ran `orqix/orqix:case-designer-20261022`, source SHA-256 `91a9506584c3ecd28881c86ebc5b0ae907b6b14442928103c65b4c8c422f9ce4`. The manifest, current source and image label match (`evidence/noninfra-source-attestation.json`). A 38,147,707-byte database backup precedes the final refresh. Migration 113 is current and readiness passes; dependencies, volumes and infrastructure topology are retained. Production infrastructure/customer qualification is not claimed.

Evidence: `evidence/noninfra-final-backend.log`, `noninfra-final-frontend.log`, `noninfra-final-types.log`, `noninfra-final-lint.log`, `noninfra-final-api.log`, `noninfra-final-e2e-types.log`, `noninfra-final-contracts.log`, `noninfra-case-designer-real-browser.log`, plus the image build/rollout/source manifests. The initial browser attempt revealed the worker access transition described above; the corrected source passes the final run.

UI02e (workspace worklist presentation) and WM04g (visual authoring/export of the admitted case subset) are complete within these precise boundaries. The full original non-infrastructure objective is **not complete**: the remaining general-designer and full-CMMN work in the acceptance ledger is still required. No scope reduction or parent closure is recorded.


## 23 October follow-up: event-driven case work

[S82](S82-case-user-events-repetition.md) adds native role-authorized user events, occurrence sentries and finite sequential human-task repetition with visual authoring, immutable command replay, fresh eligibility, required-work guards and real browser/restart acceptance. It records pinned-engine completion/termination limitations explicitly. The local application is refreshed to the S82 image/V114; the S81 image evidence above remains the historical checkpoint. Full original scope, generalized CMMN semantics and the general application designer remain open.
