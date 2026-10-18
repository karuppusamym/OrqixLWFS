# S55 - Sections, handlers, errors, signals and automatic undo (EX06 remainder, EX08; bounded)

EX06 still lacked embedded and event subprocesses after S51; EX08 asked for error and signal events and an automatic compensation lifecycle on top of EX05's HTTP failure route and the checked `compensates` annotation. This slice delivers bounded but executable versions of all of them, on the existing flat workflow model.

## Model

- **`subprocesses`** (new top-level array, at most 10) holds scopes over existing steps; steps and paths stay in `nodes`/`transitions`, so the canvas, the graph rules and field availability keep working on one flat graph. A step belongs to at most one subprocess; subprocesses do not nest.
  - **Section** (`type: embedded`): entered through exactly one entry step (every path from outside leads to it), left by the ordinary paths of its members to any number of outside steps. Optional `timeoutAfter` + `onTimeout` (PT1M..P90D) and `onError: { to, errorCode? }` (one code, or any code).
  - **Handler** (`type: event`): `trigger` is `error` (optional code; always interrupting), `signal` (a signal of this request) or `timer` (`after` the request started); `interrupting` true stops the rest of the request and its steps must end at a Finish step; false runs alongside, its last steps simply end, and it may not contain Finish steps.
- New steps: **Raise an error** (`raiseError`, `errorCode`, no paths), **Send a signal** (`sendSignal`), **Wait for a signal** (`waitForSignal`), **Undo completed calls** (`compensate`).
- `http.config.compensation: manual | automatic`. `automatic` (requires `compensates`) makes the call an **automatic undo call**: it has no paths and runs only from an Undo step, and only if the call it undoes completed in this request.

## Compiler and validation

New rule class `ScopeRules` plus graph changes. Implied flow is added to the graph for reachability and dominance only: a section's time-limit/error target is reached from the steps just before the section (so nothing produced inside the section is guaranteed there; `WF_OUTPUT_NOT_GUARANTEED`/`WF_FIELDREF_NOT_GUARANTEED` follow), a handler is entered from the start (only request data is guaranteed), and an undo call follows the call it undoes (so it may use that call's outputs). Raised errors, undo calls and the last steps of side handlers count as path ends.

Codes: `WF_SUBPROCESS_DUPLICATE_ID`, `_NODE_UNKNOWN`, `_NODE_DUPLICATE`, `_STEP_KIND`, `_SETTING`, `_TIMEOUT`, `_ENTRY`, `_EXIT`, `_ROUTE_TARGET`, `_ROUTE_LOOP`, `_PARALLEL`, `_HANDLER_PATH`, `_TRIGGER_DUPLICATE`, `_INTERRUPT_UNSUPPORTED`, warning `_ERROR_UNUSED`; `WF_ERROR_NOT_CAUGHT`, `WF_ERROR_CODE_RESERVED` (ORQIX_ prefix); warning `WF_SIGNAL_NOT_WAITED`; `WF_UNDO_TARGET_REQUIRED`, `_HANDLER_ROUTES`, `_HANDLER_SCOPE`, `_DUPLICATE`, `_NOTHING`, warning `_NEVER_RUN`. Durations reuse `WF_DURATION_*`.

BPMN mapping (Flowable 8): a section is a `subProcess` with a generated start event, a generated end event per exit target and, after it, an exclusive gateway `<S>__exit` whose conditions read which end event was reached (`orqixRoute.markExit` execution listener + `orqixRoute.exited`; a direct flow when there is one exit). Time limit and error route are interrupting timer/error boundary events on the subprocess. A handler is a `triggeredByEvent` subprocess whose start event carries the error/signal/timer definition. Raise an error is an error end event; error and signal definitions are emitted per code/name, signals with `flowable:scope="processInstance"`. When a side handler exists, every Finish step outside handlers is a terminate end event, so a finished request stops a handler still running. An automatic undo call is emitted as an `isForCompensation` service task with no flows; the Undo step is an async service task `${orqixCompensationTask}` with `orqix:undo target/handler` entries. Expression allow-list (engine and `GeneratedXmlTest`) extended accordingly. BPMN DI draws section frames and the generated helpers.

