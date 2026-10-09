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
