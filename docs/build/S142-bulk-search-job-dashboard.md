# S142 - Bulk operations by filter, search by data and the integration and job dashboard (CG-O01, CG-O04, CG-O05)

8 November 2026. Branch `claude/wave-d-bulk-search-a0d081`, based on `claude/camunda-features-gap-a441b7` at `245d172c`
(Waves A-C), with the coordinator's later fixes up to `9ef01db3` merged in. Plan: [doc 29 §9.6](../29-camunda-gap-implementation-plan.md)
and the design rule of §4 row 12 (the Camunda 8.10 batch-cancel regression). Flyway V350-V353 (range V350-V359 reserved
for this slice). Builds on S5/S37/S38 (single-request retry, cancel, suspend, resume and migration), S129 (polling
checks), S131 (connection health), S133 (package triggers) and S32 (external jobs).

## CG-O04 Search by data

**Declaring.** A request form field may carry `searchable: true` (workflow schema `scalarFormField`, the same place as
`incidentEditable`). Compiler rules (`SearchableFieldRules`, no version bump, BPMN unchanged apart from the semantic
hash):

- `WF_SEARCHABLE_FIELD` (blocker): only request questions of type text (not files, times of day or date-times), choice,
  number, whole number, yes/no or date; never list items, row columns or step/result forms.
- `WF_SEARCHABLE_LIMIT` (blocker): at most 10 per workflow.
- `WF_SEARCHABLE_LONG_TEXT` (warning): free text without `maxLength` <= 200; longer answers are not indexed.

**Projection.** `run_search_value` holds one row per run and searchable field: `value_text` (text lower-cased, choices
lower-cased, dates `YYYY-MM-DD`, yes/no `true`/`false`) or `value_number`. `RunStore` rewrites the rows in the same
transaction as every write of the request data: start (and child start), incident correction (S49), approver
correction (S42) and migration to another release (S38, whose searchable fields may differ). The fields come from the
run's pinned release, so a filter never sees other values than the run holds; there is no asynchronous projection
(doc 29 §4 row 5).

**Filtering.** `listRuns` takes `field=key:op:value` (repeatable, at most 5; ops `eq`/`prefix` for text, `eq`/`gte`/`lte`
for numbers and dates, `eq:true|false` for yes/no), `attentionReason` and `errorCode`. `listSearchableFields` lists the
fields of a workflow's latest releases.

Decisions:

- **Permission-filtered like run reads.** The run list still needs `run.read`. Field conditions read request values,
  so they also need `run.read-data` (403 otherwise, not audited, like `listRuns`); a person who may only see some runs'
  data cannot use value filters at all, which keeps the list from revealing values of runs they cannot open. Error-code
  and attention-reason filters are operational facts and need only `run.read`.
- Field conditions need `workflowId` (400 otherwise); unknown fields, operators that do not fit the kind and values of
  the wrong type are 422 with field errors on `/field/<i>`.
- **Error code.** The attention record now carries the failed step's `errorCode` when the step reported one
  (`FailedJobRecorder` for HTTP and adapter steps, `ExternalJobService` for worker business errors). The `Attention`
  schema gains the optional `errorCode`; `attentionReason` has its own `AttentionReason` schema. Partial index on
  `attention ->> 'errorCode'` for needs-attention runs.
- Field order: `inputUi.order` first, then by key (jsonb keeps no key order).
- **Studio**: request questions of a searchable kind get *Operators can search requests by this answer* under
  Advanced (next to the incident-correction switch); files, lists, times of day and date-times never get it.

## CG-O01 Bulk operations by filter

Actions: retry, cancel, suspend, resume, migrate to a release. Each item uses the single-request operation unchanged.

1. **Dry run** (`previewBulkOperation`, `run.operate` + the new grant-only `run.bulk-operate`; `run.read-data` too when
   the filter has field conditions). The filter is the run list's (`BulkRunFilter`) and must narrow the runs (a workflow,
   reference, dates, overdue, attention reason, error code or a field condition; lifecycle alone is refused). The
   action's state rule is added in SQL: retry = needs attention; cancel = open; suspend = open and not suspended;
   resume = suspended; migrate = open runs of the filter's workflow on another release (the target must be a release
   of that workflow). Both the run list and the dry run use the same `RunQueries.where`, so they match the same runs.
   The exact count is computed, and when it is within the cap (default 1,000, `orqix.bulk.max-items`, 1-10,000) the
   matching runs are copied as items in creation order in the same transaction; if the copy differs from the count,
   the copied items are the count that is shown. More than the cap stores no items (`exceedsCap`). The response shows
   the first 10 items. Valid for 15 minutes; changes no request. Audit `run.bulk-previewed`.
