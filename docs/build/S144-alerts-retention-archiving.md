# S144 - Alert rules, run retention and archiving, engine history purge (CG-O06, CG-O07, CG-P04)

8 November 2026. Branch `claude/wave-d-alerts-retention-d0a436`, based on `claude/camunda-features-gap-a441b7`
(Waves A-C). Plan: [doc 29 §9.6 and §9.13](../29-camunda-gap-implementation-plan.md). Flyway V370-V371 (range
V370-V379 reserved for this slice). Builds on S131 (connection health and circuit state), S99 (outbound webhooks),
S47/S73 (adapters and stub transports), the run evidence pack and the exit export (30 October).

New module `com.orqix.operations` (alerts and retention); nothing depends on it. It uses the runtime through the new
facade `runtime.RunRetention` and the integration module through the new port `integration.AlertChannels`.

## CG-O06 Alert rules

A workspace rule watches one signal, optionally scoped, and fires when the value reaches its threshold.

| Signal | Value | Scope |
|---|---|---|
| `needsAttention` | Requests in *needs attention* now | workflow |
| `slaBreaches` | SLA breaches detected in the window | workflow |
| `circuitOpen` | Connections whose circuit is open now (S131) | connection |
| `tokenRefreshFailing` | Credential (OAuth) failures in the window (S131 `connection_call`) | connection |
| `deadLetters` | Dead webhook deliveries plus failed external jobs in the window | workflow (external jobs only) |
| `pollChecksFailing` | Open poll waits in *attention* or with consecutive errors (S129) | workflow or connection |
| `aiDriftFallback` | AI skill versions in drift fallback now (S134) | none |

Decisions:

- **Permission `alert.manage`**, grant-only and workspace-wide (like `connector.manage`); every alert endpoint needs
  it, writes need it fresh and are audited. No role carries it.
- **Thresholds and windows.** `threshold` 1-100,000 ("at least"); `windowMinutes` 5-1,440 (default 15) is used by the
  three windowed signals. Each value is one bounded count query on indexed state; no request value is read.
- **Evaluation.** `AlertEngine` wakes every `orqix.alerts.poll-delay` (30 s) and evaluates rules whose
  `next_evaluation_at` is due (`orqix.alerts.evaluation-interval`, 1 min), claimed with `FOR UPDATE SKIP LOCKED`.
- **Deduplication.** At most one open incident per rule (partial unique index); while firing, evaluations only update
  the value and peak. Notifications are an outbox (`alert_delivery`) with one row per incident, destination and kind
  (`fired`, `resolved`), so a retried evaluation never notifies twice.
- **Quiet period** (`quietMinutes`, default 30): an incident that opens sooner than that after the rule's last
  notification is recorded with `suppressed = quietPeriod` and not notified, nor is its resolution. This also absorbs
  flapping. **Silence**: `POST .../silence {minutes}` (0-10,080; 0 ends it) records new incidents as `silenced`.
- **Resolve notifications** (`notifyResolve`, default true) go to the same destinations when the value drops below the
  threshold, only if the firing was notified. Editing the signal, scope or threshold, or switching a rule off, closes
  an open incident without a notification.
- **Destinations** (at most 20; a rule names 1-5):
  - `email` and `teams` send through an existing IN02 adapter of that kind; the adapter is read again at each send,
    so its recipient domains, enabled state and egress rules apply (`AdapterRules.validateSend`).
  - `slack` (incoming webhook address) and `pagerDuty` (Events API v2 routing key) name a secret; it is resolved at
    send time only to check it exists (counted in the secret-access audit) and never reaches the transport, a log or
    the database. Only `LocalAlertHookStub` exists. PagerDuty gets `trigger`/`resolve` with a dedup key per incident.
  - `webhook` queues an `alert.fired`/`alert.resolved`/`alert.test` delivery on an existing S99 subscription (signed,
    retried and dead-lettered like request events; no `request` object in the payload). The subscription's event
    list is not consulted, so `webhook_subscription_events` is unchanged.
- **Retries.** A failed delivery is retried with backoff (2, 4, 8, 16 minutes) and marked `failed` after 5 attempts.
- **Messages** carry the rule name, signal, value, threshold, window and scope (workflow name or connection key)
  only. Audit (`alert.fired`, `alert.resolved`, system actor) holds ids, signal and counts.
