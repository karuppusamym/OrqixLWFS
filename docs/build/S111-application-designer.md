# S111 - Application pages, composed actions and guarded extensions

29 October 2026. UI02g delivers the three general-designer parts that [S83](S83-view-presentation.md) left open: whole-page composition, action chaining and sandboxed custom extensions. It extends the existing workspace view library (V71/V113) and its immutable version pins; it does not touch the CMMN case designer or case runtime, which [S56](S56-adaptive-cases-ui-designer-scope.md) WM04 work is changing in parallel. Branch `claude/ui02-app-designer-fe6ab7`; migrations V190 to V192.

## Application pages

An application page is a new `page` kind in the view library. It uses the same draft revisions, review, publication and immutable `application_view_version` rows as request, approval, case and worklist views, so a published page version never changes when its draft is edited.

A page has a title, a context (no record, a task or a case), a layout (one column, main with side panel, or two equal columns) whose regions are fixed by the layout, and a breakpoint at 640, 768 or 1024 pixels below which the regions stack. Each region holds up to 12 components and a page up to 24:

| Component | What it shows | Data source |
|---|---|---|
| Heading, text | Plain authored text | None |
| Reusable view | An exact published request, approval, case or worklist view version | Request: a form submitted to a named workflow. Approval: the context task's request. Case: the context case data. Worklist: the caller's assigned tasks |
| Data list | Up to 50 rows of assigned tasks, claimable tasks, my requests or cases, with columns from a fixed list per source | The existing authorized list operations |
| Record details | Fixed task summary fields, `request.*` fields of the context task, or `case.*` fields | The existing task and case read operations |
| Action bar | Buttons for the page's chains | Chain run operation |
| Extension | An approved extension version in a sandboxed frame | Server-filtered granted bindings |

Any component may be shown only when a context binding equals a value. This is presentation only; hiding a component never grants or removes access.

Studio authoring is the view editor for the page kind: a component palette with a target region, an outline with move up/down, move to the other region and remove, a property inspector per component (exact view version selection, a "newer version available" notice with an explicit "use version N" pin update, workflow selection for request views, list columns, details fields, granted extension data and chains, conditional visibility), a chain editor, a bindings and problems panel, desktop/tablet/mobile preview that applies the authored breakpoint, undo of the last 50 edits, and a review dialog listing regions, components and chains before publication. A publisher pins an exact published version to a route (`/w/:ws/apps/:slug`, optionally `/:taskOrCaseId`) from the same screen. Route changes use a revision check and are kept in an append-only history. An Apps navigation entry lists the routed pages a member can open.

The runtime route reads the routed version's definition, its pinned view definitions and extension uses, and grant hints. It never returns business data. Lists, details and embedded views read through the existing task, request and case operations with the caller's own permissions, so a member who cannot read or act sees an error or a read-only control, not data. A request form is editable only when the caller can start its workflow; the server refuses the submit anyway if not.

Server validation is authoritative at save, publication and route assignment: every pinned view version must exist in the workspace and suit the page context (approval views need a task page, case views a case page, pages do not nest, file and row controls are not rendered on pages), request components must name a workflow with an active release whose input schema matches the view's bindings, types and required flags, and list columns, details fields and bindings must come from the fixed lists.

## Composed actions

A chain is an ordered list of one to five steps from the fixed allowlist: submit request, save draft, claim task, approve, reject, save case data and navigate. Chains are part of the page definition, so the published version pins them exactly. `stopOnFailure` must be true; navigation can only be the last step; a chain has at most one approve or reject, and a claim must come before it. Each step except navigation targets a view component whose pinned view suits the step and declares the matching view action (submit, saveDraft, approve, reject or saveData). These checks run in the editor, at save and at publication.

A chain runs only through `POST /app-pages/{slug}/chains/{chainId}/run`. The server reloads the routed, pinned page, refuses a stale page revision, and runs only a chain that is placed on an action bar or granted to the calling extension component. Each step calls the same service as the public operation (run start, task claim and decision, case data update) as the caller, in its own transaction, with a key derived from the request's Idempotency-Key. Membership, workflow start permission, task eligibility, separation of duties, versions and case edit rights are therefore checked again per step. Values are restricted to the fields the pinned view binds. The first failed step stops the chain; later steps are reported as skipped. Earlier successful steps are not undone, and the result lists exactly which steps ran. Replaying the same key returns the recorded result without running anything again; reusing a key with a different body is refused. A claim carries the new task version into the following decision. Save draft stores the caller's own values for that page component (V190), and a successful submit removes the draft.

