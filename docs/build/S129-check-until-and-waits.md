# S129: Check until done, callback safety net and "Waiting for"

5 November 2026. Wave A of the Camunda gap plan ([doc 29](../29-camunda-gap-implementation-plan.md)): **CG-W01** "Keep checking until done" (§5.3), **CG-W03** callback with a polling safety net (§5.5) and **CG-W08** the uniform "Waiting for" projection, panel and API (§5.10). Migrations **V210** (`poll_wait`) and **V211** (`wait_state`). OpenAPI: one block tagged **Waits**.

## 1. What the designer sets

A **Call a system** step on a read-only operation gets **Keep checking until…** (`config.poll`). Orqix calls the operation, maps the answer, and continues (`next`) when `until` holds, takes `failed` when `failWhen` holds, and takes `timedOut` at the deadline or when the checks are used up. The repetition lives inside the one step; the graph stays acyclic.

```json
"poll": {
  "every": "PT15M", "backoff": { "multiplier": 2, "maxInterval": "PT2H" }, "jitterPercent": 10,
  "until": { "fieldRef": "/steps/checkIncident/state", "operator": "in", "value": ["6", "7"] },
  "failWhen": { "fieldRef": "/steps/checkIncident/state", "operator": "eq", "value": "8" },
  "giveUpAfter": "P5D", "maxChecks": 200, "onCheckError": { "maxConsecutive": 5, "then": "needsAttention" },
  "finalCheckAtDeadline": true, "showWhileWaiting": ["state"]
}
```

`until` and `failWhen` use the decision-row condition shape (`in` lists its values under `value`, as decision rows do; doc 29 wrote `values`). Durations may use seconds (`pollInterval` in the schema: days, hours, minutes and seconds).

A **Wait for a message** step can get a **safety net** (`config.fallbackCheck`): the same call settings (connection, read-only operation, input and result mapping, timeout), a slow interval `every` (PT1M to P1D) and `until`. The mapped fields are the message payload fields; every required payload field must be mapped. The step resumes on whichever comes first.

## 2. Compiler rules