- **Metrics**: `orqix_alerts_fired`, `orqix_alerts_resolved`, `orqix_alert_notifications{kind,status}`.
- **UI**: Settings → Alerts (shown with `alert.manage`): destinations with a test send, rules with silence and delete,
  recent incidents with their notifications.

## CG-O07 Run retention and archiving

- **Policy per workflow** (`PUT /workflows/{id}/retention-policy`, `workspace.manage-access`, `expectedVersion` 0
  creates): `keepDays` 1-3,650 after the request ended, `includeRunData` and `includeFiles` (default true), `enabled`.
  Workflows without a policy keep everything (opt-in). Reading needs `workspace.manage-access` or `audit.read`.
- **Hierarchy.** Only top-level ended requests are candidates; a request and all its child requests are one unit and
  the top-level request's policy applies to the whole unit. A unit with an open child is skipped (`open_child`).
- **Legal hold** on one request or one workflow (`POST /legal-holds`, reason 3-500 characters; release with a reason).
  An active hold on any member of a unit, or on any member's workflow, blocks it (`held`). Holds are never deleted.
- **Archive.** One ZIP per request (`<workspace>/<workflow>/<yyyy>/<mm>/<reference>-<id>.zip`):
  `manifest.json` (format `orqix-run-archive` v1, policy, counts, exclusions, every file's SHA-256),
  `evidence-pack.json` and `.pdf` (the run evidence pack, generated by the system), `history/runs.jsonl`,
  `tasks.jsonl`, `decisions.jsonl`, `run-events.jsonl` (exit-export field names), `history/effects.jsonl`,
  `audit/audit-events.jsonl` (the request's, its tasks' and effects' audit records), `files/attachments.jsonl` plus
  each live file's bytes (`includeFiles`), `SHA256SUMS`. Without `includeRunData`, input, output, event data and
  effect results are left out. An archive over `orqix.retention.max-archive-bytes` (256 MiB) is skipped
  (`archive_too_large`).
- **Store** behind `ArchiveStore`: `filesystem` (default `data/run-archives`, atomic move) or `s3` (any S3-compatible
  store, path-style, AWS Signature V4 over `java.net.http`, no SDK; endpoint, region, bucket, prefix and keys are
  platform configuration from the deployment's secret mount or environment, never from a workspace).
- **Order per request, one transaction per unit:** lock the top-level request, decide again, build, store, read back
  and compare the SHA-256, record `run_archive` (`archived`), purge, mark `purged` with the row counts. Any failure
  rolls the unit back and leaves the request untouched (an object already written stays in the store and is replaced
  by the next attempt, since the key is deterministic).
- **Purge** is the database function `orqix_purge_run(ws, run)` (`SECURITY DEFINER`, owned by the migrator, `EXECUTE`
  for the runtime role). It re-checks every guard itself (ended, archive record present, no active hold on the
  request, an ancestor or their workflows, no remaining child, not linked to a case, no AI evaluation candidate),
  deletes the request's rows in 40 tables child-first, detaches deduplication and schedule records
  (`inbound_event`, `kafka_inbound_record`, `package_trigger_record`, `schedule_fire`, `schedule_trigger.last_run_id`)
  and deletes the `workflow_run` row. The runtime role keeps no `DELETE` on append-only tables such as `run_event` and
  `task_decision`. **The audit log is never purged.**
- **Kept on purpose** (the purge refuses, reported as `skipped`): requests linked from a case (`case_linked`; cases
  have their own lifecycle) and requests whose AI decision is an evaluation candidate (`ai_evaluation_candidate`;
  evaluation datasets are not deleted silently).
- **Sweeps.** Daily (`orqix.retention.sweep-cron`, 03:15) per workspace with an enabled policy, at most
  `orqix.retention.max-requests-per-sweep` (200) units, oldest first; at most one sweep runs per workspace (a sweep
  running for 6 hours is treated as abandoned). `POST /retention-sweeps {dryRun}` runs one now; archiving and purging
  needs `confirm: true`. A dry run only lists (first 200 items: reference, outcome, reason). Audit
  `retention.sweep-completed` (mode, counts) and `run.archived-and-purged` per request (reference, archive id, SHA-256,
  bytes, store; no values).
- **Archives** stay listed after the purge (`GET /run-archives?reference=`); the download reads the object back and
  verifies its checksum first (`retention.archive-downloaded` audit).
- **Metrics**: `orqix_retention_requests{outcome}`, `orqix_retention_history_purged`.
- **UI**: Settings → Run retention (shown with `workspace.manage-access` or `audit.read`; changes for administrators):
  policies, legal holds, dry run and "Archive and purge now" behind a confirmation, archives with download links.

## CG-P04 Engine history growth

Decision: **batch purge through the Flowable API, not partitioning.** The `ACT_HI_*` tables belong to the engine
(created and upgraded by Flowable in the migrate job, keyed by string ids, no time column suitable for range
partitions on every table); partitioning them would fork the engine schema and block Flowable upgrades.

- Only process instances recorded on a **purged** archive are candidates (`run_archive.history_status = pending`), so
  history of requests that still exist, or were never archived, is never touched.
- `HistoryPurger` takes batches of `orqix.retention.history-batch` (100) with `FOR UPDATE SKIP LOCKED`, one
  transaction per batch, at most 20 batches per run, every `orqix.retention.history-delay` (10 min) and right after a
  purge sweep.
- `EngineHistoryPurge` (runtime) skips an instance that still has runtime state or no end time, collects ended sub
  instances, deletes the DMN decision history (`bulkDeleteHistoricDecisionExecutionsByInstanceIdsAndScopeType`) and
  then `HistoryService.bulkDeleteHistoricProcessInstances`, which removes activities, tasks, variables, details,
  identity links, comments and byte arrays in the engine's own order. Verified: `act_hi_procinst`, `act_hi_actinst`,
  `act_hi_taskinst`, `act_hi_varinst` and `act_hi_identitylink` have no row left for the instance.
- Audit `run.history-purged` (system, per workspace and batch: instance count).

### Runbook: engine history growth

1. **See it.** `SELECT relname, n_live_tup, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_stat_user_tables
   WHERE schemaname = 'flowable' AND relname LIKE 'act_hi_%' ORDER BY pg_total_relation_size(relid) DESC;` and
   `SELECT history_status, count(*) FROM orqix.run_archive GROUP BY 1;` (a growing `pending` count means the purger
   is behind or blocked).
2. **Decide what may go.** History goes only with its request. Give the workflows a retention policy (Settings → Run
   retention), run a dry run, check the held and skipped reasons, then let the nightly sweep run or start one.
3. **Catch up after a backlog.** Raise `orqix.retention.history-batch` (up to 1,000) and lower
   `orqix.retention.history-delay` temporarily; batches never overlap across nodes. Watch
   `orqix_retention_history_purged` and lock waits (`pg_stat_activity` with `wait_event_type = 'Lock'`). Keep batches
   small during business hours: each batch is one transaction on the engine tables.
4. **Reclaim space.** Deletes leave dead tuples; autovacuum reclaims them for reuse. To return disk space, run
   `VACUUM (ANALYZE)` on the largest `act_hi_*` tables in a maintenance window, or `pg_repack` online; never
   `VACUUM FULL` while the engine runs.
5. **Something stays pending.** Instances skipped as still running stay `pending` and are retried next time; one that
   is truly orphaned (engine runtime rows of an ended request) is an incident for the operator, not for the purger.
6. **Restore a purged request.** It cannot be revived in the engine. Download its archive (Settings → Run retention →
   Archives, or `GET /run-archives/{id}/content`); `SHA256SUMS` and `manifest.json` prove the content.
7. **Never** delete `ACT_HI_*` rows by hand, switch Flowable's own history cleaning on in parallel, or move archives
   out of the store without updating `run_archive` (the download checks the checksum and reports a mismatch as 503).

## Evidence

Backend (real PostgreSQL 18 and Flowable 8 through Testcontainers):

- `AlertRulesTest` (5): `alert.manage` is required and workspace-wide only; validation of scopes, destinations and
  adapters; fires once on an open circuit, repeated evaluations neither reopen nor renotify, Slack and PagerDuty stub
  posts (no secret value in the body), resolve notifications, quiet-period suppression of a re-fire and its
  resolution, silence; failing deliveries retried to `failed` after 5 attempts (`secret_missing`); webhook
  destinations queue an `alert.fired` S99 delivery without a request object; test send; every signal query runs and
  is scoped per workspace and connection; editing the threshold closes the open incident without a notification.
- `RunRetentionTest` (3): policy rights (administrator sets, operator 403, viewer 403 on archives, version conflict
  409), request hold and workflow hold block, dry run changes nothing, purge needs confirm (422), archive, read-back
  and purge (request 404, run events and tasks gone, audit kept, open request untouched), engine history rows gone,
  archive listed and downloaded with matching SHA-256 and the expected entries; the database function refuses open
  and unarchived requests and the runtime role still cannot delete `run_event`; **coverage guard**: every table that
  references a request (foreign-key closure from `workflow_run`) or has a `run_id`/`last_run_id`/`parent_run_id`
  column is purged, detached or explicitly kept.
- `ArchiveStoresTest` (2): file system round trip and key escapes refused; S3 PUT/GET against a local WireMock with
  SigV4 headers (credential scope, signed headers, payload hash, date), 404 as empty, refusal as an error, no secret
  key on the wire.
- `SecurityMatrixTest`: the 20 new operations answer 404 to foreign principals and change nothing (the nine new
  tables in the row-count snapshot). `AccessApiTest`: `alert.manage` can be granted and revoked.

Frontend: `AlertRulesPanel.test.tsx` (3), `RunRetentionPanel.test.tsx` (2); mock backend `mocks/alertsRetention.ts`.

Totals of the full runs are in the commit message of this slice.

## Merge notes

- **Grantable permissions CHECK**: V370 recreates `permission_grant_permission_check` and
  `permission_grant_connection_is_workspace_wide` with every grantable permission on this base plus `alert.manage`
  (as V290 did). Wave D siblings that add grantable permissions (`run.bulk-operate`, `report.share`, ...) must be
  merged into one list in the last migration that recreates these checks. Also merge by hand: `Permission` enum
  (`ALERT_MANAGE` appended), `AccessService` grantable and workspace-wide lists, `AccessController.CreateGrantRequest`
  pattern, the two OpenAPI grant `permission` enums and the `Permission` schema list, frontend `AccessPanel` labels,
  `mocks/handlers.ts` `GRANT_KEYS`, `AccessApiTest` list.
- **No notification kind** added (`notification_kind_check` untouched), no new `ProblemCode`, no adapter kind change,
  no `webhook_subscription.events` change (alert deliveries use event types `alert.*` without a subscription event).
- **`orqix_purge_run`**: a table added by another branch that references `workflow_run` (or a task, effect or other
  dependent row) or has a `run_id` column must be added to the function's list in a new migration
  (`CREATE OR REPLACE FUNCTION`), or to the kept list in `RunRetentionTest`; the coverage test fails otherwise. Bulk
  operation items (CG-O01) are the likely case.
- OpenAPI: tag `AlertsAndRetention` after `TaskActions`; paths in one comment-delimited block at the end of `paths`
  (just before `components:`); schemas in one block just before `InboundAccepted`. Regenerate
  `frontend/src/api/schema.d.ts` after merging.
- Shared files touched (additive): `AuditWriter` (17 actions), `WebhookService` (`recordAlert`,
  `subscriptionExists`), `RunEvidencePack` (`build` takes ids instead of a caller; new `archive`), `Permission`,
  `AccessService`, `AccessController`, `application.yaml` (`orqix.alerts`, `orqix.retention` before
  `orqix.callbacks`), `application-test.yaml`, `SecurityMatrixTest`, `AccessApiTest`, frontend `SettingsPage`,
  `AccessPanel`, `api/endpoints.ts`, `api/types.ts`, `mocks/state.ts` (`alertManagers`), `mocks/handlers.ts`,
  `mocks/browser.ts`, `test/server.ts`, `test/setup.ts`.
- Compiler untouched (no version bump).

## Not done

- Live email, Teams, Slack and PagerDuty delivery (stubs only, as for every adapter in this build); signal values for
  package polling triggers (only poll waits) and per-binding latency or success-rate rules.
- Retention by workspace default, by outcome or for cases; archive encryption at rest beyond the store's own; object
  lock / WORM settings on the bucket (a store setting, documented as recommended); deleting archives after a second
  period.
- Restoring a purged request into the engine (by design: the archive is the record).
- Partitioning `ACT_HI_*` (rejected above).
