# S92 - Scalability qualification (horizontal scale, load, failover, volume)

26 October 2026. Branch `build/scale-qual` (cut from `314d889`). Requirement: "make sure the workflow application is scalable". This note records what was reviewed, what was measured, what was fixed and what is still open. **All numbers were measured on one shared Windows laptop; they are relative evidence (1 vs 2 replicas, before vs after a fix), not production capacity.**

## 1. Verdict in one paragraph

The application is **cluster-correct**: two replicas behind a non-sticky load balancer served 150 virtual users with zero lost or duplicated requests or effects, and when one replica was SIGKILLed mid-load the survivor took over its Flowable jobs after the lock expiry, re-sent in-flight effects with the same idempotency key (the remote system de-duplicated them) and every approved request completed exactly once. It is **not throughput-scalable by adding replicas** on this hardware: with two replicas PostgreSQL (3 CPU) saturated and request starts in one workspace are serialized by the `ORQ-n` counter row lock, so throughput did not rise (141 -> 123 req/s) and tail latency got worse. On a database with 111,000 requests and 8,000 open tasks throughput halved again (58 req/s), dominated by Insights aggregates and inbox counts. Fixed here: one index migration (V155, four indexes) and `plan_cache_mode = force_custom_plan` on every connection (it removes a pre-existing generic-plan failure that made `/insights/steps` answer 503 on every call). The start serialization, Insights cost and inbox counts are design-level and remain open (section 6, F1, F2, F16).

## 2. Environment and method

| Item | Value |
|---|---|
| Host | Windows 11, 8 logical CPUs, 16 GB, Docker Desktop (WSL2). **Shared** with other stacks (another Orqix demo stack, `analystos-*` using about 1 CPU, Testcontainers runs of other sessions) |
| Stack | Private Compose project `orqix-scale-1026` (ports 48xxx), image `orqix/orqix:scale-1026` built from this branch; `deploy/compose/docker-compose.yml` + new `docker-compose.scale.yml` (second replica `orqix-2` + nginx LB) + a private override (CPU/memory limits, `pg_stat_statements`) |
| CPU limits | app replicas 1.5 CPU each (first run: 2), PostgreSQL 18 3 CPU (first run: 2), 512 MB shared_buffers, max_connections 100; Keycloak, stub and LB unlimited |
| App settings | defaults: Hikari 15, Flowable executor core 4 / max 6, lock time 60 s, reset-expired 15 s, scheduler pool 4 |
| Load | `tools/perf/scale.py` (new, reuses the `tools/api-e2e` OIDC client): 150 VUs ramped over 120 s, 8 min; 60 % workers (T-PERF-01 loop: start, find in claimable inbox, read, claim, approve, poll run to completion), 40 % readers (operator/viewer: run list + page 2 + run detail + filtered list, Insights summary/throughput/steps every 4th loop, shared assets, catalog, notifications; manager: claimable, assigned and completed inboxes). Closed loop with 1-3 s think time, so throughput is an outcome, not an input |
| Measurement | client wall time over loopback (Python threads on the same host, so the client competes for CPU); `docker stats` every 10 s; Hikari active/pending from each replica's Prometheus endpoint; `pg_stat_statements` (reset per run); consistency read from the API, the stub and SQL at the end |
| Volume | `tools/perf/seed_large.sql`: 100,000 synthetic requests over 180 days (90 % completed, 7 % waiting with an open task, 2 % cancelled, 1 % needs attention), 300,000 tasks, 300,000 decisions, 800,000 run events, 50,000 notifications; plus about 11,000 real requests from the load runs. Seeded in 96 s |
| Plans | `tools/perf/explain_hot.sql`: EXPLAIN (ANALYZE, BUFFERS) of 16 hot queries, including the optional-filter run list as a generic prepared plan; `tools/perf/probe_volume.py`: single-user API latency of 25 list/search/Insights calls |

Evidence files (git-ignored) are in `evidence/s92/` of the worktree: `run-*.json` (full results incl. per-second timeline and samples), `explain-before.txt`, `explain-after.txt`, `probe-before.json`, `probe-after.json`, `seed.log`, `migrate-v155.log`.

## 3. Multi-replica stack and session handling

