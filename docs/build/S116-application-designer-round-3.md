# S116 - Application designer round 3: send back, compensation, occurrence choice, multi-file bundles, older pins

29 October 2026. Closes the S114 "Not covered" product gaps that have a sound design, and fixes the two UI02 E2E specs that failed in the first live-stack run. Branch `claude/ui02-app-designer-fe6ab7`, which now also contains `claude/big-open-features-f6ffdd` (WM04, UI02 and MIG11 integrated, merge 4159e2e); migrations V194 and V195.

## Send back to the requester

The holder of an approval task can return the request to its requester for the fields the step declares correctable. That is the same declaration as the holder's own correction (S42), so a workflow author still decides what may change.

- **Send back:** `POST /tasks/{taskId}/send-back`, with the expected task version, a reason and an Idempotency-Key. Only the eligible assignee may send it, and only while the task is open and the request is not suspended. At most one send-back is open per task (a partial unique index, V194).
  - While it is open, the decision and the holder's correction are both refused (`TASK_NOT_OPEN`, with a message naming the send-back).
  - The task version changes, the run timeline shows `TASK_SENT_BACK`, and `task.sent-back` is audited.
- **Withdraw:** `POST /tasks/{taskId}/send-back/withdraw`, by the holder or an operator, with the expected version (`task.send-back.withdrawn`).
- **List:** `GET /runs/{runId}/send-backs` returns a request's send-backs for its requester, participants and run readers. Current values of the returned fields are included only for callers who may read the request data.
- **Answer:** `POST /runs/{runId}/send-backs/{id}/answer` is for the requester only, with the task version shown and an Idempotency-Key.
  - It goes through the shared correction core that S42 now uses too. Only the returned fields, as single values, are accepted; they are validated against the pinned form, sent through the engine, and recorded as `INPUT_CORRECTED` with before and after values. The task version changes, so a decision prepared on the old data is refused.
  - The send-back closes as answered, and `task.send-back.answered` is audited.
- **Screens:**
  - The task page shows "Send back to the requester" to the holder. While the send-back is open it shows a "Waiting for the requester" notice with Withdraw, and it hides the holder's correction form.
  - The request page shows "Sent back for corrections" with inputs for the returned fields and an optional note.
- **Chain step:** `sendBack` targets an approval view that declares the request-correction action, takes the component's reason and carries the task version. A chain may contain it once and never together with a decision.

## Chain compensation

Each undoable step may declare its own inverse in `compensate`. The fixed pairs are:

| Step | Inverse |
|---|---|
| submit | cancelRequest |
| saveDraft | restoreDraft |
| claim | releaseTask |
| saveCaseData | restoreCaseData |
| suspendCase | resumeCase |
| resumeCase | suspendCase |
| sendBack | withdrawSendBack |

