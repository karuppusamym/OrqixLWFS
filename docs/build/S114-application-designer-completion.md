# S114 - Application designer completion: reviewed pin migration, rich page controls, more chain steps, frame hardening

29 October 2026. Completes the UI02 product gaps that [S111](S111-application-designer.md) listed under "Not covered": version migration beyond per-pin updates, file and row controls on pages, request correction and case lifecycle / plan-item chain steps, and further hardening of the extension frame. Branch `claude/ui02-app-designer-fe6ab7`, which now also contains `claude/big-open-features-f6ffdd` ([S110](S110-cmmn-canvas-planning-workflow-tasks.md)) for its `plan` plan-item action; migration V193.

## Reviewed version migration of pins

Pins never move on their own. A designer reviews them per view or page in "Where this view is used" (view editor and page designer):

| Holder | Listed | Applying creates |
|---|---|---|
| Workflow draft | Request form pin (`inputUi.viewRef`) and approval step pins (`viewBinding`) of every workflow's current draft | A new draft revision through the normal draft save (`If-Match`, audit `workflow.draft.saved` with source `viewPinMigration`). Request pins re-project the view into the form exactly as the studio's "use this view" does; approval pins copy the new view version. A release still goes through publication. |
| Workflow release | The active release's pins | Nothing: shown as a fixed version for review |
| Case definition | The newest version of each case definition that pins a case view | A new case definition version with the same CMMN model, data fields and workflow task mappings and the new view pin. Existing cases keep their definition version |
| Worklist | The workspace worklist assignment | A new worklist revision through the worklist assignment |
| Page draft | View components of other pages' drafts | A new page draft revision through the view library save |
| Published page | View components of the published page version | Nothing: shown as a fixed version |
| Page route | Routes that pin this page | A new route revision through route assignment |

`GET /views/{viewId}/pins?targetVersion=N` lists every holder with its pinned versions, the revision or version it expects, a status and what applying would do. Ready holders are those where the target version passes the holder's own checks: `RequestViewCheck.matches` on the re-projected workflow, `ApprovalViewCheck.compatibility`, the case view/data-field check, the worklist view rules, the page draft checks or the page routing checks. Otherwise a holder is current, newer, blocked (with the diagnostics), or readOnly. A case definition is also blocked when a workflow task's pinned release is no longer that workflow's active release, because a new version would silently move it; that change belongs in the case designer. The response carries a SHA-256 hash of everything the review showed.

`POST /views/{viewId}/pin-migrations` takes the target version, the reviewed hash, the selected holders and a reason. The server builds the plan again and refuses it with 409 if the hash differs, so a repeated or stale request never applies. Each selected holder must be ready. All selected holders then move in one transaction, each through its own service as the caller, so its permission (workflow edit for drafts and cases, workflow publish for the worklist and routes), its expected revision and its audit apply unchanged; any failure rolls back every holder. An append-only `application_view_pin_migration` row (V193) records who moved which holders to which version, with the hash, reason and outcomes, and the audit action `view.pins-migrated` is written. `GET /views/{viewId}/pin-migrations` lists the last 50.

The panel shows each holder's status, diagnostics and effect, lets the designer select ready holders, and opens a review dialog that lists the effects and requires a reason. On a 409 it reloads the plan for a new review. Earlier updates are listed below.

## File and row controls on pages

Request components whose pinned view has file or row fields now render with the request form's own renderer: `FormRenderer` with the workflow's active start form, limited to the fields the view binds. Choosing a file stages it through the existing upload operation, with its type, size and virus-scan checks. Rows use the existing row-list control with its row limits. Before a submit step the page runs the form's own validation and stops the chain locally, naming what to fix. The server then repeats every check: `bound()` limits values to the view's fields, and the run start validates the schema and attaches only uploads that the caller staged. Saved drafts keep no file references (staged uploads belong to the submit that attaches them), and the server drops them too.

Approval components show file fields as attached and row fields as a read-only table. Case views still cannot use file or row controls, because case data has neither.

## More chain steps

| Step | Target | Server operation |
|---|---|---|
| `requestCorrection` | Approval view that declares the request-correction action | `correctTaskInput` (S42): only the fields the workflow step declares correctable, as the holder or an operator, with a reason; the new task version carries into a following decision |
| `suspendCase`, `resumeCase`, `closeCase` | Case view | Case lifecycle with the carried case version and the component's reason |
| `startPlanItem`, `planDiscretionaryItem` | Case view + CMMN element id | Case plan-item `start` / `plan` (S110) |
| `completePlanItem` | Case view + CMMN element id | Case task completion of the element's open human task |

