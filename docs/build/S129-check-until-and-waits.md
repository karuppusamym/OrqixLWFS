# S129: Check until done, callback safety net and "Waiting for"

5 November 2026. Wave A of the Camunda gap plan ([doc 29](../29-camunda-gap-implementation-plan.md)): **CG-W01** "Keep checking until done" (§5.3), **CG-W03** callback with a polling safety net (§5.5) and **CG-W08** the uniform "Waiting for" projection, panel and API (§5.10). Migrations **V210** (`poll_wait`), **V211** (`wait_state`) and **V212** (purge grant and index). OpenAPI: one block tagged **Waits**.

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

## 6. Follow-ups closed after the Wave A merge, and remaining limits

Closed on 6 November 2026, after merging connection v1, retry and circuit breaker (S130, S131) and secrets:

| Item | Behaviour |
|---|---|
| Deferred calls | When the binding's circuit is open or its request rate is used up, `EffectDispatcher` throws `CallDeferredException` before anything is sent or journaled. The scheduler catches it: the next check moves to the end of the wait (capped at the deadline), **no check and no error are counted**, and `POLL_CHECK_DEFERRED` is written once per deferral run. At or after the deadline a deferred check gives up (`timedOut`; a safety net just stops) instead of spinning. |
| `Retry-After` | A retryable failure with the server's `Retry-After` (429, 503; `EffectDispatcher.Failed.retryAfter()`) sets the next check to `now + min(maxInterval, max(interval, Retry-After))`, capped at the deadline: never sooner than the server asked, never later than the step's longest interval. The failure still counts as a consecutive error. |
| Optional results | `poll.optionalResults` names mapped results that may be missing in an answer (a ticket number that appears only when done). The scheduler passes them to the executor as optional mappings (`ResultMappings`, `?` + pointer, in both the v0 and v1 mapping paths); a missing value leaves the check pending instead of making the answer unusable. They are optional outputs (`mayBeAbsent`), so later steps cannot require them (`WF_FIELDREF_NOT_GUARANTEED`); `WF_POLL_OPTIONAL` refuses names that are not kept results, and `WF_RESULT_OPTIONAL` is not raised for them. Studio has "May be missing until the work is done". |
| Release migration (S38) | A request waiting in a polling step (or a message wait with a safety net) can move only when the step's settings, including checks, operation, mappings and the pinned connection version, are identical; otherwise `POLL_CHANGED`. Before, polling steps could never move. |
| Repair scan (S72) | Open checks count as work outside the engine (a polling request is not "stranded"). Checks scheduled for a step where the engine is not waiting, or a step that shows checks with none scheduled, are reported (`unsupported`, never rebuilt). A `wait_state` that disagrees with the steps is `projectionDrift`, repairable: repair derives it again from the unchanged steps. |
| Purge | Ended `poll_wait` rows older than `orqix.polling.retention` (default 30 days) are deleted hourly (`orqix.polling.purge-delay`) in batches of 500 with SKIP LOCKED, one `poll.waits-purged` audit entry (count, retention) per workspace and batch. V212 grants DELETE and adds the index. |
| Safety-net inspector | Wait for a message has "Also check the other system now and then": connection, read-only operation, what to send, message values from the answer, done condition and interval. A connection used only by a safety net counts as used (dependencies sync, `WF_DEPENDENCY_UNUSED`, binding check, release connection pins). |
| Insights | "Waiting on outside systems" (`GET /insights/outside-waits`, `run.read`): per kind and system (connection, message name or worker topic) the requests waiting now, the longest and total time so far (from `wait_state`), and finished checks in the period with average and 90th percentile (from `poll_wait`). |

Remaining limits:

- **Flyway order.** V212 sorts below the merged V220–V241. A database already migrated to V241 rejects it unless Flyway's out-of-order mode is enabled for this deployment, or the file is renumbered above V241 at merge.
- Each check adds one `effect_journal` row (up to 1,000 per step occurrence); they appear in the request's technical details.
- A deferral or `Retry-After` does not stretch the deadline; an operator can extend it.
- Engine-level release migration of a waiting polling step was checked through the compatibility rules only (`ReleaseCompatibility`), not by moving a live instance.

## 7. Evidence

Compiler (`PollRulesTest`, 17 tests): every `WF_POLL_*` rule, the BPMN shape and round trip, the unchanged BPMN without polling, the shared schedule against the worked timing, and the simulator with answer sequences, failed and timed-out paths, failing checks and the safety net. All compiler tests and golden files pass unchanged.

Application, PostgreSQL and Flowable through the public API with a WireMock status endpoint (`PollWaitsTest`, 11 tests): pending ×3 then done with the mapped output and one read-only journal entry per check; deadline with a final check then `timedOut`; `failWhen`; consecutive errors needing attention, operator Check now (403 without `run.operate`, 422 without a reason, 429 within a minute, audited); restart with an hour overdue gives exactly one catch-up check, and a pod that died while checking is recovered once after its lease; two schedulers run concurrently over six due rows and each row is checked exactly once (attempts 1); Extend deadline (limit, idempotent replay) and Stop waiting; cancel closes the checks in the same transaction; an approval listed as a wait and 404 for another workspace; the safety net ending a wait whose message was lost with a late message never resuming twice; a message first closing the safety net. The generated security matrix covers the five new operations.

After the merge, `PollWaitsTest` has 18 tests: also a check deferred by an open circuit (no call, no check or error counted, next check at the end of the cool-down, give-up after the deadline), `Retry-After` 120 s honoured and 7,200 s capped at the 10-minute longest interval, an optional result missing in a pending answer, purge after the retention with its audit, the repair scan (in sync, `wait_state` drift repaired through the API, orphaned step reported), release-migration compatibility (`POLL_CHANGED` for changed checks or connection version), and the Insights figure. `PollRulesTest` adds `WF_POLL_OPTIONAL`, optional outputs and the safety-net connection counting as used.

**Load test** (`PollLoadTest`, opt-in with `ORQIX_LOAD_TEST=true`, PostgreSQL Testcontainers and WireMock on one Windows workstation): 2,000 concurrent polling waits checking once a minute with 10 percent jitter, four schedulers (the live one plus three loops standing in for pods), measured over 180 s after a 75 s warm-up:

| Measured | Value |
|---|---|
| Checks (WireMock calls) | 5,981 |
| Expected from the schedule (2,001 waits × 180.1 s / 60 s) | 6,007 |
| Ratio | **0.996** (within the 5 percent of the acceptance) |
| Rate | 33.21 checks/s measured, 33.35/s scheduled |
| Waits due for more than 10 s but not checked (max of 12 samples) | 0 |

The acceptance in doc 29 names 5,000 waits; 2,000 were measured here (the coordinator's target). The test takes the count from `-Dorqix.loadTest.waits`.

Frontend: schedule twin, inspector toggle and summary, optional results, Waiting-for panel and its actions, safety-net inspector, "Waiting on outside systems", browser simulator twin (with optional results and the safety net) and Test-tab answers.