How it behaves:
- **Default:** no compensation. Every other step cannot be undone, and both the editor and the server reject an undo declared on it.
- **When it runs:** a later step fails, and the runner runs the declared inverses of the succeeded steps in reverse order. Each runs as the caller through the same operation as its public action (operator cancel, task release, case data update with the previous values, case lifecycle, send-back withdraw, or putting the caller's own earlier draft back), so it is authorized again and can itself be refused.
- **What gets recorded:** each step result carries `compensation {action, status, detail, problemCode}`, and the `page.chain.run` audit counts compensated steps.
- **Requests:** undoing a started request needs operator rights. For a requester it is recorded as not undone.
- **Editor:** a checkbox per undoable step ("If a later step fails: …"), and "cannot be undone" text for the rest.
- **Outcome:** shows "undone" or "not undone" per succeeded step of a stopped chain.

## Repeated plan items

Plan-item steps take an `occurrence` setting:

| Value | When several items of the element are available |
|---|---|
| `single` (default) | The step stops |
| `newest` | It takes the most recently created one |
| `choose` | It takes the one the person picked |

For `choose`, the action bar shows a "Which {element}" list read from the case plan with the caller's access. The choice is sent as `ChainInput.planItems`, and the server accepts it only if it is one of the items available for that action.

## Multi-file extension bundles

- **Contents (V195):** a version may now carry up to 30 files (JavaScript modules, stylesheets, WOFF/WOFF2 fonts and PNG/JPEG/WebP/GIF images). Names are flat and SVG is excluded. Each file holds 1 byte to 256 KiB, with 1 MiB in all.
- **Hashes:** each file's SHA-256 is recorded at submission. The version hash covers the inline source, the entry and every file hash, so an administrator approves exactly those bytes. The review panel lists every file with its hash, and files are immutable.
- **Loading:** the frame document loads the named entry module with an `integrity` attribute.
- **Serving:** a sandboxed frame's own requests carry no session, so the frame endpoint mints a twelve-hour signed path for the version (HMAC with a per-version random key). The file endpoint (`getUiExtensionFile`, no session, `extensionFileToken`) serves only approved or pending versions, readable cross-origin with `nosniff`. Revoked versions and forged or expired paths get 404.
- **Frame policy:** scripts, styles, fonts and images are allowed only from that exact signed prefix. `connect-src` stays `'none'`, and relative module imports resolve only inside the bundle.
- **Mock mode:** multi-file versions show the fallback, because mock mode cannot serve files.

## Older pins in the migration review

The pin review now also lists, read-only:
- inactive workflow releases that still pin the view ("Release N (not active)");
- older case definition versions ("Older case version").

Owners can see what will not move. Requests and cases already on those versions keep their view.

## Fixes from the first live-stack run

- **`t-ui-02h-pin-migration`:** a strict-mode locator also matched the success alert's list item. The pin list is now named "Pins of this view", and the spec scopes to it.
- **`t-ui-02g-extensions`:** both extensions showed the start-up fallback. A local reproduction with the server's frame document and headers in Playwright's Chromium explains it:
  - Alone, the badge sent ready in 57 ms.
  - Next to the busy-looping extension, the host page's own timer did not tick for 8 s. The same-site sandboxed frames ran in the page's process, so the overdue start-up timeouts fired together with the late ready messages.

  The spec now uses an extension that navigates itself away (fallback "navigated away"). That exercises the S114 unloading signal and doesn't stall the host. This corrects S114: a busy extension can delay the whole page when the browser runs the frame in the page's process. The heartbeat removes the frame once the page gets time again. Process isolation of sandboxed frames is browser-dependent.
- **Frame response:** it no longer sends `Origin-Agent-Cluster`, which only produced a console warning there.

## Data and contracts

- **V194:** `task_send_back`, with only the closing columns updatable and one open send-back per task.
- **V195:** `ui_extension_file` (immutable), and `ui_extension_version.entry` and `file_key`. The source check now allows an empty source when an entry module is named.
- **OpenAPI:**
  - New operations: `sendTaskBack`, `withdrawTaskSendBack`, `listRunSendBacks`, `answerSendBack` and `getUiExtensionFile`.
  - Schema changes: `TaskDetail.form.sendBack`; `ChainStep.compensate` and `occurrence`; `sendBack` in `ChainAction`; `ChainInput.planItems`; `compensation` on chain steps; bundle `files` and `entry` on submit and version.
  - The `caseVersion` holder type is added, and extension capabilities may declare up to 15 actions.
  - Audit actions `task.sent-back`, `task.send-back.withdrawn` and `task.send-back.answered` are added, and `page.chain.run` gains a `compensated` count. The contract check accepts the `extensionFileToken` qualifier.
- **Runtime:** a new `SendBacks` service. The S42 correction now goes through a shared `applyCorrection` core, and `PageActions` gains send-back, withdraw, release and cancel.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Full backend `verify` on PostgreSQL/Flowable (Testcontainers), all modules, with coverage gate and packaging | Build success: compiler 641/641; application 1061 run, 0 failures, 4 skipped (the opt-in live AI and spike tests) | `evidence/s116-backend-full.log` |
| New `SendBackAndCompensationApiTest`: send-back only by the holder, one open at a time, decision and holder correction refused while open, requester list with values and `canAnswer`, outsider 404, non-requester 403, undeclared field 422, answer changes the request and a stale decision is refused, operator withdraw; chain shape rules (only a step's own inverse, no send-back with a decision), a failed reject releases the claim and records it, a send-back chain, case data restored after a failed later step, a chosen occurrence must be available | 3/3 | `evidence/s116-backend-focused.log` |
| New `ExtensionBundlesAndOlderPinsApiTest`: SVG, unknown entry and empty bundle refused; files hashed and listed; the frame loads the entry module with its integrity and a policy limited to the signed prefix; the signed path serves files without a session, cross-origin readable; forged path, unknown file and revoked version 404; inactive release and older case version listed read-only | 2/2 | `evidence/s116-backend-focused.log` |
| New `ChainOccurrenceTest` (single, newest, choose, none available) | 4/4 | `evidence/s116-backend-focused.log` |
| Focused rerun of the page, chain, extension, pin-migration and holder-correction tests, `SecurityMatrixTest` (new operations as foreign principals), `DatabaseTest` (append-only V194/V195 tables), `ArchitectureTest` | 38/38 across 11 classes | `evidence/s116-backend-focused.log` |
| Frontend: chain rules, editor undo toggles and "cannot be undone" text, occurrence picker and per-step undo outcome, send-back panel and requester answer, multi-file submission and review; jsdom axe | 12 new tests, all pass | `evidence/s116-frontend-full.log` |
| Full frontend suite | 1252/1252 across 175 files | `evidence/s116-frontend-full.log` |
| Typecheck, full lint, generated API drift (`api:check`), contract check, E2E typecheck | Pass; 88/88 contract checks | `evidence/s116-static.log`, `evidence/s116-contracts.log` |
| E2E specs against the mock build (`E2E_MOCK=true`) | 3 passed (page journey, action chains, send-back and undo), 3 skipped as designed (server-served frames and pins) | `evidence/s116-e2e-mock.log` |
| Local reproduction of the live extension failure with the server's frame document and headers in Playwright's Chromium | Badge alone ready in 57 ms; next to a busy frame the host timer stalls 8 s; a self-navigating frame reports unloading before each new document | this record |

## Not covered

- No real-stack browser run of this round; the session that started this one runs the merged live-stack journeys (`t-ui-02i-send-back-and-undo`, `t-ui-02i-extension-bundle` and the fixed `t-ui-02g-extensions`, `t-ui-02h-pin-migration`).
- The requester is not notified of a send-back beyond the request page and timeline; there is no deadline or reminder for an answer.
- Compensation is best effort: an inverse can itself be refused (recorded as not undone), and some steps (decisions, closing a case, completing or starting plan items, corrections, planning) have no inverse. Undoing a started request needs operator rights.
- Choosing among several open occurrences is unit-tested; the integration test covers a single available occurrence, because the CMMN repetition fixture does not keep two occurrences available at once.
- Extension files are served from the application's own origin under a signed path; there is no separate extension origin, so frames are not process-isolated by the browser's site rules.
- Mock mode does not run multi-file bundles.

## Status

UI02 product scope is complete within this boundary. UI02 stays open under [S56](S56-adaptive-cases-ui-designer-scope.md) until the merged branches pass the real-stack journeys.