2. **Execute** (`executeBulkOperation`, idempotency key): only the person who ran the dry run (403 otherwise; others run
   their own); `confirmedCount` must equal the shown count (409 `BULK_COUNT_MISMATCH`); a reason (1-450 characters,
   422 `REASON_REQUIRED` when blank); 409 `BULK_PREVIEW_EXPIRED` after 15 minutes; 422 `BULK_LIMIT_EXCEEDED` over the
   cap; 409 `BULK_OPERATION_STATE_CONFLICT` when already confirmed or cancelled or nothing matched. Audit
   `run.bulk-started`.
3. **Batch** (`BulkOperationWorker`, every 2 s, `orqix.bulk.poll-delay`): leases due operations with
   `FOR UPDATE SKIP LOCKED` and a 60 s lease, so one pod works an operation at a time. Pacing is a token clock on
   `next_item_at` at the operation's `ratePerMinute` (1-600, default 60, `orqix.bulk.default-rate-per-minute`), at most
   25 items per tick and never more than one tick of banked credit (no burst after a pause). Per item:
   - **the set only shrinks**: the run is checked again against the stored filter and state rule; a run that no longer
     matches is `skipped` with `NO_LONGER_MATCHES`. No run outside the dry run's items is ever touched.
   - the single-request operation runs **as the confirmer**, rebuilt from their latest sign-in (as S141 does), with the
     run's current version, the idempotency key `bulk:<operation>:<run>` and the reason
     `"<reason> (bulk operation <id8>)"`. Its permission checks, state rules (unreconciled effects, closure policy,
     suspension blockers, migration compatibility), run events, notifications and audit (`run.cancelled`,
     `run.retry-requested`, ...) are exactly the API's.
   - `RUN_BUSY`, or a version race on a still active run, is retried up to three attempts; any other refusal fails the
     item with the API's problem code and message. `IDEMPOTENCY_KEY_REUSED` means an earlier attempt completed, so the
     item is `succeeded` with `ALREADY_APPLIED`.
   - before every tick the confirmer must still hold `run.operate` and `run.bulk-operate` and be enabled; otherwise the
     remaining items are cancelled (`PERMISSION_REVOKED`) and `run.bulk-stopped` is audited (system actor).
   - when no item is pending: `completed`, audit `run.bulk-completed` with the counts (system actor).
4. **Control** (version-checked): pause (running), resume (paused; only the confirmer, because the items run with their
   permissions) and cancel (previewed, running or paused; pending items end as `cancelled`). Audit `run.bulk-paused`,
   `run.bulk-resumed`, `run.bulk-cancelled`.
5. **Reads** (`run.operate`): list, one operation with progress and the first items, items by state with paging.

Other decisions: hard cap rather than paging through more; no "select all matching later" mode; dry run per person;
migrate needs the workflow in the filter; per-item results keep the reference and codes, never request values.

**UI.** Runs page: *Bulk action on these runs* (only with `run.operate` and `run.bulk-operate`) uses the current filters:
action (and target release for a move), dry run with the count and first requests, "Type N to confirm" and reason,
then progress with Pause, Resume, Cancel remaining and the result of each request. Filters gain *Error code* and, with a
workflow chosen and `run.read-data`, *Request field* conditions kept in the URL (`field=key:op:value`). Settings,
Members: label for `run.bulk-operate`.

## CG-O05 Integration and job dashboard

`getJobDashboard` (`run.operate`): counts, times and codes only, never job input, request data or secrets.

- **External job queues by topic**: available, locked, failed in 24 h, oldest available job and its age, when a worker
  last asked for the topic and how many workers asked in the last hour. `fetchExternalJobs` now upserts
  `external_worker_seen` (workspace, topic, worker id) at most once a minute per worker and topic.
- **Checks until done** (S129 poll waits) per connection operation: waiting, checking, needing attention, late (due more
  than a minute ago), next and last check.
- **Connector package triggers** (S133): kind, enabled, last run and success, lag of the last poll, last error code, dead
  records in 24 h, next run.
- **Connection calls** (S131 health windows, reused through the new `integration.IntegrationStatus` port so runtime does
  not reach into `integration.health`): calls and success rate in 1 h, calls and failures in 24 h, last error code,
  circuit state, notices. Present only when the caller also holds `connection.read`.

UI: Settings, *Operations* tab (`run.operate`), refreshed every 30 s, with "No worker" when jobs wait and no worker was
seen for 15 minutes.

## Contract and data

- OpenAPI block tagged **BulkAndSearch** (paths before `/workspaces/{workspaceId}/mcp-server`, schemas before
  `McpServer`): `listSearchableFields`, `previewBulkOperation`, `listBulkOperations`, `getBulkOperation`,
  `listBulkOperationItems`, `executeBulkOperation`, `pauseBulkOperation`, `resumeBulkOperation`, `cancelBulkOperation`,
  `getJobDashboard`. `listRuns` gains `field`, `attentionReason`, `errorCode` (and 400/422 responses); `Attention` gains
  `errorCode`; new `AttentionReason` schema. Permission tokens `run.operate + run.bulk-operate` (no new qualifier).
