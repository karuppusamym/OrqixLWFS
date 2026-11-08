# S145 - Analytics read model: heatmaps, KPI targets and human work analytics (CG-N01, CG-N03, CG-N05)

Wave D of [doc 29](../29-camunda-gap-implementation-plan.md) section 9.7, decision D-CG6: reporting tables in
PostgreSQL fed from the run timeline through an outbox, refreshed incrementally. Heavy analytics never aggregate the
operational tables (`workflow_run`, `task_context`, `run_event`); Insights (S24, S43, S102) is unchanged.

## Read model

**Feed.** `AnalyticsOutboxWriter` (runtime) listens to `RunEvents.Appended`, the same in-transaction event the
outbound webhooks use (S99 B2), and inserts one `analytics_outbox` row for each event that can change a run's figures:
run ended (`RUN_COMPLETED`, `RUN_CANCELLED`, `REQUEST_ENDED`), task events (`TASK_CREATED`, `TASK_CLAIMED`,
`TASK_RELEASED`, `TASK_REASSIGNED`, `TASK_DECIDED`, `TASK_TIMED_OUT`, `TASK_ESCALATED`), `STEP_INTERRUPTED`,
`SECTION_LEFT` and `HISTORY_CHANGED`. The row is part of the event's transaction: never lost, never present for a
rolled-back change. Only the run id travels.

**Projection.** `AnalyticsProjector` (runtime, every `orqix.analytics.projector-tick`, default 5 s, at most
`orqix.analytics.batch` = 200 runs and 20 s per pass) takes the runs with due rows and, per run, in its own transaction:

1. `pg_try_advisory_xact_lock` on the run, so a run is never projected twice at once on any replica;
2. `SELECT ... FOR UPDATE SKIP LOCKED` of exactly the outbox ids it saw;
3. rebuilds every fact of the run from its timeline and tasks (delete and insert, so projecting again gives the same
   rows);
4. deletes exactly those ids. An event committed meanwhile keeps its own row and is projected in the next pass.

A failure rolls the run back and retries its rows with backoff (30 s doubling, at most 1 h); after
`orqix.analytics.max-attempts` (5) they stay as failed rows and are counted on `getAnalyticsStatus`. A restart loses
nothing: the queue is the table. A run that no longer exists (purged by retention) loses its facts.

**Facts.** `analytics_run` (cycle time, human task count, automated), `analytics_step_visit` (one row per finished
visit, with the same visit derivation as the run diagram, `RunPathBuilder`, S24), `analytics_route` (connections a
run used), `analytics_task` (waiting versus working time, claims, returns, reassignments, SLA result). Fact tables
carry the workflow, release and run start time for one index range per report, and have **no foreign keys to runs**,
so run retention is not blocked. Histories over 5,000 events keep run and task facts and skip visits and routes (as
the run diagram does).

**Backfill.** `startAnalyticsBackfill` (`run.operate`, audited `analytics.backfill.requested`) queues every run of the
workspace, optionally only runs started since a UTC date, skipping runs already queued. It is restart-safe and
idempotent because the projector is. `AnalyticsProjector.runFor(runIds)` projects chosen runs at once (tests, and
operators on a console).

## CG-N01 Heatmaps

`getAnalyticsHeatmap` (`run.read`): for one workflow and optionally one release, runs started in the range: per step
finished visits, distinct requests, average, p95 and longest time; per connection the requests that used it and their
share; plus `freshness` (newest projection, pending updates). Ids are those of the release definition.

- **Run diagram**: a "Heatmap" switch (people with `run.read` only) overlays a badge on each step (visits, average,
  p95, colored by p95 against the slowest step) and the request count on each connection (line width by use), for
  this request's release or all releases and the last 7, 30 or 90 days; "Heatmap as a table" gives the same numbers.
- **Studio canvas**: the same switch in the canvas toolbar, with a release list (or all releases). The draft is
  overlaid by step and connection id; a step added since the release shows nothing, and the table names ids that are
  no longer in the draft.

## CG-N03 KPI targets

`getWorkflowKpiTargets` (`workflow.read`) and `setWorkflowKpiTargets` (`workflow.edit`, audited
`analytics.kpi-targets.updated`, optimistic `expectedVersion`, 0 when none) per workflow: median and p95 cycle time
(1 s to 366 days, median not above p95), automation rate and SLA attainment (shares 0 to 1), and an optional delay
cost per hour with a currency. Every target is optional; sending none clears the row and its states.

`getKpiOverview` (`run.read`) is the business value view: workflows with targets measured over runs completed and
tasks ended in the range. Cycle times are percentiles of completed runs; automation rate is the share of completed
runs without a human task; SLA attainment is met / (met + missed) over tasks with a due time. Fewer than 5 samples is
`insufficientData`. `excessHours` is the time completed runs spent over the cycle-time target (p95 target, else the
median target); `delayCost` = excess hours x cost per hour. Off-target workflows come first, the costliest first.
Insights shows it as "Targets and business value", with the target form when one workflow is selected and the person
may edit workflows.

**Alert hooks.** `com.orqix.analytics.KpiAlertHook` (public interface of the new `analytics` module) gets a
`KpiStatusChange` (workspace, workflow, measure, previous and new status, actual, target, samples, window) when a
measure's state changes. `KpiEvaluator` evaluates every workflow with targets over the trailing 30 days every
`orqix.analytics.kpi-check-interval` (default 15 min), under an advisory lock (one replica), stores the state in
`analytics_kpi_state` and calls the hook beans after commit; a failing hook is logged and does not stop the others.
No delivery is built here: alert rules (CG-O06) implement the hook.

## CG-N05 Human work analytics

