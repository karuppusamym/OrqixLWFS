# S37 - Suspend and resume a request

First half of EX04. An operator with `run.operate` can pause an active request and let it continue later. Moving a suspended request to another release is the second half, specified in [S38](S38-release-migration.md).

## Behavior

| Operation | Rule |
|---|---|
| `POST /runs/{id}/suspend` | `run.operate`, `Idempotency-Key`, required `reason` (1–500 characters) and `expectedVersion`. Allowed only for a `running` or `waiting` request. Refused with `RUN_NOT_SUSPENDABLE` for requests that need attention, child requests, requests with a running child request, and requests whose engine instance is missing. A second suspend returns `RUN_SUSPENDED`. |
| `POST /runs/{id}/resume` | Same permission, key and body. Refused with `RUN_NOT_SUSPENDED` when nothing is suspended. |
| `POST /runs/{id}/cancel` | Still allowed while suspended; the suspension row is removed with the cancellation. |

Suspension is stored in `run_suspension` (V30); the run lifecycle (`running`/`waiting`) is unchanged, so lists, filters and `isActive` checks keep their meaning. Suspend and resume each bump the run version, append a `RUN_SUSPENDED`/`RUN_RESUMED` timeline entry with the reason, write the audit actions `run.suspended`/`run.resumed` (no metadata) and send the initiator one in-app notice.

## What is frozen

- **Engine:** the Flowable process instance is suspended. Its tasks, timers, message subscriptions and jobs are held by the engine. A step that is executing makes the suspend fail with `RUN_BUSY` (retry shortly); nothing is half-suspended.
- **Tasks:** claim, release, decide and reassign return `RUN_SUSPENDED`; the task detail reports every one of those actions as unavailable with that reason. Priority changes remain possible.
- **Messages:** a message sent while a wait is suspended is not delivered; it follows the existing buffer path (bounded, expires with the buffer TTL) and the sweeper delivers it after resume.
- **Watchdog:** a suspended instance counts as outstanding work, so the stale-request detector does not flag it (S31).
- **Retry/reconcile:** reported as `RUN_SUSPENDED` in allowed actions (a request needing attention cannot be suspended in the first place).

## Visibility

`RunDetail.suspension` carries `since` for everyone who can read the request, so a requester can see it is paused. `reason` is included only for `run.operate` holders. `allowedActions` gains `suspend` and `resume`. Studio run detail shows a banner, a Suspend/Resume button with a required reason, and the timeline entries.

## Limits (deliberate)

- **Clocks keep running.** Task due dates, stage deadlines and overdue notices use wall-clock time and continue while suspended. Timers (approval timeouts, waits, message time limits) do not fire while suspended, and those that came due fire shortly after resume. There is no SLA pause or due-date shift.
- A child request cannot be suspended on its own, and a request with a running child cannot be suspended. Cascading suspension of a call tree is not built.
- There is no scheduled or automatic suspension and no bulk suspend.
- Moving a suspended request to another release is [S38](S38-release-migration.md); suspension is its precondition.

## Tests

`RunSuspensionTest` (PostgreSQL + Flowable, through the public API): a claimed approval cannot be decided while suspended and completes after resume; permission, stale version, idempotent replay, double suspend, resume-when-not-suspended and cancel-while-suspended; a message sent while suspended is buffered and delivers after resume. `SecurityMatrixTest` covers both new operations. Frontend: `RunDetailPage.test.tsx` (operator flow, requester view) and `events.test.ts`.