Plan-item steps name the CMMN element (`planItem`). When the step runs, the server reads the case plan with the caller's own access and picks the one item with that element id on which the caller may take the action now; none is `TASK_NOT_OPEN` and more than one is refused. Each step uses a key derived from the chain's Idempotency-Key, and the case version carries from step to step, including after save case data. Shape rules: a correction appears at most once and before the decision (and after any claim); no case step follows closing the case; plan-item steps need an element id and other steps may not have one. The approval component shows correction inputs only for the fields the server reports correctable on the task now. The case component's reason box feeds case actions too. "Request correction" here is the holder's correction of the request; the runtime still has no send-back to the requester.

## Extension frame hardening

- The frame document already had `default-src 'none'`, `connect-src`, `form-action`, `base-uri`, `frame-src`, `worker-src` and `object-src 'none'` and `sandbox allow-scripts` without `allow-top-navigation*`, `allow-forms`, `allow-popups` or `allow-same-origin`. It now also carries `<meta name="referrer" content="no-referrer">`, and the server response adds `Permissions-Policy` (camera, microphone, geolocation, payment, USB, fullscreen, display capture, clipboard, WebAuthn, autoplay all denied), `Referrer-Policy: no-referrer` and `Origin-Agent-Cluster: ?1`. The host iframe repeats the same list in its `allow` attribute.
- Granted data never appears in the frame address; it arrives only by postMessage to the frame's own window.
- The bridge reports `unloading` on `beforeunload` and `pagehide`, then drops its references to granted data, handlers and pending requests and ignores further messages. The host tears the frame down at once on `unloading` or on a second `ready` (a new document), in addition to the existing second-load, crash, start-up timeout and heartbeat checks, so it never posts data to whatever document comes next.
- The watchdog stays on the host side (start-up timeout, heartbeat, rate limit). In the real Chrome check, a 2.5-second busy loop in a sandboxed frame did not block the page's own timer, so the browser ran that frame outside the page's event loop and the heartbeat can remove it.

## Data and contracts

