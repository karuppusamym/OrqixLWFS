# Resilience scenarios (Docker stack)

Scenarios of docs/build/S9 that need real processes: restart, `kill -9`, `docker stop`, a stopped database. The in-JVM
counterparts run in `mvn verify` (`com.orqix.resilience.*`, `com.orqix.hardening.*`, Testcontainers PostgreSQL).

```powershell
python -m pip install -r tools/api-e2e/requirements.txt

# T-RES-01 on the plain stack (docker compose --profile app up -d --build)
python tools/resilience/resilience.py restart

# the others need two application instances: add the overlay (orqix-2 on :8082 / :8083, same image and database)
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.resilience.yml --profile app up -d --build
cd ../..
python tools/resilience/resilience.py killnode  --victim orqix-2    # T-RES-03  (also: --victim orqix)
python tools/resilience/resilience.py shutdown  --victim orqix-2    # T-RES-05  (also: --victim orqix)
python tools/resilience/resilience.py dboutage                       # T-RES-04  (--outage-seconds 60)
python tools/resilience/resilience.py revocation                     # T-SEC-09
```

Each scenario prints PASS/FAIL lines, writes `evidence/resilience/<test>.json` (checks and measurements) and restores
the stack in a `finally` block (killed or stopped instance started again, PostgreSQL running, both instances ready).
Run them one at a time and not together with the browser or API suites: they change the shared procurement stub and
stop containers. They use the local Keycloak development accounts; no password or response body is printed.

| Scenario | Procedure | Pass criteria |
|---|---|---|
| `restart` (T-RES-01) | Start a run, claim its task, `docker compose stop`/`start` the app | Task, claim, version, inbox entry and release pinning intact; approval completes with one effect and one completion event |
| `killnode` (T-RES-03) | Stub `delay` 25 s; decision through the victim so that it runs the job; `docker kill --signal=SIGKILL` 3 s into the call | The other instance resumes within 120 s (lock time 60 s + reset interval 15 s + margin); exactly one order at the stub (the resend is answered as a replay of the same idempotency key); one `EFFECT_SUCCEEDED`, one `RUN_COMPLETED`; the victim restarts and becomes ready |
| `shutdown` (T-RES-05) | Stub `delay` 15 s; the victim runs the job; `docker compose stop -t 60 <victim>` (SIGTERM) 2 s into the call | Orderly exit (143, not 137), drain within the grace period; run completes; exactly one order; one `EFFECT_SUCCEEDED` |
| `dboutage` (T-RES-04) | Two instances; 4 runs have an HTTP call in flight (stub `delay` 12 s), 10 tasks wait; 6 workers send reads and commands; PostgreSQL is stopped for 60 s and started again | No command reports success during the outage, all answer `503 DEPENDENCY_UNAVAILABLE`; liveness stays up; readiness drops and recovers; the 4 pending runs complete with one order each; commands succeed again |
| `revocation` (T-SEC-09) | Subject role bindings for two dev users (no seed data touched); both instances cache the access; removal through :8080; the other instance polled every second | Immediate on the instance that changed it; the second instance converges within the policy cache TTL (60 s) |

Notes on what the scenarios found are in `evidence/resilience/` and in docs/adr/0001-dependency-baseline.md (S-5).
Database restore (T-RES-06) needs a backup target and is not covered here.
