# S71 - Section and handler frames; call failures as section errors (EX06/EX08 follow-up to S59)

S59 left two items with the most user-facing value open: sections and handlers were only edited in the inspector and listed as text on the run diagram (no frames on either canvas), and a section's error route could catch only a "Raise an error" step, never a failing system call. This slice delivers both, bounded, without changing the flat workflow model or any default behavior. No migration was needed (V90-V92 unused).

## 1. Frames on the Studio canvas and the run diagram

Design (decided here; no user input was available):

- A frame is a labelled rectangle drawn **behind** the member steps of one section or handler: the bounding box of the steps' current positions and measured sizes plus 18 px padding and a 30 px label band on top. It is rendered through React Flow's `ViewportPortal` with `z-index: -1` inside the viewport's stacking context, so it pans and zooms with the steps, sits under steps and routes, and follows a step while it is being dragged.
- Frames are **decoration, not containers**: they cannot be selected, dragged, focused or connected (`pointer-events: none`, no React Flow node), are not part of the definition and never nest (a step belongs to at most one subprocess, so frames never contain each other). Membership is still edited in the request form inspector ("Sections and handlers"). Moving a step out of a frame's area does not change membership; the frame simply grows to follow the step.
- Sections have a solid border on a soft info tint; handlers a dashed border on a soft purple tint (theme tokens only).
- Label: `Section: <name> · <time limit P5D → step> · <error CODE / any error → step>` or `Handler: <name> · on signal x / after P3D / on error CODE · stops the request / runs alongside`.
- Run diagram: the same frames, with state from `scopeVisits`: **active** (inside now: thicker waiting-colored border), **passed** (left: caption says how and where the request continued, e.g. "left because a step raised an error, continued at ..."), **idle** (never entered/started: dimmed).
- Accessibility: frames are `aria-hidden`; the same information stays available as text (inspector; the run diagram's "Sections and handlers" list). On the Studio canvas each member step's accessible name now ends with ", in the section X" / ", in the handler X".
- Workflows without subprocesses render exactly as before (no frame elements).

Code: `frontend/src/features/studio/ScopeFrames.tsx` (`frameBox`, `framesOf`, `frameCaption`, `ScopeFrames`), used by `CanvasView.tsx` and `runs/diagram/RunDiagram.tsx` (`runFrames`); styles in `studio.css`.

Not drawn: section time-limit and error routes as arrows (they are named in the frame label and the run diagram list), frames in the Lanes view, the Steps tab, BPMN DI changes (S59 already draws section frames in BPMN DI).

## 2. A call can raise an error for its section's error route (opt-in per call)

Model: `http.config.raiseErrorOnFailure: <errorCode>` (schema `$defs/errorCode`). Unset is the default and keeps S32 exactly: the call's own `failed` path when drawn, otherwise retries and "needs attention".

When set, and only for the failures S32 already routes as *known* (the system rejected the request with a 4xx other than 401/403/408/429, or a read-only call returned an unusable answer, with a completed `permanentFailure` effect-journal entry for this run/step/occurrence), the call raises that business error. The section's interrupting error boundary catches it, cancels the open work in the section and continues at the error route target. Unknown outcomes, retryable failures (5xx, timeouts, 429), and policy/configuration failures never raise it: they still retry and then need attention.

Validation (`ScopeRules.callFailures`):

| Code | When |
|---|---|
| `WF_CALL_FAILURE_ERROR_NOT_CAUGHT` (blocker) | the call is not in a section, the section has no error route, or the route catches a different code. Error handlers and other sections do not count. |
| `WF_CALL_FAILURE_ERROR_CONFLICT` (blocker) | the call also has a `failed` path, or is an automatic undo call |
| `WF_ERROR_CODE_RESERVED` (blocker) | the code starts with ORQIX_ |
| schema | the code format `^[A-Z][A-Z0-9_]{1,39}$` |

`WF_SUBPROCESS_ERROR_UNUSED` now also counts calls that opt in.

Compiler: `orqix:http raiseOnFailure="CODE"` and an `<error id="error__CODE">` definition; no step-level boundary is generated (the section's existing `__error` boundary catches it). Definitions without the setting compile byte-for-byte as before (golden files unchanged). Simulation: a "rejected" or invalid-read scenario for such a call says it raises the error and continues at the section's error route; an unknown outcome still needs attention.

Runtime (`HttpTaskDelegate`): the known-failure check was generalised to return the BPMN error to throw: `ORQIX_STEP_FAILED` when the step has its own failure boundary (unchanged S32 path, which also wins if a hand-made model had both), otherwise the opt-in code. For the opt-in it records `EFFECT_FAILED` (`routed: true`, `raisedError`) and `ERROR_RAISED` on the call (`source: callFailure`, `failureKind`, `effectId`), not `STEP_FAILED`; the projection is told the call was already handled, so the boundary's cancellation does not add a `STEP_INTERRUPTED`. The usual `ERROR_CAUGHT` / `SECTION_LEFT how=error` follow; the run path shows the call's visit as `raised` and the route `<section>__onError` as taken. The journal stays `permanentFailure`; nothing is resent. The crash-recovery path (a failure already journaled before the engine committed) uses the same rule.

Studio: the call inspector shows **When it fails** only for a call inside a section with an error route (or one that already has the setting): a checkbox "Raise an error for the section's error route when this call fails for good" (pre-filled with the section's code, or `CALL_FAILED` for an any-code route), the code (format, reserved prefix and "the section's error route does not catch this code" flagged). Turning it on removes the call's Failed route; the canvas then refuses a new Failed route from it.

### Decisions taken (no user input was available)

1. Opt-in per call, explicit code, never implicit: a section error route does not start catching call failures on its own, so existing published workflows behave identically.
2. Only the S32 "known" failures raise; uncertain or temporary failures keep retries and needs-attention, because routing them would hide a possibly completed effect.
3. Only the enclosing section's error route may catch it (not error handlers, not other sections); a call cannot have both its own failure path and the opt-in.
4. Step outputs are not guaranteed on the error route (unchanged S59 rule); the failure details stay on the call's step variable as for S32.

### Not covered

Error handlers catching call failures; other step kinds (child workflows, external worker jobs, document generation) raising on failure; per-status or per-error-code mapping to different business codes; BPMN source round-trip (the BPMN reader does not support subprocesses, so it neither reads nor needs the attribute); the offline mock validator/simulator in the frontend; frames for stages; Docker-stack and browser (Playwright/axe) journeys.

## Verification (18 October 2026, JDK 21, PostgreSQL via Testcontainers, Flowable 8)

- Compiler `ScopesAndEventsTest` (15 cases, 4 new): opted-in call compiles (attribute, error definition, section boundary, no `__failed` boundary; any-code route also accepted); without the opt-in nothing changes (warning `WF_SUBPROCESS_ERROR_UNUSED`, no attribute, example workflow emits no `<error>`); blockers for outside a section, other code, no route, error handler only, failed path conflict, reserved code, bad format, undo call; simulator raises on "rejected" and still needs attention on an unknown outcome. `GeneratedXmlTest` and `GoldenFilesTest` pass unchanged.
- Application `ScopesAndEventsRuntimeTest` (9 cases, 2 new, real engine): a 400 from the opted-in purchase order is sent once, raises `ORDER_REJECTED`, leaves the section by its error route and finishes rejected (events `SECTION_ENTERED`, `EFFECT_FAILED`, `ERROR_RAISED:procure`, `ERROR_CAUGHT:order`, `SECTION_LEFT how=error`, rejection notice, `RUN_COMPLETED`; no `STEP_FAILED`/`STEP_INTERRUPTED` for the call; journal `permanentFailure`; run path `order__onError` taken, visit `raised`); with the opt-in a 5xx still retries and needs attention at the call; without the opt-in a 400 in the same section needs attention as before.
- Frontend `ScopeFrames.test.tsx` (7 cases): frame geometry (measured and default sizes), captions, frames rendered on the studio canvas in the viewport portal (not as nodes; node count unchanged; member step names say their section), no frames without subprocesses, run-diagram frame states, the call inspector opt-in (offered only in a section with an error route, pre-filled code, Failed route removed and refused, uncaught code flagged, turning off). `ScopeInspectors.test.tsx` still passes.
- Manual check in the mock app (Vite, built-in browser): adding a section over "Create purchase order" and "Tell requester it is ordered" with an error route draws the frame behind the two steps with its label; DOM check: frame encloses both steps, `z-index: -1`, `pointer-events: none`, the step is on top at its centre; the call inspector offers the opt-in.
- Full clean backend `verify` (compiler and application, coverage gate, packaging): BUILD SUCCESS - compiler 499 cases, application 795 (791 pass, the same four intentional skips). `ArchitectureTest` and `SecurityMatrixTest` pass on this branch (`evidence/s66/backend-verify.log`).
- Frontend: `tsc -b`, ESLint on the changed files and `api:check` (schema.d.ts regenerated for the new property) pass. The full Vitest suite (115 files, 884 tests) was run twice while the machine was at 100% CPU from another session's work: 879 and 880 passed; every failure was a 15 s test timeout (plus one follow-on "multiple Check workflow buttons" in the same StudioPage file after a timed-out test), in `ai.test`, `StudioPage.test`, `GuidedWorkflowDialog.test`, `SourceLanesDiscussion.test` and `GenerateDocumentInspector.test`. Each of these files passes when rerun on its own (StudioPage 17/17 twice; the five files together 38/38); `GuidedWorkflowDialog.test` also times out on the unchanged base commit under the same load and passes with a 60 s timeout. A clean full run on an idle machine was not obtained (`evidence/s66/frontend-full.log`, `frontend-full2.log`, `frontend-rerun*.log`).
- Not run: the Docker stack, browser (Playwright/axe) journeys, the mock-mode bundle build, a refreshed image.