Request correction is not in the chain allowlist: the runtime has no send-back operation, consistent with the existing approval-view restriction (S79).

## Guarded extensions

Designers register an extension and submit immutable versions (V191): a JavaScript bundle of at most 64 KiB, its SHA-256, a height and declared capabilities, which are the bindings it may receive (task summary fields, `request.*`, `case.*`) and the chain actions it may request. Sources with script or comment markup are refused. A workspace administrator who did not submit the version approves or rejects it with a note, and may later revoke an approved version. The database also rejects a decision by the submitter. Decisions are appended to a history table, and the application role cannot change a version's source, hash or capabilities.

A page may grant an extension component only a subset of the declared bindings (also limited to its context) and only chains whose every step action is declared. A pending version can be saved in a draft, but publication and route assignment require an approved version.

At runtime the extension runs in an iframe with `sandbox="allow-scripts"` only, so it has an opaque origin and cannot reach the page, cookies, storage or same-origin APIs. The server serves its frame document from `GET .../ui-extensions/{id}/versions/{v}/frame`: approved versions to members, and pending versions only to designers and administrators for review. Its own policy is `default-src 'none'`, `script-src` limited to the SHA-256 of the fixed bridge and of the stored bundle, `connect-src`, `form-action`, `frame-src`, `worker-src` and `object-src` set to `'none'`, `frame-ancestors 'self'` and `sandbox allow-scripts`. That response alone is framable from this origin; every other response keeps `X-Frame-Options: DENY`.

The extension talks to the host only through the bridge's postMessage protocol (`orqix-extension/1`): `onData`, `run(chainId)` and `resize`. The host accepts messages only from its own frame window, parses them strictly, answers `run` only for granted chains, and allows at most five runs in ten seconds. The server still authorizes each run as above and refuses runs from a revoked version. The data the frame receives comes from `GET .../components/{componentId}/data`, which reads the context with the caller's own task or case access and returns only the bindings granted to that component.

The host isolates failures. If the bridge does not start within five seconds, reports an error or unhandled rejection, misses a heartbeat, or the frame navigates away from its document, the host removes the frame and shows a fallback with a retry button; the rest of the page keeps working. A revoked or missing version shows the fallback without loading a frame. Mock mode builds the same document as `srcdoc` with the policy in a meta tag; a test asserts the frontend bridge copy is byte-identical to the server's.

## Data and contracts