Simulation: raised errors follow the section error route or error handler; sent signals and waits are noted (waits assume arrival; handlers are not simulated); Undo lists the completed calls it would undo, most recent first.

## Runtime

- **Projection** (`RuntimeProjectionListener`): `SECTION_ENTERED`/`SECTION_LEFT` (how: completed with the exit, timedOut, error), `ERROR_RAISED`, `ERROR_CAUGHT`, `HANDLER_STARTED`, `SIGNAL_SENT`, `SIGNAL_WAIT_STARTED`/`SIGNAL_RECEIVED` (a "waitForSignal" current step), `STEP_INTERRUPTED`. Work cancelled by a section boundary or an interrupting handler (identified by the cancellation cause, or for error handlers by the caught error in the same command) has its open tasks cancelled and its current step removed; other cancellations keep their existing handling.
- **Signals** never leave a request: in-process throws are instance-scoped, and the operator API delivers to the subscriptions of one process instance through an engine command (Flowable's public signal API skips instance-scoped subscriptions and could otherwise broadcast).
- **Automatic undo lifecycle** (`CompensationTaskDelegate`, `RunCompensations`, V70 `run_compensation`): the first run of an Undo step fixes a plan - every call with an automatic undo whose effect journal entry in this request *succeeded*, most recently completed first - in its own transaction. It then runs each undo call through the same journaled `EffectDispatcher` as a call step and marks it undone. A failing undo call is an ordinary job failure (retries, then needs attention at the Undo step); an operator retry follows the same plan and never resends an undo that already succeeded. Completion writes `UNDO_SUCCEEDED` per call and `COMPENSATION_COMPLETED`, then the request continues.
- **Run path** (`getRunPath`): `diagram.scopes` (sections/handlers with their routes `<S>__onTimeout`/`<S>__onError`), `scopeVisits`, visit outcomes `raised` and `interrupted`, and taken section routes. Release moves are refused for workflows with subprocesses (`SUBPROCESSES` finding).

## API and permissions

| Operation | Requires | Notes |
|---|---|---|
| `sendRunSignal` `POST /workspaces/{ws}/runs/{runId}/signals` | `run.operate` | Idempotency key, reason, `expectedVersion`; 404 for invisible runs, 403 (audited) without permission, 409 `SIGNAL_NOT_AWAITED` / `RUN_SUSPENDED` / `RUN_NOT_ACTIVE`; audit `run.signal-sent` (signal, delivered count) |

Authoring is governed by the existing draft/publish permissions; undo calls are bound to the same pinned connections and egress policy as any call. Problem code `SIGNAL_NOT_AWAITED` added; the migration finding enum now lists `MULTI_INSTANCE` (missing since S51) and `SUBPROCESSES`; current step kinds `waitForSignal` and `compensate`.

## Studio

Request form inspector: **Sections and handlers** editor (add/remove, name, members limited to allowed kinds and exclusive across scopes, section time limit and its target, error route and optional code, handler trigger, interrupting; error handlers forced interrupting). Palette and inspectors for the four new steps (signal name and error code formats checked). Call inspector: **Undo** (which call this undoes; run automatically - turning it on removes the call's paths). The canvas refuses paths from a raised error and paths to/from automatic undo calls; step rename/delete keeps subprocess references. Run diagram: a **Sections and handlers** list (state, how left, where it continued).

## Decisions taken (no user input was available)

1. Scopes are a separate `subprocesses` list over the flat graph, not nested node containers: the canvas, rules and stored definitions stay single-level. No nesting, at most 10.
2. Automatic compensation is run by Orqix, not by Flowable compensation events: Flowable 8 does not wait for compensation handlers (its source marks waitForCompletion as TODO) and orders them by subscription time only, so a request could finish before its undo calls ran. The Orqix step is sequential, journaled, most-recent-first and retry-safe.
3. Compensation is request-wide (every completed call with an automatic undo), not per-subprocess scope.
4. HTTP failures are not converted into catchable business errors; a call keeps its own `failed` route (S32). A section error route catches only Raise an error.
5. Signals are scoped to one request; no workspace-wide broadcast. External signals require `run.operate`.
6. A finished request (with a side handler) terminates anything still running alongside it.
7. Steps a cancelling scope could not clean up (child workflows, external worker jobs, message waits; parallel splits inside sections) are excluded, as are section time limits/error routes in workflows that run steps in parallel.

## Not covered

Nested subprocesses; subprocess-scoped compensation, compensation of non-HTTP steps or child workflows, BPMN compensation events at runtime; message-, escalation- or conditional-triggered handlers; handlers inside sections; multiple error routes per section; error routes on individual steps; signal boundaries on steps; catching platform failures as business errors; cross-request or workspace-wide signals; drawing section frames on the Studio canvas (sections are edited in the inspector) and on the run diagram canvas (a list is shown instead); the offline mock simulator/validator; release moves of requests in such workflows; Docker-stack and browser (Playwright/axe) journeys.

## Verification (17 October 2026, JDK 21, PostgreSQL via Testcontainers, Flowable 8)

- Compiler `ScopesAndEventsTest` (11 cases): section BPMN (subprocess, entry/exit flows, exit gateway with markExit/exited, timer and error boundaries, error definitions, DI frame); single-exit section without gateway; structure blockers (entry, Finish inside, step in two scopes, id clash, route target inside, half-set time limit, P200D); data inside a section not guaranteed after its time-limit/error route; uncaught/other-code/reserved errors and paths out of a raise; handler BPMN (signal and timer starts, interrupting flag, side-handler end, terminate ends, no terminate inside a handler); handler blockers; signal throw/catch BPMN and the not-waited warning; undo BPMN and blockers (paths, missing target, duplicate, later-step data, nothing to undo, never run); simulator raise/undo. `GeneratedXmlTest` now also compiles these three workflows and checks the extended expression allow-list.
- Application `ScopesAndEventsRuntimeTest` (7 cases, public API, real engine): a raised error leaves the section by its error route (events, path `review__onError`, visit `raised`) while a normal request leaves by the gateway to the purchase order; a section time limit cancels the open manager task and continues at its target; an operator signal starts the interrupting withdraw handler (requester 403, outsider 404, unknown signal 409 `SIGNAL_NOT_AWAITED`, idempotent replay, one audit row) and leaves another request of the same workflow untouched; a timer handler runs alongside and the request still finishes; a sent signal reaches its own request's handler and waiting step only (another waiting request's handler is not started) and an operator signal releases the wait; a rejected call undoes the two completed calls most recent first (plan rows, events, 5 remote calls) and finishes failed; a failing undo call needs attention at the Undo step and an operator retry sends only the remaining undo call. `SecurityMatrixTest` covers `sendRunSignal`.
- Defects found by running the tests: Flowable's public `signalEventReceived` silently skips instance-scoped subscriptions (fixed with an engine command); `getRun` contract enum lacked the new current-step kinds; audit metadata needed whitelisting; the compiler test first assumed the wrong element order.
- Full clean `verify`: compiler 475 cases, application 765 cases (761 pass, the same four intentional skips), coverage gate and packaging - BUILD SUCCESS (`evidence/s55-backend-verify.log`).
- Frontend: `ScopeInspectors.test.tsx` (6 cases: section/handler authoring, reference keeping on rename/delete, signal and error-code inputs, undo marking removes paths and canvas refusals, run-diagram scope list). Full suite 102 files, 842 tests pass (`evidence/s55-frontend-final.log`); a first full run while the backend verify was running had one `RunDiagram.test.tsx` timeout that passes alone and in the clean rerun. `tsc -b`, scoped ESLint and `api:check` pass.
- Not run: the Docker stack, browser (Playwright/axe) journeys, the mock-mode bundle, a refreshed image.
