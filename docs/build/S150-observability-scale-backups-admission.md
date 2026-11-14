# S150 - Observability, scale qualification, backups and admission control (CG-P06, CG-P01, CG-P03, CG-P05)

9 November 2026. Branch `claude/wave-e-platform-ce0c9a`, cut from `52769a2b` (Waves A-D merged). Doc 29 section 9.13.
Flyway V440-V441 (range V440-V449). OpenAPI: three operations tagged `Platform`, all under `/admin` and
`platform.admin`. No new grantable permission, no new `run_id` table, compiler version unchanged.

## 1. Summary

| Ticket | Delivered | Evidence |
|---|---|---|
| CG-P06 OpenTelemetry, metrics, dashboards, SLOs | Micrometer Tracing with the OpenTelemetry bridge on every pod; one span per HTTP request, per engine job (child of the run's start), per connector call and per model call; trace ids in logs, `run_event.trace_id` and `effect_journal.trace_id`; OTLP exporter off by default (`orqix.tracing.*`); 15 new meters for poll checks, circuits, bulk operations, alerts, analytics lag, AI tasks and admission; three Grafana dashboards, 7 recording rules and 14 alerts (`deploy/observability`), a PrometheusRule for OpenShift; five SLOs | `TracingAndMetricsTest` (start, job and connector call share one trace), `validate_manifests.py` observability section, section 2 |
| CG-P05 Admission control | Per-workspace limits of new requests and inbound messages per sliding minute; 429 `RATE_LIMITED` with `Retry-After`; audited once per minute; the same check for REST, Kafka, package triggers (webhooks), MCP, schedules, pages and conversations; Platform API to set limits | `AdmissionControlTest` (4), `KafkaConsumerTest.anAdmissionRefusalIsRetriedEvenOnTheLastAttempt`, section 3 |
| CG-P03 Backups and restore drill | Consistent logical backups with a MANIFEST of row counts and a sample request read in the dump's snapshot; WAL archiving, base backups and a point-in-time restore script for Compose; daily backup and quarterly drill CronJobs for OpenShift; a drill that restores into a scratch database and verifies counts and the sample request; runbook | `deploy/backup/README.md`, drill run in section 4 (T-RES-06 closed locally) |
| CG-P01 Scale qualification | `tools/perf/multipod.py` with a CI-safe `--smoke`, against one API pod and two worker pods (`deploy/compose/docker-compose.roles.yml`) | measured run in section 5 |

## 2. CG-P06 OpenTelemetry, metrics, dashboards and SLOs

### 2.1 Traces

- Dependencies (open source, Apache 2.0): `spring-boot-micrometer-tracing-opentelemetry` (Boot 4.0.8),
  `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` with the JDK HTTP sender (OkHttp and Kotlin are
  excluded). Not the Boot OpenTelemetry starter: it also adds the OTLP *metrics* registry, which would push metrics to
  `localhost:4318` by default. Prometheus remains the metrics path.
- Settings (`application.yaml`, env in brackets): `orqix.tracing.enabled` (`ORQIX_TRACING_ENABLED`, true),
  `sampling-probability` (`ORQIX_TRACING_SAMPLING`, 1.0), `otlp.enabled` (`ORQIX_TRACING_OTLP_ENABLED`, **false**),
  `otlp.endpoint` (`ORQIX_TRACING_OTLP_ENDPOINT`, `http://localhost:4318/v1/traces`), `environment`, `service-namespace`.
  They map onto `management.tracing.*` and `management.opentelemetry.*`.
- HTTP: Spring MVC's server observation (one span per request; W3C and B3 accepted).
- Engine jobs: `runtime/engine/JobTracing` is Flowable 8's `JobExecutionObservationProvider` on the async executor. One
  `orqix.job` span per job, with handler type, node id, run id, workspace id and process instance. Its parent is the
  W3C traceparent of the request that started the run, stored at the start in the process variable
  `orqix_traceparent` and read with one indexed query per job (only when tracing is on).
- Connector calls: `HttpExecutor` wraps every call in the observation `orqix.connector.call` (span + timer by effect
  class and outcome; connection and operation as span attributes). Model provider calls: `ProviderHttp` wraps each call
  in `orqix.ai.call` (by host and outcome). External worker jobs, poll checks and AI tasks run inside engine jobs or
  scheduled runs (Spring traces `@Scheduled` methods), so their writes carry those traces.
- Trace ids are persisted: `run_event.trace_id` (every timeline event) and `effect_journal.trace_id` (the latest
  attempt), V440. `shared/tracing/TraceIds` reads the current OpenTelemetry span statically, so no constructor changed.

### 2.2 Metrics

`runtime/internal/PlatformMetrics`: counters `orqix_poll_checks_total{outcome}`, `orqix_bulk_items_total{action,outcome}`,
`orqix_ai_tasks_ended_total{status}`, `orqix_admission_rejected_total{kind}`; database gauges (cached 15 s per pod)
`orqix_poll_waits_active`, `orqix_poll_waits_due`, `orqix_poll_check_lag_seconds`, `orqix_connection_circuits{state}`,
`orqix_bulk_operations_running`, `orqix_alert_incidents_open`, `orqix_analytics_outbox_pending`,
`orqix_analytics_outbox_dead`, `orqix_analytics_lag_seconds`, `orqix_ai_tasks{status}`, `orqix_ai_task_queue_age_seconds`;
timers `orqix_connector_call_seconds` and `orqix_ai_call_seconds`. Latency histograms are on for
`http.server.requests`, `orqix.connector.call` and `orqix.ai.call` (needed for the SLO quantiles). The alert meters of
S144 (`orqix_alerts_fired_total` ...) and retention meters already existed and are on the dashboards.

### 2.3 Dashboards, rules and SLOs

`deploy/observability/README.md` lists every metric, the SLOs and how to install the pack on Compose
(`docker-compose.observability.yml`: Prometheus, Grafana, Jaeger) and OpenShift (ServiceMonitor, PrometheusRule,
Grafana import, OpenTelemetry Collector). SLOs (30 days): API availability 99.5 % (multi-window burn-rate alerts
14.4x/6x), start p95 under 1 s, read p95 under 500 ms, oldest executable job under 60 s 99 % of the time, analytics lag
under 5 minutes 99 % of the time. The dashboards are generated by `grafana/generate_dashboards.py`; the validator
checks that every `orqix_*` metric they or the rules use is registered in the backend.

## 3. CG-P05 Admission control

- Every start passes `RunService.start` and every inbound message `MessageService.send`; both call
  `AdmissionControl` after the idempotency replay check (a replay is always answered) and before any lock.
- Count: committed rows of the workspace in the last 60 seconds (`workflow_run.created_at` on the existing index,
  `inbound_message.received_at` on the new index `inbound_message_ws_received`), reading at most `limit` rows. Exact
  across pods with no shared counter and no write on the start path.
- Above the limit: 429 `RATE_LIMITED` with `Retry-After` = seconds until the oldest counted row leaves the window
  (1-60). Counted in `orqix_admission_rejected_total{kind}` and audited as `admission.limit-exceeded` (kind and limit
  only) at most once per workspace, kind and minute on each pod, in its own transaction.
- Channels: REST start and message, MCP `start_<workflow>` tools and `send_message` (through `AssistantRuntime`), Kafka triggers
  (`KafkaDispatcher` maps the code to "retry"; the partition pauses with backoff up to 60 s, and the record is **not**
  dead-lettered for a rate limit however long it lasts), package triggers and their webhooks (HTTP 429 with
  `Retry-After: 60`; nothing is recorded, so the sender's retry is accepted later), schedules (the fire is recorded as
  failed, like any other refusal), app pages and conversations.
- Configuration: `orqix.admission.enabled` (true), `starts-per-minute` and `messages-per-minute` (default **0 = no
  limit**), `limit-cache-ttl` (10 s). Overrides per workspace in `workspace_admission_limit` (V441):
  `GET /admin/admission`, `GET|PUT /admin/workspaces/{workspaceId}/admission` (platform administrators; PUT audited as
  `platform.admission-updated`). A null field keeps the default, 0 removes the limit for the workspace.

## 4. CG-P03 Backups, point-in-time restore and the restore drill

### 4.1 What was built

- `deploy/backup/scripts/orqix-backup.sh` (POSIX sh, runs in the `postgres:18-alpine` image): opens a session that
  exports a snapshot, reads the row counts of 22 tables (application, effects, audit, files, engine runtime and history)
  and a sample request (id, reference, lifecycle, release, event sequence, event and effect counts, input digest) **in
  that snapshot**, dumps with `pg_dump -Fc --snapshot`, writes `MANIFEST` and `SHA256SUMS`, verifies them, prunes sets
  older than 14 days. It refuses to finish when a verification value is empty.
- `orqix-restore-drill.sh`: checksums, `CREATE DATABASE orqix_drill_<ts>`, `pg_restore --no-owner --no-privileges
  --exit-on-error`, the same queries on the copy, line-by-line comparison with the MANIFEST (it fails when the MANIFEST
  lacks any expected line), JSON report in `/backups/drills/`, `DROP DATABASE`. Exit 1 on any mismatch.
- Compose: `docker-compose.backup.yml` turns on WAL archiving (`archive_timeout` 300 s, append-only archive), adds a
  `backup` service (first backup once the schema exists, then every 24 h); `compose-base-backup.sh` (`pg_basebackup`
  tar + gzip with checksums, keeps 4); `compose-pitr-restore.sh` (scratch server from the newest base backup, WAL
  replay to `--target`, promote, summary).
- OpenShift: `deploy/backup/kustomization.yaml` with PVC, CronJob `orqix-backup` (01:30 UTC daily), CronJob
  `orqix-restore-drill` (quarterly), generated script ConfigMap; Secrets by reference only. Checked by
  `validate_manifests.py` (15 new checks).
- Runbook: `deploy/backup/README.md` (schedule, RPO/RTO, OpenShift setup, drill, Compose PITR, effect reconciliation
  before users resume).

### 4.2 Measured (T-RES-06, closed locally)

On the private roles stack right after the 5-minute load of section 5, with both workers still checking 491 poll waits:

| Step | Result |
|---|---|
| Logical backup | 9 s, dump 6.1 MB, 22 tables counted, snapshot-consistent while the system was writing |
| Restore drill | restore into `orqix_drill_20261110040534` in **92 s**, **23 checks, 0 mismatches** (22 counts + sample request): 1,890 requests, 4 releases, engine runtime and history tables intact, 1 effect to reconcile before resuming (an in-flight poll check at backup time); scratch database dropped |
| Base backup | 198 s (`--checkpoint=fast`, compressed; the machine was also running the backend test suite) |
| Point-in-time restore | marker row inserted 2 s after the target time: restore **to the target** shows 0 marker rows, restore **to the end of the archive** shows 1; each recovery 19-21 s |

The first drill attempt exposed a defect in the verification queries (`query_to_xml` in table mode returned NULL, so no
count was compared and the drill passed with 1 check). Fixed (table-forest mode) and both scripts now refuse empty or
missing verification lines; the numbers above are from the fixed scripts. Evidence (git-ignored):
`evidence/s150/drill-20261110T040534Z.json`, `manifest-20261110T040521Z.txt`.

Not run here: the OpenShift CronJobs on a cluster (rendered and validated only), and a drill on a production-sized
database (restore time grows with the dump; schedule the first drill on a copy of production before relying on RTO).

## 5. CG-P01 Scale qualification with API and worker pods

### 5.1 Harness

`tools/perf/multipod.py` (README section "Runtime roles"). It creates a read-only connection to the stub, publishes a
purchase approval and a variant that keeps checking the stub every 10 s after the order, and runs closed-loop users:
start, wait for the task, claim, approve, wait for completion (or for the watch step). Metrics are scraped from all
three pods every 10 s. Checks: the API pod made no connector calls or poll checks, both workers did, all work finished,
no dead-letter jobs; exit 1 otherwise. `--smoke` (3 users, 75 s) is the CI-safe mode; nothing runs it automatically.

### 5.2 Measured run

Stack: `docker compose -p orqix-roles -f docker-compose.yml -f docker-compose.roles.yml -f docker-compose.backup.yml`
with image `orqix/orqix:wave-e-platform` built from this branch; one API pod (`dev,api`), two worker pods
(`dev,worker`), one PostgreSQL 18, no CPU limits. Host: Windows 11, 8 logical CPUs, Docker Desktop, **shared**: the
backend test suite (Testcontainers) and another project's container (about 1 CPU) ran at the same time.

Smoke (3 users, 75 s): 0 problems; connector calls API 0 / worker-1 86 / worker-2 79.

Run: 20 users, 30 s ramp, 5 minutes, 25 % of starts end in a polling step.

| Measure | Result |
|---|---|
| Starts | 1,815 in 302 s = **6.0 starts/s** (closed loop; 0 errors in 5,445 measured calls) |
| Start latency p50 / p95 / p99 / max | 0.33 / **1.46** / 2.85 / 6.9 s |
| Task latency (start answered to task claimable) p50 / p95 | 0.07 / 0.21 s. The purchase approval's first task is created inside the start transaction, so this is read-after-write, not worker pickup |
| Claim / decide p95 | 0.38 / 0.51 s |
| Completion latency (approval answered to request completed: worker job, connector call, end) p50 / p95 / p99 | 1.16 / 1.93 / 2.49 s |
| Connector calls by pod | API **0**, worker-1 2,752, worker-2 2,688 (51 / 49 %); effect lease owners: the two worker hosts only |
| Poll checks | 3,625 checks by the workers (12.0/s); 475 requests reached the watch step, 479 active waits at the end. Against the nominal schedule (active waits / 10 s, integrated over the run: 6,821) that is 53 %: each wait was checked about every **14.6 s** instead of 10 s, with the most overdue check 3-4 s late at the end. Worker CPU stayed below 15 % of the host and no connection waited, so the limit is the scheduler's cadence (the next check is planned from the last check, after a claim delay), not capacity |
| Analytics lag (sampled, 15 s gauge cache) | p50 4.7 s, max 8.6 s |
| Oldest executable job | at most 1.8 s |
| Pools | API pod up to 14 of 15 connections busy; workers up to 6; 0 pending |
| Consistency | 1,340 approvals = 1,340 completions; 0 dead-letter jobs |

Reading: with the roles split, the API pod is the busy one (its pool reached 14 of 15 and the start p95 of 1.46 s is
above the 1 s SLO on this shared machine), and the workers have ample headroom at this load. The poll cadence finding
is recorded as follow-up F-P01. Evidence (git-ignored): `evidence/s150/multipod-run-20u-5m.json`,
`multipod-smoke-20261110T035348Z.json`.

**Limits, stated plainly.** One machine, shared with other work; client threads on the same host; loopback latency;
one PostgreSQL without CPU limits; one workspace; 5 minutes. These are relative numbers for this topology, not a
capacity statement. Not measured here: more than two workers, a worker failure under load (S92 covers a replica kill),
a large database, and the observability overlay itself (Prometheus, Grafana and Jaeger were not started: their images
were not on the machine).

## 6. Decisions (doc 29 is silent on these)

| # | Decision | Why |
|---|---|---|
| D1 | Tracing on by default, exporter off; sampling 1.0 by default | Trace ids in logs, timeline and journal cost little and help support; nothing leaves the pod unless configured. Operators lower sampling at volume |
| D2 | No trace headers on connector and model calls | Trace context is internal; third parties get nothing they did not get before |
| D3 | The run's traceparent is written once, at the start; later engine jobs join the start's trace | Updating the variable at every decision would make parallel branches conflict on one variable row. The decision's own trace id is on its timeline event |
| D4 | Micrometer bridge plus OTLP trace exporter, not the Boot OpenTelemetry starter | The starter pushes OTLP metrics to localhost by default; Prometheus stays the metrics path |
| D5 | Database gauges are cached 15 s per pod and identical on every pod; rules use `max()` | Same convention as S1 gauges; bounded scrape cost |
| D6 | Admission limits default to 0 (no limit) | S92 measured more than 400 starts per minute in one workspace; a default limit could break existing load. Platform administrators set limits per workspace |
| D7 | Admission counts committed rows of the last minute, no counter table | Exact across pods, no hot row, no write on the start path; in-flight starts can overshoot by the concurrency (protection, not billing) |
| D8 | Child requests count towards the workspace's start limit; only entry points are refused | Child requests are load too; refusing them mid-run would fail running requests |
| D9 | Kafka records refused by admission are never dead-lettered | A limit is back-pressure, not a defect |
| D10 | Audit at most once per workspace, kind and minute per pod | A refused burst would otherwise write one audit row per refusal |
| D11 | Admin endpoints under `/admin` (tag Platform) with `platform.admin`, no new permission | Matches the security matrix family `/admin/*` (403 for others); no `permission_grant` change |
| D12 | Logical backups in the snapshot of their own MANIFEST | Exact verification of a restore while the system keeps running |
| D13 | Drill restores with `--no-owner --no-privileges` into a generated scratch database | Works on a separate drill server without Orqix roles; cannot touch production |
| D14 | PITR on OpenShift belongs to the database platform | Orqix does not run the production database; the runbook lists what to configure and rehearse |

## 7. Changes

61 files, about 6,200 lines (most of it the generated dashboards, the contract and the generated frontend types).

| Area | Files |
|---|---|
| Backend, tracing | `pom.xml`, `shared/tracing/TraceIds`, `runtime/engine/JobTracing`, `FlowableEngineConfiguration`, `EngineCommands` (traceparent at start), `RunEvents`, `EffectJournal`, `HttpExecutor`, `ai/provider/ProviderHttp`, `application.yaml` |
| Backend, metrics | `runtime/internal/PlatformMetrics`; counters in `PollWaits`, `BulkOperationWorker`, `AiTaskJobs` |
| Backend, admission | `AdmissionSettings`, `AdmissionControl`, `runtime/api/AdmissionController`, `RunService`, `MessageService`, `PackageTriggerService` (429), `KafkaRecordHandler` (no dead letter for rate limits), `AuditWriter` (2 actions) |
| Migrations | `V440__trace_ids.sql`, `V441__workspace_admission_limits.sql` |
| Contract | `contracts/openapi.yaml` (tag Platform, 3 operations, 5 schemas, 429 on `receivePackageTriggerEvent`), `frontend/src/api/schema.d.ts` regenerated |
| Tests | `AdmissionControlTest` (4), `TracingAndMetricsTest` (2), `KafkaConsumerTest` (+1), `SecurityMatrixTest` (sample body for `setWorkspaceAdmission`) |
| Deploy | `deploy/observability/**`, `deploy/compose/docker-compose.{observability,roles,backup}.yml`, `deploy/backup/**`, `deploy/openshift/optional/prometheusrule.yaml` |
| Tools and docs | `tools/perf/multipod.py`, `tools/perf/README.md`, `tools/validate-manifests/validate_manifests.py`, `docs/admin-guide.md`, `docs/feature-status.md` (T-RES-06) |

## 8. Verification

| Check | Result |
|---|---|
| `backend/mvnw -o clean verify` | BUILD SUCCESS: compiler 1,224 tests, app 1,419 tests (5 skipped: opt-in live and load tests), 0 failures. A first full run (while the image build and the load run shared the machine) found the controller/JDBC architecture rule, the security matrix answering 400 instead of 403 for `setWorkspaceAdmission` (now authorization before validation, plus a sample body), and three timing failures in `BankingWorkflowsTest` / `BulkAndSearchTest` that passed when rerun alone and in the final run |
| Frontend `typecheck`, `lint`, `test` | pass; 1,785 tests (two AI skills page tests timed out at 15 s while the machine was saturated and passed when rerun alone) |
| `api:check` | schema.d.ts matches the contract |
| `python tools/contract-check/contract_check.py` | PASS, 142 checks |
| Redocly lint | valid, 75 warnings (the same 75 as on `52769a2b`) |
| `python tools/validate-manifests/validate_manifests.py` | PASS, 179 checks (4 overlays, backups, observability) |
| `docker compose config` with all overlays | valid |
| Runs on Compose | smoke and 5-minute harness run (section 5), backup, drill and PITR (section 4) |

Not run: promtool on the rules and the observability overlay itself (Prometheus and Jaeger images were not on the
machine; the rules and dashboards are checked structurally and against the backend's metric names), the OpenShift
CronJobs on a cluster.

## 9. Open and follow-ups

- **F-P01** Poll cadence: with two workers and 480 waits every 10 s, each wait was checked about every 14.6 s at low
  CPU. Plan the next check from the planned time (not from the last check) and/or claim larger batches per tick; then
  rerun `multipod.py` and compare the measured rate with the schedule (S129 PollLoadTest measures one context only).
- No UI for admission limits yet (Platform API only); a Settings page for platform administrators is a follow-up.
- The worker SDK does not forward a traceparent yet, so external worker jobs show the worker's HTTP calls as their own
  traces, linked by run id and node id, not by trace parent.
- PITR on OpenShift is rehearsed through the database platform, not by a manifest of this repository.
- Scale numbers are from one machine; a pilot environment must repeat `tools/perf/multipod.py` with its own topology.