- V190: `page` view kind; `application_page_route` (revision-checked pin of one exact page version per slug) and append-only `application_page_route_history`; `application_page_draft`.
- V191: `ui_extension`, `ui_extension_version` (immutable bundle, hash and capabilities; only decision columns updatable), append-only `ui_extension_decision`.
- V192: `application_page_chain_run` for idempotent chain replay.
- OpenAPI: `LibraryViewDefinition` (form view or `PageDefinition`) on the view library operations, `page` in `ViewSummary.kind`, 12 new operations under `/app-pages` and `/ui-extensions`, all with permission annotations and audit actions (`page.route-assigned`, `page.chain.run`, `extension.created`, `extension.version.submitted`, `extension.version.decided`; `page.draft.saved` is written by the save draft step). The contract check accepts the permission phrase "underlying read permission" for the component data operation.
- A new `apps` application module owns routes, the runtime payload, drafts, component data and chain runs. It depends on catalog (`ApplicationPages`), runtime (a narrow `PageActions` facade over the existing task and run services) and cases (`CaseService`). Nothing depends on it.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Selected backend `verify` on PostgreSQL/Flowable (Testcontainers), with coverage and packaging: new `ApplicationPagesApiTest`, `PageChainsApiTest`, `UiExtensionsApiTest`; existing `ApplicationViewsApiTest`, `WorklistViewsTest`, `CaseViewApiTest`, `ArchitectureTest` (Modulith), `SecurityMatrixTest` (every operation as foreign principals, no state change), `DatabaseTest` (empty-database migration and append-only privileges for the new tables) | 30/30 pass, build success; `UiExtensionsApiTest` 2/2 again after the last frame edits | `evidence/s111-backend-verify.log`, `evidence/s111-backend-extensions-final.log` |
| Page versioning and pinning: draft edits and new publications never change a routed version; stale route revisions 409; removal; history; publisher-only assignment; viewer read-only | Pass | `ApplicationPagesApiTest` |
| Incompatible targets refused at save and publish: approval on a non-task page, request without workflow, schema mismatch, missing version, step against the wrong view kind or an undeclared view action, early navigation, two decisions, `stopOnFailure` false, nested pages, routing a non-page | Pass | `ApplicationPagesApiTest` |
| Chain authorization: unbound field refused, viewer without start permission refused at the run start, ineligible approver stopped at claim with later steps skipped and the task still open, eligible manager claim + approve + navigate, idempotent replay, key reuse 409, stale revision 409, unplaced chain 403, non-extension source 403, missing context 422, missing key 400, audit rows | Pass | `PageChainsApiTest` |
| Extension approval and grants: markup and undeclared capabilities refused, pending preview for reviewers only, sandbox policy (two script hashes, no same-origin, no eval, no network), `SAMEORIGIN` only on the frame and `DENY` elsewhere, submitter cannot decide, status conflicts, pending blocks publication, grants beyond declared refused, server-filtered data with the caller's access, revocation blocks data, frame, chain runs and new route pins | Pass | `UiExtensionsApiTest` |
| Focused frontend: page model, designer (palette, reorder, undo, responsive preview, problem inspection, save, review, publish, route pin, new page), chain editor, runtime (draft, submit and navigation, read-only refusal, claim + approve, stop at the first failure), extension host (sandbox attributes, granted data only, granted chains only, foreign messages ignored, crash, start-up timeout, heartbeat, navigation away, retry), registry (register, submit, approve, revoke), bridge parity and frame policy; jsdom axe on designer, chain editor, runtime and registry pages and the host fallback | 25/25 new tests across 7 files; 66/66 across 17 files with the touched view, shell, auth-client and color tests | `evidence/s111-frontend-focused.log` |
| Full frontend suite | 1213/1213 across 168 files | `evidence/s111-frontend-full.log` |
| Typecheck, full lint, generated API drift (`api:check`), contract check | Pass; 88/88 contract checks | `evidence/s111-static.log`, `evidence/s111-contracts.log` |
| Real Chrome (built-in browser) against the mock-mode Vite dev server | Pass: draft save and restore after reload; task page with the live sandboxed extension showing only granted data; the extension's own button ran claim + approve + navigate through the bridge and server chain; revocation shows the fallback with no frame; designer flags the revoked version; no console errors | `evidence/s111-browser.md` |

Fixes made during verification: row locks on `ui_extension` needed an UPDATE privilege on one column; the default `X-Frame-Options: DENY` writer had to exclude the frame response; component titles were promoted to `h2` after an axe heading-order finding; a seeded chain with a navigate step needed the extension to declare `navigate`; the mock layer stopped importing the API client so an existing auth test keeps its module mock; the frame stylesheet uses system colors instead of hex literals; the demo extension declared a global `status` that collides with `window.status` (found in the browser, where the sandbox isolated the crash as designed); the host now posts data to the frame only when the granted content changes.

## Not covered

- No real-stack (Keycloak/PostgreSQL/Flowable) browser journey and no local image refresh were run for this increment; the browser check used mock mode. The server frame endpoint, its policy and framing headers are verified by backend tests, not in a real browser.
- No fresh full backend or compiler suite run; the selected classes above ran.
- Version migration of existing pins is limited to the page editor's explicit "use version N" update; there is no bulk migration of workflow, case or route pins.
- Pages cannot render file or row controls, request correction is not a chain step, case lifecycle and plan-item actions are not chain steps, and chains never undo earlier successful steps.
- A sandboxed frame can still navigate itself; the host then removes it, but data already granted to an approved extension could leave with that navigation. Admin review of the source is the control. A busy loop in a frame that the browser runs in the page's process can delay the page before the heartbeat removes it.
- Extension bundles are single inline scripts without imports, fonts, images other than `data:` URLs, or network access.

## Status

UI02g is complete within this boundary. UI02 itself stays open: S56's status rule needs end-to-end acceptance on the real stack (a real-browser authoring-to-runtime journey against PostgreSQL/Flowable/Keycloak, including the server-served extension frame), and version migration beyond explicit per-pin updates. Full CMMN remains WM04.