- V193: append-only `application_view_pin_migration` (target version foreign key to the published view version, plan hash, reason, applied items).
- OpenAPI: `listViewPins`, `listViewPinMigrations`, `migrateViewPins` (permission `workflow.edit + underlying action permission`, audit `view.pins-migrated`) with `ViewPinInventory`, `ViewPinItem`, `MigrateViewPins`, `ViewPinMigration`; `ChainAction` gains the seven steps and `ChainStep` gains `planItem`.
- Catalog: a public `ViewPins` service finds and moves catalog pins through `WorkflowService.saveDraft`, `WorklistViews.assign` and `ApplicationViews.save`. Small visibility changes: `RequestViewCheck.matches` and a new `project` method (the server port of the studio's request-view binding), `ApprovalViewCheck.compatibility`, `WorklistViews.validate`, `ApplicationViews.check`.
- Cases: `CaseService.viewUses`, `reviseView` (through `createDefinition`) and `openTaskId` are appended; no existing case behavior changed.
- Runtime: `PageActions.correct` delegates to `TaskService.correctInput`.
- The `apps` module's `PinMigrations` service adds case and route holders, the plan hash, the all-or-nothing apply and the record.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Full backend `verify` on PostgreSQL/Flowable (Testcontainers), all modules, with coverage gate and packaging | Build success: compiler 640/640; application 1037 run, 0 failures, 4 skipped (the opt-in live AI and spike tests) | `evidence/s114-backend-full.log` |
| New `PinMigrationsApiTest`: request and approval pins in workflow drafts move after review (projected draft then passes release publication), published page and release stay fixed, foreign 404 / no read 403, wrong or reused hash 409, not-ready or reasonless 422, blocked approval version with its diagnostic; worklist and page draft move together or not at all when the publish permission is missing; page routes move to a new page version; case definitions get a new version while existing cases keep theirs, and an incompatible case view is blocked | 3/3 | `evidence/s114-backend-full.log` |
| New `PageCompletionApiTest`: correction refused for a non-correctable field and without a reason, correction + approve with the carried task version and audit; case start/complete plan item, hold and resume refused for a worker and for a stale version, run for the operator, close, nothing to start after close; shape rules for plan items and closing; page with a file field: drafts keep no file, someone else's staged upload refused by the run start, own upload attached to the new run | 3/3 | `evidence/s114-backend-full.log` |
| Existing page, chain and extension API tests (frame now also asserts form-action/base-uri, Permissions-Policy, no-referrer, referrer meta and the unloading bridge), `SecurityMatrixTest` (new operations as foreign principals), `DatabaseTest` (append-only V193 table), `ArchitectureTest` | Pass | `evidence/s114-backend-full.log` |
| Focused frontend: chain step rules, file/row rules, correction placement; chain editor; runtime with file and row controls (local validation stops the chain before the server) and correction inputs only for correctable fields; pin migration panel (status, review dialog with required reason, apply, history, stale plan 409); host teardown on unloading and second ready, `allow` list; bridge unloading and referrer meta; jsdom axe on the runtime and migration panel | 36/36 across 9 files (11 new tests) | `evidence/s114-frontend-full.log` |
| Full frontend suite (including the merged S110 tests) | 1237/1237 across 172 files | `evidence/s114-frontend-full.log` |
| Typecheck, full lint, generated API drift (`api:check`), contract check | Pass; 88/88 contract checks | `evidence/s114-static.log`, `evidence/s114-contracts.log` |
| Real Chrome (built-in browser) against the mock-mode Vite dev server | Pass: publish a new view version, review and apply its page-draft pin with a reason, history; the live extension frame with the `allow` list and referrer meta; a self-navigating frame reports unloading before its second load; a busy frame does not block the page's timer | `evidence/s114-browser.md` |

## Real-stack acceptance specs

Four Playwright specs cover the UI02 journeys for the real-stack run (`tests/e2e/specs`, helpers in `support/views.ts`):

| Spec | Journey | Mock mode |
|---|---|---|
| `t-ui-02g-page-journey.spec.ts` | Designer composes a page in the page designer (palette, inspector, chain, action bar), publisher reviews, publishes and pins a route, requester submits through the chain, viewer sees the form read-only and the server refuses the chain, outsider gets 404 | Runs (outsider check real stack only) |
| `t-ui-02g-action-chains.spec.ts` | Task page: an operator who may read but not approve is stopped at the claim with later steps skipped; the manager's reject without a reason stops after the claim with the navigation skipped; the held task is then approved and the navigation runs | Runs |
| `t-ui-02g-extensions.spec.ts` | Register and submit two extensions in the registry, approval by the administrator (not the submitter), server-served frame headers (policy, `SAMEORIGIN`, Permissions-Policy, no-referrer), granted data in the sandboxed frame, the granted chain run through the bridge, a slow extension's start-up fallback, revocation fallback without a frame | Skipped |
| `t-ui-02h-pin-migration.spec.ts` | Workflow draft pinning version 1, version 2 published, reviewed update with a reason, migrated draft verified and then published as a release, release shown as a fixed pin | Skipped |

The two mock-capable specs pass against the mock build (`evidence/s114-e2e-mock.log`); the specs typecheck. Writing them found that a chain's outcome disappeared when its own claim changed the task version (the runtime canvas was keyed by that version); the canvas is now keyed by route revision and context only.

## Not covered

- No real-stack (Keycloak/PostgreSQL/Flowable) browser journey and no local image refresh; the session that started this one runs the merged live-stack journeys. The server-served frame headers are verified by backend tests, not in a real browser.
- Pin migration does not publish workflow releases or page versions: it prepares drafts, and publication keeps its own review and approval. Older case definition versions, inactive workflow releases and older page versions are not listed. A case whose workflow task pins moved since its last version must be revised in the case designer.
- Mock mode lists page, route and worklist pins only (mock workflows and cases do not pin views), and applies them one by one rather than in one transaction.
- Chains still never undo earlier successful steps. Request correction is the holder's correction of declared fields; there is no send-back to the requester.
- A plan-item step acts on exactly one available item with the named element id; with several (repetition), the chain stops and the person uses the case page.
- Self-navigation by an approved extension cannot be fully prevented: browsers dropped CSP `navigate-to`, and a sandboxed frame may always navigate itself. The host stops sending data as soon as the bridge reports unloading, but data already granted could leave in that navigation's address. Admin review of the source remains the control for that. Whether a busy frame runs outside the page's event loop depends on the browser's isolation of sandboxed frames; the heartbeat removes it either way once the page gets time.
- Extension bundles remain single inline scripts without imports, fonts or network access.

## Status

UI02 product scope is complete within this boundary. UI02 stays open under [S56](S56-adaptive-cases-ui-designer-scope.md)'s status rule until the merged branches pass the real-stack authoring-to-runtime journey, including the server-served extension frame.