`getHumanWorkAnalytics` (`run.read`): tasks that ended in the range per workflow step and candidate group (finished,
average and p95 waiting and working time, SLA met and missed, attainment, reassignments) and per group the load now
(open, not picked up, oldest open) and the same figures. Waiting is time with nobody assigned, working is time with
someone assigned, from the claim, return and reassignment events of the step while the task was open. When another
task of the same step was open at the same time (several approvers, list items) the events cannot be told apart: the
task falls back to its last claim time, is counted as `approximate`, and its reassignments are not counted. A task
created already assigned is recognised by its first assignment event (a return, or a reassignment away from someone).
Insights shows it as "Human work".

## Decisions taken where doc 29 is silent

- Facts per visit and per task rather than daily rollups: percentiles cannot be rolled up exactly, and the fact
  tables are far smaller than the timeline. Daily rollups can be added under the same feed if volume requires.
- Projection is per run and re-derives the run from its timeline, instead of applying events one by one: simpler,
  idempotent, and consistent with the run diagram.
- Heatmap and KPI windows: heatmaps by run start, KPIs and human work by end time (a run counts for the period it
  finished in).
- Reads need `run.read` (as Insights); targets are workflow settings; backfill needs `run.operate`. No new permission.
- No request data, titles, people or comments in any output; group names come from `workspace_group`.
- The evaluator's window is fixed at 30 days and the minimum sample at 5; neither is configurable yet.

## Contract and data

- Flyway `V380__analytics_read_model.sql` (V381 to V389 unused): `analytics_outbox` (bigserial; the runtime role gets
  USAGE on its sequence), `analytics_run`, `analytics_step_visit`, `analytics_route`, `analytics_task`,
  `analytics_kpi_target` (FK to `workflow`), `analytics_kpi_state`.
- OpenAPI tag `Analytics` (seven operations), schemas `Analytics*`; `frontend/src/api/schema.d.ts` regenerated.
- New module `com.orqix.analytics` (allowed: shared, identity, audit); runtime gains `AnalyticsOutboxWriter`,
  `AnalyticsProjector`, `AnalyticsTaskTimes`.
- Settings: `orqix.analytics.projector-tick` (5s), `batch` (200), `max-attempts` (5), `kpi-check-interval` (15m).
  The test profile sets both ticks to 1h; tests call `runFor` and `KpiEvaluator.evaluate` themselves.

## Tests

- `AnalyticsApiTest` (real PostgreSQL and Flowable, responses validated against the contract): access (viewer and
  operator 200, requester 403, other workspaces 404, access before parameters), parameter checks; heatmap from four
  real requests (visits, finished visits only, connection shares, release filter, no request data), backfill twice
  (no double queueing, audited) and identical facts after projecting again; human work with claim, return, claim,
  reassignment to the group, claim and decision (3 claims, 1 return, 1 reassignment, waiting + working = task
  duration), load per group; KPI targets (403 for viewers, 422 for median over p95 and for a cost without currency,
  409 on a stale version, audit), ranking and delay cost (6 h over target x 50 EUR = 300 EUR), alert hook called once
  per change and not again without change, clearing targets; facts of a purged run removed.
- `AnalyticsTaskTimesTest` (unit): event-based split, pre-assigned task, fallbacks and approximate tasks.
- `SecurityMatrixTest`: the seven operations are in the generated foreign-principal matrix (404, no state change;
  `analytics_kpi_target` and `analytics_kpi_state` in the snapshot) and pinned to their permissions and audit names.
- Frontend `analytics.test.tsx`: heat levels and texts, run diagram overlay for an operator and no switch for a
  requester, KPI table, target form sends the version read, human work table. Mock mode serves all seven operations
  (`src/mocks/analytics.ts`, heatmap derived from the mock runs).

## Results

See the session report for the full backend `verify` and frontend check totals of this branch.

## Merge notes

- **No shared CHECK constraint or enum rebuilt.** No change to the Permission enum, problem codes, notification kinds
  or the compiler (version unchanged).
- Shared files touched (additive): `AuditWriter` (two actions after `webhook.delivery-dead`), `SecurityMatrixTest`
  (two sample bodies, two snapshot tables, one test), `application-test.yaml` (`orqix.analytics`), `openapi.yaml`
  (tag after Insights; paths before `runs/{runId}/cancel`; schemas after "end of Waits"), `schema.d.ts` (regenerate
  after merging), `RunDiagram.tsx`/`RunEdge.tsx` and Studio `CanvasView.tsx`/`RouteEdge.tsx` (overlay hooks),
  `InsightsPage.tsx` (two cards), `mocks/insights.ts` (spreads the analytics handlers).
- **For retention (CG-O07)**: analytics facts have no FKs to runs; purging a run should also enqueue an
  `analytics_outbox` row for it (or delete `analytics_*` rows by `run_id`); the projector then removes its facts.
- **For alert rules (CG-O06)**: implement `com.orqix.analytics.KpiAlertHook` as a bean; the module allows that
  dependency direction (`alerts -> analytics`).
- **For migration and move (CG-O02, CG-O03)**: if moving a run emits a timeline event, add its type to
  `AnalyticsOutboxWriter.TYPES` so the run is re-projected with its new release.
- Pre-existing on the base (not from this branch): `ArchitectureTest.modulithStructureIsValid` fails because
  `runtime.internal.WaitReminders` (S139) calls `com.orqix.compiler.internal.json.StrictJson`.

## Not done

- Report builder (CG-N02), outlier analysis (CG-N04) and event-log export (CG-N06).
- Alert delivery (CG-O06) and purge of analytics facts with run retention (CG-O07).
- No volume measurement of the projector yet (S92-style); one replica and test data only.
