# S139: Working-time waits, reminders, wait for the first, message handlers and scheduled starts

6 November 2026. Wave C of [doc 29](../29-camunda-gap-implementation-plan.md): CG-W05, CG-W06, CG-W07 (§5.7–5.9), CG-E02 and CG-E08 (§9.1). Migrations V320–V322. OpenAPI block tagged **WaitsAndStarts**. Compiler version not bumped; BPMN of workflows without these settings is unchanged (golden files unchanged).

## What was built

| Ticket | Design |
|---|---|
| CG-W05 | Shared, versioned working calendars (`working_calendar`, `working_calendar_version`, V320; API `/calendars`, read `workflow.read`, change `workflow.edit`). A workflow pins a version in `dependencies.assets` (kind `calendar`); `AssetReferenceCheck` reports missing/archived (blocker) and newer (warning). `wait.workingDuration` + `calendarRef`, `waitForMessage.timeoutCalendarRef`, `poll.timeoutCalendarRef`. One working day = the calendar's working hours of one day. Timers use `${orqixWait.working(execution, '<element>')}`; check deadlines are computed in `PollWaits`. |
| CG-W06 | `whileWaiting.remind` on message waits/callbacks, check-until calls and worker jobs. `wait_reminder` rows (V321) start and stop with the "Waiting for" projection (`WaitStates.resync`, same transaction). Each reminder is one effect-journal send (node `<step>__remind`, occurrence = reminder number), so a retry never sends twice. D-CG7: `toField` (request field) allowed only when the adapter's `allowedRecipientDomains` contain the address domain, checked at every send (refused and recorded otherwise). `GET /runs/{runId}/reminders`. |
| CG-W07 | Step `waitForFirst`, 2–5 branches (message, signal, callback, timer, check), routes `on: branch` + `branch`. Compiles to an event-based gateway with one catch event `<step>__<branch>`; callback/check branches wait for an internal message `orqix-<branch>-<hash>` correlated by run id (the message API refuses `orqix-` names). Every branch is a current step, so `wait_state` has a row per branch; the winner's values become `/steps/<step>/*` plus `branch`; the others close (`WAIT_FIRST_WON`). BPMN import supports it. |
| CG-E08 | Handler trigger `message` (message, correlationField = required request field, payload). Subscription kind `handler` (V321 column): armed with the request, no waiting step, never takes buffered messages; interrupting handlers take one message, side handlers every message. Message waits are now closed when a handler interrupts them, so `waitForMessage` is no longer excluded from interrupting handlers. |
| CG-E02 | `schedule_trigger`, `schedule_fire`, `scheduler_lease` (V322). Five-field cron or RRULE subset (HOURLY/DAILY/WEEKLY/MONTHLY, INTERVAL, BYDAY incl. 1MO/-1FR, BYMONTHDAY, BYHOUR, BYMINUTE, UNTIL) in an IANA zone; optional pinned calendar (times outside working hours skipped); fixed input checked against the active release; missed runs `catchUpOnce`/`skip`, overlap `allow`/`skip`; at most one start per 5 minutes. Leader lease + unique `(schedule, fire_at)` + idempotency key = one start per planned time. Starts run as the person who last saved the schedule (needs `workflow.publish` and start rights; checked again at each start, failures recorded and audited). Schedules are a workspace resource, not part of the definition, so pausing never needs a republish. |

UI: Studio inspector sections (working time, time limit in working time, reminders, wait-for-the-first branches, message handler trigger), palette entry and event-gateway glyph, workflow "Scheduled starts" section; Settings tabs **Working calendars** and **Schedules**; MSW mock handlers.

## Merge notes (shared constraints, enums, files)

- `message_subscription.kind` new column with CHECK (`wait`, `handler`), V321.
- Workflow schema: route enum `branch`, node `waitForFirst`, asset kind `calendar`, trigger type `message`. OpenAPI: run-path transition `on` enum gains `branch`.
- `Permission`/`ProblemCode` unchanged. Audit whitelist: `calendar.*`, `schedule.*`.
- Shared files touched: `RuntimeProjectionListener`, `MessageDelivery`, `MessageStore`, `MessageWaits`, `MessageService`, `PollWaits`, `WaitStates`, `ReleaseQueries.node` (synthesizes branch/handler nodes), `ScopeRules`, `GraphRules`, `FieldTypes`, `Conditions`, `TypeRules`, `BpmnGenerator`, `BpmnReader`, `BpmnDefaults`, `SimulationRun`, `WorkflowDefinition` (new `waits` component, old constructors kept), `SecurityMatrixTest`, `contract_check.py` (preview operations exempt from audit).
- Regenerate `frontend/src/api/schema.d.ts` after merging.

## Evidence

- Compiler: `WaitsAndStartsCompilerTest` (15), `WaitsAndStartsRoundTripTest` (10); full compiler suite 921 tests pass, coverage 90.5% (gate 90%).
- Application (PostgreSQL, Flowable, public API): `WaitsAndStartsRuntimeTest` (11) and `RecurrenceTest` (5) pass.
- Frontend: `tsc -b` clean; studio + settings Vitest suites pass (76 files) after the notation test update.

## Not verified / limits

- The final full `backend/mvnw -o clean verify` (application suite) was started but its result was not read before the session ended; the frontend full suite, ESLint and `api:check` were not run.
- Handler payload values are validated and stored but not yet usable by handler steps as field references.
- The check branch has no Studio editor beyond the Source tab; no run-page reminders panel yet.
