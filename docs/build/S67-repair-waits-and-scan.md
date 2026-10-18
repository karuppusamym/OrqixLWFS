# S67 - Repair of wait and worker steps, and the periodic consistency scan (EX09 extension)

Builds on S49 (EX09 on-demand check and approval rebuild) and S31 (EX03 watchdog). Three additions, none of which triggers, skips, migrates or modifies the engine.

## 1. What the rebuild now covers

`GET /runs/{id}/diagnosis` and `POST /runs/{id}/repair` (same permission `run.operate`, same idempotency key, `expectedVersion`, reason, audit `run.projection-repaired` with counts only, same re-classification under the run lock) now rebuild these steps when the engine state fully determines them:

| Step | What the engine (and Orqix's own bookkeeping row) must show |
| --- | --- |
| Approval (S49, unchanged) | Open engine task plus its open `task_context` row with the same task id and node |
| Date wait ("wait until") and duration wait | One engine timer job at an element carrying `orqix:wait`; due date and creation time come from that timer job |
| Message wait | The engine message subscription at the catch element, an open `message_subscription` row (`waiting`, same node, message name and engine execution id), and, when the step has a time limit, the engine timer of that limit; the due date is the engine timer's |
| External-worker step | The engine token waiting at a service task carrying `orqix:external`, plus an open (`available` or `locked`) `external_job` row for the same node and topic; the job is not touched (lease, attempt and fence are unchanged) |

The classification is the S49 table. The new rule is that a rebuild is offered only when **everything** the engine holds is accounted for. Otherwise the state is `unsupported` with the reason in the message, and the repair is refused with 409 `RUN_NOT_REPAIRABLE`:

- an engine timer or event subscription this check cannot interpret (for example a signal wait, or a timer at an element it does not know);
- a message wait whose time-limit timer is missing, or without an open subscription row for the engine's waiting execution;
- a worker step with no open job for its topic, or an open job for a step where the engine is not waiting;
- an executable job in flight, or any dead-letter or suspended job (check again once it settles or is resolved);
- an engine activity none of the above explains, a projected step of any other kind (child, HTTP, document, signal wait), or active child requests;
- a task with no open `task_context` row.

An engine token at a worker step whose job row is gone is still `strandedToken` (cancel only). Nothing is restarted: a lost job or a skipped activity could repeat an external effect or invent an outcome.

The rebuilt step carries the same fields as the step the engine listeners create (`kind`, `label`, `since`, `dueAt`, `message` or `topic`, `taskId` for approvals). `since` is the engine timer creation time for date waits and the Orqix subscription or job creation time for message waits and worker steps. Lifecycle is settled the same way as for any step change.

## 2. Periodic consistency scan (report only)

`RunConsistencyScan`, called from `RunWatchdog.sweep()` after the EX03 pass (a failure in it never affects that pass).

- **Opt-in:** `orqix.watchdog.consistency-scan.enabled` (default `false`). Off means no queries, no rows.
- **Bounded:** `batch-size` (default 10, hard cap 25) requests per pass; each request is inspected in its own short transaction with `FOR UPDATE SKIP LOCKED` (a request that is busy is simply looked at next time).
- **Rate-limited:** at most one pass per `interval` (default 5m) on an instance.
- **No repeated inspection of healthy waiting requests:** migration `V93` adds `run_consistency_scan` (one row per scanned request: state, message, version, first-seen, checked-at). Only requests that are running or waiting, not suspended and not updated for five minutes are candidates; never-scanned requests come first, then the longest-unchecked. A request that was `inSync` is looked at again only after `recheck-after` (default 6h); one with a finding after `finding-recheck-after` (default 30m), so a resolved finding clears. The plain EX03 pass still handles empty projections and is unchanged.
- **Reports, never repairs:** it calls the same read-only diagnosis as the operator check. It does not change the request (no version bump, no lifecycle change, no timeline event), does not notify, and does not cancel or rebuild. Suspended and ended requests are not stored as findings; rows of finished requests are purged (bounded per pass), and a successful operator repair removes the finding at once.
- Several instances each respect the limits independently (the interval is per instance); the row lock and the recheck times keep them from inspecting the same request together.

`GET /workspaces/{ws}/consistency-findings?limit=` (`run.operate`, default 50, max 100) returns `{enabled, scannedRuns, lastCheckedAt, total, items[]}`; each item has the run id and reference, the state, `repairable`, the message, the run version, `firstSeenAt` and `checkedAt`.

## 3. Operator UI

- Runs page: a "Consistency scan" panel for `run.operate` holders. It appears only when the scan is on or findings exist; it lists each drifted request with the plain-language state, whether it can be rebuilt, when it was found and a link to the request's operator section. It says the scan only reports and offers no repair control.
- Run detail, operator section: a warning when the scan found a mismatch for this request, pointing to "Check consistency". The dialog now says "Rebuild steps" and "Open steps are missing from this request".
- Requesters never request findings.

## Evidence

Run locally on 18 October 2026 (PostgreSQL via Docker, Flowable, public API):

- `RunRepairWaitsTest` (6 cases): lost date-wait, message-wait (with its time limit; the message afterwards still arrives and completes the request) and external-worker projections are classified `projectionDrift` and rebuilt with the same engine timer job, subscription and untouched job row (`available/0/0`, a worker then leases and completes it); audit and timeline once, replay safe. Refusals: an unknown engine timer, a message wait without its open subscription row (both `unsupported`, 409 `RUN_NOT_REPAIRABLE`, projection and engine unchanged), and a worker token whose job is gone (`strandedToken`).
- `RunConsistencyScanTest` (4 cases): drift is recorded but the request is byte-for-byte unchanged (version, `updated_at`, steps, lifecycle, no event); a healthy request is not due again before the recheck time and its `checked_at` is unchanged by a further pass; the watchdog sweep does nothing while the scan is off; an enabled scan is capped at 25 per pass and a second sweep inside the interval does nothing; findings are listed for the operator and refused (403) for a requester; a repair clears the finding; ended requests are purged and suspended ones skipped. S49's `RunRepairTest` and `RunWatchdogTest` still pass.
- Full clean backend `verify`: compiler 495 tests, application 803 tests (799 pass, the same 4 intentional skips), coverage gate and packaging pass. An earlier verify, run while the frontend suite was running at the same time, failed once on `BankingWorkflowsTest` (a one-minute timer wait timed out under load); that class passes alone (42/42) and the final clean verify passed.
- Frontend: typecheck, lint and `api:check` pass. `ConsistencyScanPanel.test.tsx` (5 cases) and the updated `RepairDialog.test.tsx` (3) pass. The full suite run concurrently with the backend verify had 22 failures in studio/AI/workflow-list files unrelated to this change; run alone it had 10 such failures, and those files pass when run together (42 files, 253 tests). Full-suite passes were therefore not obtained in a single run; the failures looked like timeouts under load, not defects, but this was not investigated further.

## Not covered

- Child-workflow, HTTP/document, signal-wait and other projections are still reported, not rebuilt; mixed states with any of them are refused.
- A projection that differs only in `dueAt`/`since` is not treated as drift.
- No automatic engine-token repair, no backup/restore or reconstruction of lost engine state.
- The scan is not enabled in any shipped profile; operators enable it per environment. Interval limits are per instance.
- No real-Docker full-stack or browser (Edge/axe) run for this ticket.