- `deploy/compose/docker-compose.scale.yml` adds `orqix-2` (same image and configuration, its own `HOSTNAME` = Flowable lock owner, `stop_grace_period: 60s`) and an `lb` service (nginx, `deploy/compose/lb/nginx.conf`). `LB_CONF` can point at a one-upstream config for single-replica runs.
- **Sticky sessions are not required, and were verified absent.** Sessions are Spring Session JDBC rows in PostgreSQL. Through the round-robin LB, the OIDC login (authorization request saved on one replica, callback served by either) succeeded for all 240 load sessions per run, and 20 consecutive `/me` calls of one session alternated 10/10 between the replicas, all 200.
- Forwarded headers: the app uses `forward-headers-strategy: framework`; the LB passes `Host`/`X-Forwarded-Host` with the published port and deliberately **no `X-Forwarded-Port`** (it would carry the container port 8080 and break the OAuth2 redirect URI - this was hit and fixed during setup).
- LB failover: passive health (`max_fails=1 fail_timeout=5s`), `proxy_next_upstream error timeout http_502 http_503`, 2 tries, 2 s connect timeout. nginx never re-sends a request whose body was already sent to a replica (no `non_idempotent`), so the LB cannot duplicate a start or a decision; client retries use the same `Idempotency-Key`.
- The private Keycloak got the redirect URIs `http://localhost:48080|48082|48084/login/oauth2/code/orqix` through its admin API only (`evidence/s92/kc_redirect.py`); the realm file is unchanged (S62/S84 convention).

## 4. Load results: one vs two replicas (150 VUs, 8 min)

Small database (a few thousand requests). Steady state = after the 120 s ramp. Latency p50 / p95 / p99 in ms.