| Code | Rule |
|---|---|
| `WF_POLL_READONLY` | The operation must be `readOnly` (a check may run many times). Also for the safety net. |
| `WF_POLL_INTERVAL` | `every` PT10S to P1D (safety net: PT1M to P1D). |
| `WF_POLL_BACKOFF` | `maxInterval` at least `every`, at most P1D; multiplier 1–4 (schema). |
| `WF_POLL_DEADLINE` | `giveUpAfter` PT1M to P90D and at least `every`. |
| `WF_POLL_TOO_MANY_CHECKS` | Without `maxChecks`, the computed worst case must not exceed 1,000 (safety net: over its step's time limit, or 90 days). |
| `WF_POLL_ROUTES` | Exactly one `timedOut` path; a `failed` path when `failWhen` is set. A `timedOut` path is refused on a call without polling (`WF_OUTGOING_INVALID`, with a hint). |
| `WF_POLL_CONDITION` | A condition term on the step itself must name one of its mapped results; other terms follow the usual field availability. Types, literals and choice domains are checked like decision rows. |
| `WF_POLL_OVERLAP` | `until` and `failWhen` must never both hold (same satisfiability check as disjoint decision rows). |
| `WF_POLL_SHOW` | `showWhileWaiting` names mapped results only (at most 3, schema). |
| `WF_POLL_COMBINATION` | No polling on an undo call or a call that raises an error on failure. |
| `WF_POLL_FALLBACK_MAPPING` | Safety net: mapped names are declared payload fields of a matching type; every required payload field is mapped. |

The mapped results of a polling call exist only on its `next` path (`WF_FIELDREF_NOT_GUARANTEED` / `WF_OUTPUT_NOT_GUARANTEED` otherwise). On the `failed` path the failure facts apply; `failureKind` gains `remoteFailed` (the answer met `failWhen`) next to `rejected` and `invalidResponse`.

**Timing** is one shared class, `com.orqix.compiler.PollSchedule`, used by the compiler, the simulator and the runtime, with a TypeScript twin (`frontend/src/features/studio/model/pollSchedule.ts`). Interval *i* is `min(maxInterval, every × multiplier^i)`; checks at 0 (or `every` with `firstCheck: afterInterval`), then after each interval, all capped at the deadline, plus one final check exactly at the deadline. The worked example of doc 29 (PT5M, ×2, PT1H cap, P2D) gives 52 checks, the 51st at 47:15 and the 52nd at 48:00. Studio shows the summary, for example *"Checks up to 52 times over 2 days; from 1 hour 15 minutes on, once an hour. With 500 requests waiting this is about 500 calls an hour to ServiceNow."*

**BPMN.** A polling call compiles to an asynchronous, **triggerable** service task (`${orqixPollTask}`, the S64 shape) followed by a generated exclusive gateway `<id>__pollRoute` whose paths test `${orqixRoute.polled(execution, '<id>', 'met'|'failed'|'timedOut')}`. The settings travel as canonical JSON in `orqix:poll configBase64` (and `fallbackCheckBase64` on `orqix:message`), so the BPMN round trip keeps them; the runtime reads them from the release definition. A call without polling, and a message wait without a safety net, generate exactly the same BPMN as before: **no golden file changed**. The compiler version is **not** bumped on this branch (D-CG1: one coordinated bump per wave, at merge).

## 3. Runtime

| Part | Design |
|---|---|
| Entering the step | `PollTaskDelegate` resolves the inputs and the values of condition terms that are not the step's own, inserts one `poll_wait` row (V210) in the job transaction and returns; the engine waits at the step. The step is shown as a call that waits (`polling: true`), so the request is `waiting`. |
| Scheduler | `PollScheduler` (`orqix.polling.tick`, default 2 s) runs on worker and all-in-one pods only (scheduling is off on API pods, ADR 0004). Transaction 1 claims up to 20 due rows with `FOR UPDATE SKIP LOCKED` (skipping suspended requests), sets `checking`, a lease (`timeout + 30 s`) and raises the fence. The check runs outside any lock through `EffectDispatcher.dispatch` (egress, credentials, timeout, effect journal). Transaction 2 locks the request row, then the poll row **with the claimed fence and owner**; a lost lease records nothing. Lock order everywhere: request, then message subscription, then poll row. |
| One journal entry per check | Occurrence = check number, so a pod that dies after the call but before transaction 2 is re-claimed after its lease and the journal returns the recorded answer instead of calling again. |
| Outcome | `until` → trigger with outcome `met` and the mapped results as step outputs; `failWhen` → `failed` with the results plus failure facts; a known rejection (4xx) or an unusable answer → `failed` when drawn, otherwise needs attention; retryable failures (timeouts, 5xx, 429, unreachable) count as consecutive errors, and at `maxConsecutive` the request needs attention (`RETRIES_EXHAUSTED`) or takes `timedOut`. The trigger is `triggerAsync`, as for worker jobs. |
| Next check | From the time of the check that just ran: `now + interval ± jitter`, capped at the deadline. After downtime a due row therefore gets **exactly one** check, never one per missed interval. At or after the deadline: one final check (when set), then `timedOut`. |
| Suspend, cancel, end | Suspended requests are not claimed; the deadline keeps running and a passed deadline fires on resume. Cancelling a request or its completion closes its poll rows in the same transaction. If the step itself was cancelled (a section around it ended), the next outcome finds no execution and closes the row. |
| No payloads at rest | The row keeps the HTTP status, an outcome code, a digest of the mapped result and the up to three shown values; the request values and condition values are cleared when the row ends. |
| Timeline noise | `POLL_STARTED`, `POLL_VALUE_CHANGED` (only when a shown value changes), `POLL_CHECK_FAILED`, `POLL_ENDED`; unchanged checks only update counters. Operator actions add `POLL_CHECK_REQUESTED`, `POLL_DEADLINE_EXTENDED`, `POLL_STOPPED`. |
| Safety net (CG-W03) | Started with the message wait (`MessageWaits.started`). When its check meets `until`, the answer is written as an inbound message and handed to the existing `MessageDelivery.deliver` (one resume per wait, payload validation, `WAIT_ENDED`). A message that arrives first closes the safety net in the same transaction (`messageReceived`); the time limit closes it too. A late message after a safety-net resume is buffered, never delivered twice. A failing safety net stops itself and never stops the request. |

**Operator actions** (fresh `run.operate`, reason required, audited, `Idempotency-Key`): **Check now** (once a minute per wait; also restarts checks that need attention), **Extend deadline** (up to twice the authored time, never beyond 90 days from the start; a replayed key does not extend twice), **Stop waiting** (a check-until step takes `timedOut`; a safety net just stops). An operator **Retry** of a request whose checks stopped also resumes them.

## 4. "Waiting for" (CG-W08)

`wait_state` (V211) holds one row per open wait: kind (`approval`, `timer`, `message`, `callback`, `poll`, `externalJob`, `childRequest`, `signal`), a plain-language subject, since, next check, due time, deadline, attempts, a correlation reference (message name, signal or worker topic, never a request value) and the connection. Every wait kind already shows itself as a current step of the request, so the projection is **derived from the current steps inside `RunStore.mutate`**, in the same transaction as every change of them; no wait kind needed its own write and the panel cannot disagree with the request page.

| Operation | Permission | |
|---|---|---|
| `GET /workspaces/{ws}/runs/{runId}/waits` (`listRunWaits`) | `run.read`, initiator or task participant | Open waits with their checks, plus ended checks; shown values only with request-data access |
| `GET /workspaces/{ws}/waits?kind=&connectionKey=&waitingLongerThan=&limit=` (`searchWaits`) | `run.read` | The runs filter "waiting on connection X for longer than Y", oldest first |
| `POST .../waits/{waitId}/check-now`, `/extend-deadline`, `/stop` | `run.operate` (audited `poll.checked-now`, `poll.deadline-extended`, `poll.stopped`) | Bodies are decided after the access check, so a caller outside the workspace always gets 404 |

The run page shows a **Waiting for** panel: kind, subject, since, next check, deadline, checks made, last answer, and the three operator actions with a reason dialog.

## 5. Studio, simulator and Test tab

- The Call a system inspector has **Keep checking until…** (enabled only for read-only operations): done condition, optional failed condition, interval with optional backoff, deadline, values shown while waiting, failing-checks policy, final check, most checks, the computed summary, and a reminder to draw the timed-out and failed paths.
- The simulator (Java and the browser twin) follows the same schedule and reports each check with its simulated time. The Test tab accepts the answers of the checks, one per line (`pending`, `unavailable`, or a JSON body); the scenario field is `calls.<id>.checks` (the last answer repeats). A message wait with a safety net also accepts `messages.<id>.outcome: "fallback"`.
- The browser mock backend runs a check-until step as a single call (like workers, S64); its "Waiting for" handler derives waits from the mock run's steps.

## 6. Limits and follow-ups

- **Every mapped result must be in every answer.** The existing call executor treats a missing mapped field as an unusable answer, so a field that appears only when the work is done (a tracking number) makes a pending answer fail. Map only fields that are always present, or read the late field in a following call. Lifting this needs a change in `HttpExecutor` (owned by CG-W04/CG-C01 in this wave).
- **`Retry-After` and the binding rate limit / circuit breaker** (§5.3 "Rate limits", acceptance "Retry-After honoured") belong to CG-W04, which owns `HttpExecutor`; the scheduler does not read `Retry-After` yet.
- **Release migration (S38)** and the **repair scan (S72)** are not yet extended for `poll_wait`; a release migration of a request waiting in a polling step should be refused unless the step's settings are identical.
- The **5,000-wait load test** of the acceptance list was not run; per-pod throughput is one batch of 20 rows per round, up to 10 rounds per tick.
- Each check adds one `effect_journal` row (up to 1,000 per step occurrence); they appear in the request's technical details.
- Insights "time spent waiting on outside systems" can read `wait_state` but is not built.
- No purge of ended `poll_wait` rows yet.
- Studio has no inspector for the safety net yet; it is set in the workflow JSON or BPMN.

## 7. Evidence

Compiler (`PollRulesTest`, 17 tests): every `WF_POLL_*` rule, the BPMN shape and round trip, the unchanged BPMN without polling, the shared schedule against the worked timing, and the simulator with answer sequences, failed and timed-out paths, failing checks and the safety net. All compiler tests and golden files pass unchanged.

Application, PostgreSQL and Flowable through the public API with a WireMock status endpoint (`PollWaitsTest`, 11 tests): pending ×3 then done with the mapped output and one read-only journal entry per check; deadline with a final check then `timedOut`; `failWhen`; consecutive errors needing attention, operator Check now (403 without `run.operate`, 422 without a reason, 429 within a minute, audited); restart with an hour overdue gives exactly one catch-up check, and a pod that died while checking is recovered once after its lease; two schedulers run concurrently over six due rows and each row is checked exactly once (attempts 1); Extend deadline (limit, idempotent replay) and Stop waiting; cancel closes the checks in the same transaction; an approval listed as a wait and 404 for another workspace; the safety net ending a wait whose message was lost with a late message never resuming twice; a message first closing the safety net. The generated security matrix covers the five new operations.

Frontend: schedule twin, inspector toggle and summary, Waiting-for panel and its actions, browser simulator twin and Test-tab answers.
