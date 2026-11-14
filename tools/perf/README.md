# Performance smoke (T-PERF-01)

R0 smoke of docs/build/S9: 20 virtual users for 10 minutes against the Docker stack (`cd deploy/compose && docker compose
--profile app up -d --build`, one application instance on :8080, Keycloak, procurement stub, a clean database).

```powershell
python -m pip install -r tools/api-e2e/requirements.txt
python tools/perf/smoke.py --users 20 --minutes 10      # writes evidence/perf/T-PERF-01.json
```

Every virtual user has a requester, a manager and an operator session (real Keycloak login) and loops: start a purchase
request, find its task in the claimable inbox, read, claim and approve it, then poll the run until it completes (with run
lists and notifications in between) and think for 0.5-2 s. Amounts of 4,500 or less are used so that no finance step is
needed. The result file has p50/p95/p99/max per API, the list/task aggregate, start, errors, throughput, flow counters,
container CPU/memory samples, and the consistency checks (every approved run completed, exactly one stub order per run).

Targets: p95 list and task APIs < 1 s, p95 start < 2 s, no errors.

**Why Python and not Gatling.** The S9 catalog names Gatling for the pilot load (T-PERF-02, GA). Gatling would add a Maven
module, a JVM test dependency set and its own scripting of the OIDC login; the Keycloak form login with PKCE is already
scripted in `tools/api-e2e/orqix_client.py`. For this smoke Python threads against one instance are sufficient, and nothing
is added to the product or its runtime. Latency is the client-side wall time of each call on the same host (loopback).

## Scale qualification (S92)

`docs/build/S92-scalability-qualification.md`. Two replicas behind nginx (`deploy/compose/docker-compose.scale.yml`), a mixed
ramped load, volume seeding and plan checks:

```bash
python tools/perf/scale.py --base http://localhost:<lb-port> --stub http://localhost:<stub-port> --users 150 --ramp 120 \
    --minutes 8 --out evidence/s92/run.json                       # add --kill-after 150 for the replica-failure test
psql ... -v runs=100000 -v tasks_per_run=3 -f tools/perf/seed_large.sql   # qualification databases only
psql ... -f tools/perf/explain_hot.sql                            # EXPLAIN (ANALYZE, BUFFERS) of the hot queries
python tools/perf/probe_volume.py --base http://localhost:<port> --out evidence/s92/probe.json
```

`scale.py` reads container names and management ports from its options (defaults: the private `orqix-scale-1026` stack).

## Runtime roles: API pod plus two worker pods (CG-P01, S150)

`docs/build/S150-observability-scale-backups-admission.md`. One API pod (profile `api`) and two worker pods (profile
`worker`) on one PostgreSQL, from `deploy/compose/docker-compose.roles.yml`:

```bash
cd deploy/compose
docker compose -p orqix-roles -f docker-compose.yml -f docker-compose.roles.yml --profile app up -d --build
cd ../..
python tools/perf/multipod.py --smoke                                  # 3 users, 75 s; exit 1 when a check fails
python tools/perf/multipod.py --users 30 --minutes 5 --out evidence/s150/run.json
```

The harness publishes a purchase approval and a variant that keeps checking the stub's health every 10 s after the
order (a read-only connection `perf-health` it creates), then runs closed-loop virtual users that start, wait for the
task, claim, approve and wait for completion (or for the watch step). It reports start throughput and latency, task
latency (start answer to claimable task: includes a worker pod picking up the job), completion latency (approval to
completed: worker job plus connector call), poll checks per second against the schedule, analytics lag sampled from
the pods' metrics, connector calls and poll checks per pod, and dead-letter jobs. The checks: the API pod made no
connector calls or poll checks, both workers did, all work finished, no dead-letter jobs.

`--smoke` is short enough for a CI job that already has the stack up; nothing runs it automatically. Everything runs on
one machine: the numbers are relative evidence for this topology, not capacity.