| Run | Throughput req/s (steady) | Starts/min | Reads | Start | Claim+decide | Insights | Errors | DB CPU avg | App CPU avg | Hikari pending avg (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| A. 1 replica (2 CPU), DB 2 CPU | 107 | 276 | 661 / 1826 / 2585 | 933 / 2200 / 3105 | 761 / 1880 / 2594 | 656 / 1724 / 2330 | 0 | 210 % (cap 200) | 184 % | n/a |
| B. 1 replica (1.5 CPU), DB 3 CPU | 141 | 418 | 436 / 1099 / 1533 | 629 / 1360 / 1822 | 511 / 1172 / 1585 | 493 / 1127 / 1475 | 0 | 218 % | 150 % (cap) | 65 (98) |
| C. 2 replicas (1.5 CPU each), DB 3 CPU | 123 | 438 | 314 / 2115 / 3690 | 1400 / 4268 / 6649 | 463 / 2236 / 3584 | 633 / 2277 / 4109 | 15 (0.03 %) | 300 % (cap) | 90 % + 109 % | 35 (97) |
| D. 2 replicas, **large DB** (111k requests, 8,000 open tasks), V155 + `force_custom_plan` | 58 | 159 | 1194 / 5210 / 7600 | 1882 / 6351 / 30011 | 1281 / 4904 / 6992 | 5206 / 9819 / 11824 | 176 (0.6 %) | 294 % (cap) | 30 % + 31 % | 48 (102) |

Consistency in A, B, C: every started request was approved and completed; completed runs = stub orders = idempotency records = started; max one decision per task; every effect `succeeded` with 1 attempt; no Flowable dead-letter or leftover jobs.

What the numbers say:

1. **One replica was CPU- and pool-bound**: app at its 1.5 CPU limit, all 15 connections busy and on average 65 requests waiting for a connection.
2. **Two replicas moved the bottleneck to the database**: PostgreSQL at its full 3 CPU, app replicas at 60-70 % of their limit. Median read latency improved (436 -> 314 ms) but p95/p99 and starts got worse and throughput did not grow (141 -> 123 req/s). The 15 errors were client timeouts (30 s GET, 60 s POST) of requests queued behind the database.
3. **Starts are serialized per workspace.** `UPDATE workspace SET run_seq = run_seq + 1` (gapless `ORQ-n` reference, `RunStore.insert`) takes the workspace row lock at the beginning of the start transaction and holds it until commit, i.e. across the Flowable process start, projection, audit and idempotency writes. Its mean execution time (= lock wait) was 86 ms with one replica and **971 ms (max 11.7 s) with two**, the largest total time of all statements. While waiting, each start holds a pool connection, which feeds pool exhaustion on both replicas. Adding replicas cannot raise the start rate of one workspace above about 1 / (start transaction time).
4. **Insights aggregates are the largest CPU consumer per call and grow with history.** The Steps query (`approvalSteps`) went from 42 ms mean (run A) to 82 ms (B) to 263 ms (C) as the runs accumulated, and accounted for 128-353 s of database time per 8-minute run. Every Insights view is recomputed (nothing is cached) on every replica.
5. Per-request overhead visible in `pg_stat_statements`: Spring Session JDBC reads the session and updates `LAST_ACCESS_TIME` on almost every request (90k selects / 71k updates in run B); permission checks re-read workspace, groups and members per request (`freshAccess`, ~2.7 queries per request). Each is cheap (< 1 ms) but scales linearly with request rate and lands on the single database.

### 4.1 Run D - two replicas on the large database after the fixes

Same 150-VU profile against the seeded database after V155 and `plan_cache_mode`. Workers look for their task with `sort=newest` (the default inbox order is priority, oldest first; a first attempt without it, `run-2rep-150-large-invalid-sort.json`, found no task among 8,000 older seeded ones and is not used).

- Throughput halved compared with run C (58 vs 123 req/s, 159 vs 438 starts/min); PostgreSQL at its 3-CPU limit, the app replicas at 30 % each.
- Database time by statement (8 minutes): Insights `approvalSteps` **2,192 s** (600 calls, mean 3.65 s under contention vs 0.2 s alone), claimable **count** 1,639 s (3,373 calls, 486 ms mean), claimable list 788 s (543 ms mean), `run_seq` lock wait 572 s (394 ms mean, max 6.1 s), Insights cycle times / open steps 130-143 s each.
- So at volume the limits are, in order: Insights aggregates (F2), the inbox count and list over a large open backlog (F16), and the start lock (F1). None of them is relieved by more replicas.
- Consistency held: 1,452 started = approved = completed in the database = stub orders = idempotency records; effects all `succeeded`, 1 attempt; 0 dead-letter jobs. 176 client errors (read timeouts and start connection errors at the 30/60 s client limits; 21 starts gave up and created no request).
- Not separated: whether `force_custom_plan` itself added measurable planning CPU in this run (it was introduced together with V155 and the larger database).

### 4.2 Volume: plans and single-user latency (111k requests, 300k tasks, 889k events)

EXPLAIN (ANALYZE, BUFFERS), warm cache, before and after V155 (`explain-before.txt`, `explain-after.txt`):

| Query | Before ms | After ms | Note |
|---|---|---|---|
| Run list, first page | 1.0 | 0.1 | `workflow_run_ws_created` |
| Run list `lifecycle=needsAttention` (custom plan) | 78 | 1.0 | `workflow_run_ws_open` |
| Same, generic plan | 269 | 227 | seq scan; avoided by F3 |
| Business-key search, no match (custom / generic) | 0.9 / 204 | 0.1 / 171 | generic plan avoided by F3 |
| Run list `overdue=true` | 163 (cold) | 24 | F5 |
| Claimable inbox page / claimable count (8,000 open) | 6 / 80 | 2 / 55 | F16 |
| Insights TASK_TIMED_OUT events, 90 days | 60 | 0.2 | `run_event_ws_type_time` |
| Insights wait events, 90 days | 69 | 2.6 | `run_event_ws_type_time` |
| Insights totals 30 days | 40 | 28 | parallel seq scan is the right plan (17 % of rows) |
| Insights completed per day, 30 days | 42 | 15 | `workflow_run_ws_completed_ended` |
| Watchdog candidates | 4.8 | 5.3 | uses `workflow_run_active`; no index added |
| Quota check (active runs of a workflow) | 3.5 | 3.0 | |
| Insights `openOnlySteps` (custom / generic) | 90 / > 60,000 | - | F3 |

V155 build time on this database (migrate job): **2.2 s** for the four indexes.

Single-user API latency through one replica, p50 / p95 ms over 15 calls (`probe-before.json`, `probe-after.json`; the after values include `force_custom_plan`; differences under about 10 ms are noise):

| Call | Before | After |
|---|---|---|
| runs list / page 2 / by workflow | 9/10, 10/13, 10/11 | 12/22, 13/16, 14/18 |
| runs `waiting` / `needsAttention` | 21/30, 23/30 | 14/16, 15/18 |
| business-key search / reference / no match | 11/16, 12/13, 14/28 | 14/20, 10/12, 11/14 |
| runs `overdue` | 106/129 | 70/91 |
| claimable inbox / priority sort / assigned / completed | 196/225, 178/220, 63/79, 111/156 | 150/185, 214/270, 54/95, 98/111 |
| Insights summary 30 d / 90 d | 507/637, 1247/1533 | 456/636, 1217/1445 |
| Insights steps 30 d / 90 d | 621/897, 1282/1569 | 481/621, 1334/1659 |
| Insights throughput 30 d | 40/49 | 39/54 |
| notifications / catalog / shared assets | 13/18, 7/9, 9/10 | 11/12, 9/10, 15/25 |

Between the two probes, without `force_custom_plan`, `/insights/steps` answered **503 after 10 s on every call** once the generic plan of `openOnlySteps` was cached (F3); with the setting it answers 200 again.

## 5. Failure test: SIGKILL one replica mid-load

Two replicas, 100 VUs, 6 minutes, the stub delays every order by 1 s so engine jobs are in flight; `docker kill` (SIGKILL, no graceful shutdown) of `orqix-2` at 150 s. Jobs sampled every 5 s.

| Observation | Evidence |
|---|---|
| LB failover | From the kill second on, every request was served by the survivor; 65 requests show two upstreams (connect to the dead replica timed out after 2 s, retried on the survivor). In-flight requests on the killed replica got `502` (1 start, 1 decision); the client retried with the same key and both succeeded (no duplicates) |
| Jobs of the killed replica | 23 async jobs stayed locked by the dead owner `0bc98b4d4bd1` from 150 s until about 200 s, then were acquired by the survivor `98027e146bd7` (lock time 60 s, reset interval 15 s, measured takeover 45-58 s after the kill because some locks were taken before it). No dead-letter jobs; 0 jobs left at the end |
| In-flight effects | 4 order calls were re-sent by the survivor with the same Idempotency-Key (`effect_journal.attempts` max 2); the stub counted 1,386 create requests, 4 replays, **1,382 orders for 1,382 completed requests** |
| Requests | 1,382 started = 1,382 approved = 1,382 completed = 1,382 start idempotency records = 1,382 runs in the database; max 1 decision per task |
| Errors | 108 client-side errors (0.2 %): 26 starts gave up after their one retry (no request was created for them: runs = started), the rest were 30 s read timeouts and connection errors. **Most occurred before the kill (s 50-149) and nginx logged no error for them**; the nginx error log starts at the kill. They are attributed to the load client and Docker Desktop's port proxy under 200+ sockets plus queueing behind the start lock (see section 4, point 3), not to the failover |

Graceful shutdown (`docker stop -t 60`) was not separately measured in this slice; it is covered by T-RES-05 (`GracefulShutdownTest`, `unlock-owned-jobs: false`).

## 6. Static review findings

Severity: H = limits scale or can fail under N replicas, M = grows with data or load and needs action before large tenants, L = minor. "Fixed" means changed in this branch with a test.

| # | Area | Finding | Sev | Status |
|---|---|---|---|---|
| F1 | Run start | Per-workspace `run_seq` row lock held for the whole start transaction (section 4, point 3). Measured 86 ms mean wait with 1 replica, 971 ms / max 11.7 s with 2 | H | **Open** - needs a product decision: either allow gaps in `ORQ-n` (use a sequence or allocate in its own short transaction) or restructure the start so the counter is taken last. Not changed: "a rolled-back start leaves no gap" is documented behavior |
| F2 | Insights | Summary/Steps recompute percentiles over every request and task in the window on every call (0.5 s for 30 days, 1.2-1.3 s for 90 days on 111k requests, single user; 263 ms mean DB time under load) | M | Partly fixed (V155 removes the full `run_event` scans). Open: cache per workspace for a short TTL, or pre-aggregate; limit concurrent Insights calls |
| F3 | Plans | The JDBC driver prepares statements on the server after 5 executions; PostgreSQL may then reuse one generic plan. The many optional-filter queries (`CAST(:x AS uuid) IS NULL OR col = :x`) get catastrophic generic plans: Insights `openOnlySteps` 90 ms custom vs > 60 s generic (merge join with a join filter over all requests), so `/insights/steps` answered **503 after 10 s on every call** once the plan was cached; run list `lifecycle=needsAttention` 1 ms custom vs 227 ms generic, business-key search 0.1 ms vs 171 ms. The same generic plan appears with V155 rolled back, so it is pre-existing and depends on plan-cache timing | H | **Fixed**: `connection-init-sql` sets `plan_cache_mode = force_custom_plan` (env `ORQIX_DB_PLAN_CACHE_MODE`, default `force_custom_plan`). Test `PlanCacheModeTest` |
| F4 | Indexes | Full scans on growing tables: Insights `run_event` by type/time (60-69 ms -> 0.2-2.6 ms), finished tasks by creation time, open requests of a workspace (run list `needsAttention` 78 ms -> 1 ms), completed by end time (42 -> 15 ms) | M | **Fixed**: `V155__scale_hot_query_indexes.sql` (4 indexes). Applied by the migrate job to the seeded database in **2.2 s** (`CREATE INDEX` blocks writes to the table meanwhile). Test `ScaleIndexesTest` |
| F5 | Run list | `overdue=true` walks all requests of the workspace newest first and probes `run_stage` for each (24-70 ms warm / 163 ms cold on 111k requests); claimable count probes `task_decision` per open task (55-80 ms for 8,000 open tasks) | L | Open (acceptable at this volume; revisit with a partial index on open stages / a separation-of-duties pre-filter) |
| F6 | Pool | Hikari 15 per replica with Tomcat 200 threads: under load the pool is the first queue (avg 65 waiting, max 98). Starts waiting on F1 hold connections | M | Documented (admin guide sizing); `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` can raise it within the connection budget. Not changed: larger pools only move the queue into PostgreSQL on this hardware |
| F7 | Policy cache | `PolicyService` cache is a `ConcurrentHashMap` without size bound; entries expire (50 s TTL) but are only removed on an access change (`evictAll`, local to the replica). Revocation bound across replicas = TTL 50 s (within the 60 s promise) because other replicas never hear the eviction. Most endpoints use `freshAccess` (no cache) anyway | L | Open: bound or sweep expired entries; growth = distinct (subject, groups, workspace) seen by a replica |
| F8 | Rate limits | Attachment uploads, AI job admission, message buffers, inbound backlog: database-backed, cluster-wide. `SimulationLimiter` (30 dry runs/min) and the AI model circuit breaker (`ModelPolicy.localTrips`) are **per replica** (limit x N) | L | Documented |
| F9 | Schedulers | Watchdog, consistency scan, stage overdue, reminders, stage mail, SLA, external-job lease recovery, staged-attachment cleanup, attachment retention, agent-write retention: all bounded batches (50-200) with `FOR UPDATE SKIP LOCKED` on the candidate rows and row locks on the run, so N replicas do not double-process. `MessageSweeper` runs unbatched `UPDATE`/`DELETE` statements (expire buffered, cancel orphans, purge closed) every 5 s on every replica; concurrent replicas wait on each other's row locks (errors are caught and retried next pass). AI job reaper is per replica but idempotent (deadline-based). Spring Session cleanup runs on every replica (idempotent `DELETE`) | L | Documented; MessageSweeper batching open |
| F10 | Retention | `idempotency_record` (one row per start/decision/command, 30-day `expires_at`), `run_event`, `audit_event`, `task_*`, `notification` are never purged in R0 (S2: "R0 stores everything and runs no purge job"). Storage and index size grow with traffic | M | Open by decision (D12); capacity planning must assume linear growth |
| F11 | Flowable | Async executor per replica (core 4 / max 6, queue 100), lock owner = pod hostname, lock time 60 s, reset 15 s, `unlock-owned-jobs: false` (graceful shutdown drains). Jobs created in a request are executed by the creating replica (hint after commit); others are acquired by polling. No global acquire lock: with many replicas acquisition contention shows as optimistic-lock retries (not measured beyond 2) | L | Documented. Kill test confirmed takeover |
| F12 | Inbound purge | `InboundService.purge` deletes all events before a cutoff in one statement (admin call, 30 s statement timeout) | L | Open (batch it before large tenants) |
| F13 | Migrate profile | `AttachmentLifecycle.sweep` is not excluded from the `migrate` profile and logs an error when the job's pool closes | L | Open (cosmetic) |
| F16 | Inbox | Every claimable-inbox call runs `countClaimable` over all open claimable tasks of the caller's groups, with a separation-of-duties `NOT EXISTS` probe per task, plus the list. With 8,000 open tasks: 55-80 ms count / 2-11 ms list alone, **486 / 543 ms mean under load**, 1,639 + 788 s of database time in run D | H at volume | **Open**: cap or cache the count (for example "999+"), and keep inbox polling intervals in the UI generous |
| F17 | Insights robustness | `InsightsQueries.needsAttention` does `Instant.parse(attention.since)`; one needs-attention request without `since` makes the whole Insights summary answer 500 (found because the first seed omitted it; the application's own writers were not audited for every path) | L | Open |
| F14 | Payload limits | Request 64 KiB JSON, drafts 256 KiB, files 10 MiB (multipart spooled to `/tmp` above 256 KiB), JSON depth 32; LB `client_max_body_size 11m` | - | OK |
| F15 | N+1 | Run list, task inbox, notifications, catalog use batched name/visibility lookups (constant queries per page); `pg_stat_statements` call counts per request confirmed no per-row query patterns on the measured endpoints | - | OK |

## 7. Capacity guidance (measured on this machine only)

| Shape | Measured here | Use as |
|---|---|---|
| 1 replica, 1.5 CPU, Hikari 15, DB 3 CPU | about 140 req/s mixed, 420 starts/min in **one** workspace, p95 1.1-1.4 s at 150 active users with 1-3 s think time | relative baseline only |
| 2 replicas, 1.5 CPU each, DB 3 CPU | no throughput gain; database saturated; start p95 4.3 s | shows that the DB and F1 limit before the app does |
| 2 replicas, DB 3 CPU, 111k requests and 8,000 open tasks | 58 req/s, 159 starts/min, read p95 5.2 s | history and backlog size dominate; Insights and inbox counts need F2/F16 before large tenants |
| Database | 2-3 CPU were saturated by 150 users of one workspace; 111k requests + 800k events used about 1 GB of data and indexes | size the DB first; scale replicas for availability |

Guidance that follows from the evidence (not from production measurements):

- Run **two replicas for availability**; add replicas for throughput only after the database has headroom (CPU < 60 % at peak) and the workload is spread over several workspaces (F1).
- Connection budget: `replicas x 15 + 5 + 10 <= 0.8 x max_connections` (admin guide); with max_connections 100 that is 4 replicas.
- Watch `hikaricp_connections_pending`, PostgreSQL CPU, and the `run_seq` statement in `pg_stat_statements`.

## 8. What this does not prove

- Production capacity, other hardware, network latency, TLS, a real OpenShift router, more than two replicas, PostgreSQL HA/failover, multi-workspace load (all load ran in one workspace, the worst case for F1).
- The load client ran on the same host as the stack and the host was shared with other running stacks; client-side errors under 200+ sockets are partly attributable to Docker Desktop's port proxy.
- The large dataset is synthetic (no Flowable history for the seeded requests; uniform distribution over 180 days; one approval step per task).
- Graceful-shutdown handover under load (only SIGKILL was run here), long-duration soak (memory, bloat, autovacuum), and the effect of the never-purged tables over months.
- Gatling/T-PERF-02 pilot load.

## 9. Changes in this branch

- `backend/app/src/main/resources/db/migration/V155__scale_hot_query_indexes.sql` (new; no existing migration changed).
- `backend/app/src/main/resources/application.yaml`: `plan_cache_mode` in `connection-init-sql` (default `force_custom_plan`, env `ORQIX_DB_PLAN_CACHE_MODE`).
- Tests: `db/ScaleIndexesTest`, `db/PlanCacheModeTest`.
- `deploy/compose/docker-compose.scale.yml`, `deploy/compose/lb/nginx.conf`.
- `tools/perf/scale.py`, `tools/perf/seed_large.sql`, `tools/perf/explain_hot.sql`, `tools/perf/probe_volume.py`.
- `docs/admin-guide.md`: section "Scaling and capacity".

Verification: `mvnw clean verify` (backend, run after the load tests, not concurrently): 899 tests, 4 skipped, 1 failure - the first version of `ScaleIndexesTest` asserted planner choices, which differ on the shared test database. It now asserts index keys, predicates and validity; re-run together with `DatabaseTest`: pass. `PlanCacheModeTest`, `InsightsApiTest`: pass. No frontend change, no frontend suite run.

Note for merging: V155 was chosen from the reserved range V155-V159. Flyway `outOfOrder` is not enabled, so lower versions merged later (V123-V154) must be applied before V155 on any database, or the migration job will refuse them.
