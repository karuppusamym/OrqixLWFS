# S17 — Task delegation and durable reminders

## Delegation

An eligible approver who owns an open task can delegate it to another eligible named colleague. The UI loads choices from `GET /workspaces/{workspaceId}/tasks/{taskId}/delegates`; it does not accept an arbitrary subject identifier. A reason and explicit confirmation are required. The mutation carries the viewed task's `expectedVersion`, and a conflict preserves the proposed colleague and reason while asking the user to reload the current task.

The backend rechecks ownership, task openness, candidate group membership, `task.act`, workspace access and self-approval policy at execution. The task owner cannot delegate to themself. The known-user directory scans at most 500 registered subjects and returns at most 100 eligible named colleagues. A person who has never been registered locally cannot be suggested until identity synchronization or sign-in creates their subject record.

Delegation updates the task and run projections in the same transaction, preserves the earlier task/decision history, emits `TASK_REASSIGNED` with the reason and named source/target, and writes a `task.delegated` audit action. Operator reassignment remains separately authorized. The frontend mock API implements the same ownership, eligibility, self-approval and version checks with its existing run-event history model; durable database audit records belong to the real backend.

## Assigned-task reminders

Reminders are one-shot, durable, in-app notifications to the currently assigned eligible approver. The default delay is 24 hours. Configuration permits delays from 1 minute to 30 days; the polling default is 60 seconds. They are not email delivery and do not broadcast to the entire candidate group.

Set `orqix.reminders.enabled`, `orqix.reminders.after` and `orqix.reminders.poll-delay`, or the corresponding `ORQIX_REMINDERS_ENABLED`, `ORQIX_REMINDERS_AFTER` and `ORQIX_REMINDERS_POLL_DELAY` environment variables. Local Compose forwards these settings; defaults and examples are in `deploy/compose/.env.example`. The migration profile does not create the reminder worker or enable its scheduler.

The persistent pending queue survives process restarts. Workers claim batches of at most 100 using `FOR UPDATE SKIP LOCKED`, so multiple workers can drain the queue without sharing the same claim. They also skip busy task rows instead of waiting while holding batch locks; those queue entries remain pending for a later poll. Stable notification deduplication protects retries from repeated reminders. Completed/cancelled tasks do not generate reminders. Changed assignments never notify the previous assignee; revoked permission, ineligible approvers and denied self-approval defer delivery. Eligibility bypasses the session-policy cache and reads current access. Delegation/reassignment is evaluated against the current owner rather than sending an obsolete notification to the previous owner. Scheduling is disabled for the `migrate` profile.

## Verification

`TaskDelegation.test.tsx` verifies named choices, required reason and confirmation, expected-version requests, busy callbacks, retryable directory loading, preserved conflict forms, and mock API authorization/version/history behavior. Three backend integration cases verify owner/target eligibility and persisted handoff, durable one-shot deduplication, unassigned/disabled recipients and warmed-cache role revocation. A live browser flow verifies explicit delegation, removal of the former owner's decision controls, and the recipient's approval. Queue persistence and locking are database-backed; a dedicated process-restart reminder drill remains an operational acceptance item.

## Boundaries

Recurring escalation policies, email/Teams delivery, group-wide reminders, out-of-office schedules, identity-provider directory search and business calendars are additional capabilities. The one-shot reminder queue and named delegation do not imply them.