- Permission `run.bulk-operate` (grant-only, workspace-wide): `Permission` enum, `AccessService`, `CreateGrantRequest`
  pattern, OpenAPI `Permission` and both grant enums, `V350`.
- Problem codes `BULK_COUNT_MISMATCH`, `BULK_PREVIEW_EXPIRED`, `BULK_OPERATION_STATE_CONFLICT` (409),
  `BULK_LIMIT_EXCEEDED` (422), with frontend messages.
- Audit actions `run.bulk-previewed`, `-started`, `-paused`, `-resumed`, `-cancelled`, `-completed`, `-stopped`.
- Workflow schema: `searchable` on `scalarFormField`.
- Flyway: `V350__run_bulk_operate_permission.sql`, `V351__run_search_values.sql` (`run_search_value` and the
  attention error-code index), `V352__bulk_operations.sql` (`bulk_operation`, `bulk_operation_item`),
  `V353__external_worker_seen.sql`. App-role grants only (no DELETE on the bulk tables).

## Tests

- Compiler `SearchableFieldRulesTest` (3): scalar fields compile with an unchanged BPMN; long text warning; file and time
  fields and 11 fields blocked; list items blocked.
- `BulkAndSearchTest` (6, PostgreSQL/Flowable, `com.orqix.runtime.internal`):
  - search: fields listed in form order; three projection rows per run; text (case-insensitive), number ranges,
    prefix and combined conditions; viewer 403 with conditions but 200 without; requester 403; unknown field and wrong
    operator 422 with pointer; conditions without workflow 400; an approver-style correction re-indexes.
  - error codes: two runs that need attention after a 400 carry the step's error code; `errorCode` and
    `attentionReason` filters; then a bulk retry of exactly those runs completes them, one `run.retry-requested` each.
  - cancel: grant-only (operator 403, viewer 403); filter must narrow (422); exact count and sample; a request started
    after the dry run is never touched; wrong count 409, no key 400, blank reason 422; replay header; three
    `run.cancelled` audits and run events with the bulk reason; second execute 409; items and list.
  - shrinking set and control: a request cancelled on its own before its turn is skipped with `NO_LONGER_MATCHES`;
    at one request a minute the first item runs alone; stale pause 409; paused operations do nothing; resume; cancel
    of the rest; audits.
  - cap (lowered to 2): `exceedsCap`, no items, execute 422; expired dry run 409; losing the grant mid-batch cancels the
    remaining items with `run.bulk-stopped`.
  - dashboard: an external task topic shows two available jobs and no worker, then one locked and the worker seen;
    connections present for the operator; no request values; viewer 403.
- `SecurityMatrixTest`: the ten operations for foreign principals against a real dry run (created with a temporary
  `run.bulk-operate` grant that is removed again) are 404 and change nothing; the four new tables are in the row-count
  snapshot. `AccessApiTest`: `run.bulk-operate` can be granted and revoked. `ArchitectureTest` passes.
- Frontend: `BulkActionsPanel.test.tsx` (5: not offered without the grant; confirm count and reason, progress, pause,
  cancel, per-request results; cap; field conditions in the URL; no field filters without `run.read-data`),
  `JobDashboardPanel.test.tsx` (2), `studio/model/searchable.test.ts` (1: round trip, never on files). Mocks
  `mocks/bulk.ts`; mock `permissionsOf` honours `bulkOperators`; the mock run list applies `errorCode` and `field`.

## Results

See the session report (full backend `clean verify`, frontend typecheck, lint and tests, contract check).

## Merge notes

- **Grantable-permission CHECKs** (`permission_grant_permission_check` and
  `permission_grant_connection_is_workspace_wide`): V350 recreates both with every grantable permission on this base
  plus `run.bulk-operate`. A sibling migration that adds a grantable permission (for example `alert.manage` or
  `report.share`) must recreate both with the union, in the higher version. The Java lists to merge are
  `AccessService.createGrant`, `AccessController.CreateGrantRequest` (regex), the OpenAPI `Permission` enum and the two
  grant `permission` enums, `AccessApiTest.everyGrantablePermissionCanBeGranted`, the frontend mock `GRANT_KEYS` and
  the `AccessPanel` labels.
- **Problem code enum** (Java `ProblemCode`, OpenAPI `Problem.code`, `frontend/src/lib/problemMessages.ts`): four
  `BULK_*` codes added after `AI_SKILL_STATE_CONFLICT`.
- **Audit whitelist**: seven `run.bulk-*` actions after `run.suspended`.
- `Attention` (OpenAPI and `RuntimeDtos.Attention`) gains `errorCode`; `RuntimeDtos.Attention` has a sixth component.
- `RunStore` gains a constructor parameter (`ObjectProvider<RunSearchIndex>`), `RunService` one (`RunSearchIndex`),
  `RunQueries.Filter` three components (old constructors kept).
- No change to notification kinds, adapter kinds or the compiler version. Regenerate `frontend/src/api/schema.d.ts`
  after merging.
