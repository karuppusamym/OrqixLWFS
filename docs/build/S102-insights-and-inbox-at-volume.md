# S102 - Insights and inbox at volume (28 October 2026)

Follow-up to [S92](S92-scalability-qualification.md) findings F2 (Insights aggregates), F16 (inbox count) and F1 (the request-number lock). Measured on an isolated stack (`scale-1028`, ports 43080 and up): one application replica, one PostgreSQL, **100,000 requests, 300,000 tasks, 8,000 open tasks and 800,000 events** seeded with `tools/perf/seed_large.sql`. Load: `tools/perf/read_load.py`, 12 signed-in people for 60 seconds, half of them alternating Insights summary and Insights steps (30 days), the other half reading the first page of the claimable inbox. The old image (`r0-2026-10-28`) and the new image ran against the **same data**, one after the other.

## What changed

| Finding | Change |
|---|---|
| F2 Insights | Finished figures are kept for 30 seconds per replica (`orqix.insights.cache-ttl-seconds`, 0 switches it off; bounded to 500 entries) and concurrent viewers of the same figures share one calculation. The cache sits **after** the access check and the parameter validation, so nobody reaches it without `run.read`; the key is figures, workspace, workflow and window, never the person. A failed (timed-out) calculation is not kept. `InsightsCacheTest` (4): reuse within the time to live and recomputation after it, off at 0, no caching of failures, twelve callers share one calculation. Tests run with the cache off, so existing Insights tests still read fresh data. |
| F16 Inbox count | The assigned and claimable counts are exact up to **1,000** and stay at 1,000 beyond (`LIMIT` inside the count), so a large backlog is no longer counted in full on every inbox call and every badge poll. The contract says so; the navigation badge shows `1000+`. |
| F1 Request-number lock | **Not changed**, on purpose (see below). |

## Results (same data, old image then new image)

| Call | Old: calls per second, p50 / p95 / max (ms) | New: calls per second, p50 / p95 / max (ms) |
|---|---|---|
| Insights summary, 30 days | 1.1, 2,517 / 3,593 / 4,173 | **26.3**, 38 / 141 / 2,150 |
| Insights steps, 30 days | 1.0, 2,884 / 3,976 / 4,408 | **26.3**, 38 / 134 / 2,666 |
| Inbox, claimable, first page | 8.0, 648 / 1,101 / 1,944 | **18.5**, 245 / 478 / 955 |
| Errors | 0 | 0 |

Reading the numbers: the Insights gain is the cache. A figure that is not in the cache (the first viewer after 30 seconds, or a new window) still takes the old 2 to 3 seconds, which is the `max` column; the cost is that Insights can be up to 30 seconds behind reality. The inbox gain comes from the bounded count; the remaining 245 ms is the list query itself over 8,000 open tasks.

## Why the request-number lock is unchanged

`UPDATE workspace SET run_seq = run_seq + 1` takes the workspace row until the start transaction commits, which includes the engine start. Taking the number at the end of the transaction would shorten the lock, but many places read the request reference **inside** the same transaction (the outbound webhook "request started" row, task and notification text, the engine's own projections), so a number assigned late would be missing for them. A safe change needs those readers reworked or the reference carried in memory; it was judged too risky to do without its own design and tests. Until then one workspace starts requests one after another (about 14 per second per workflow in [S101](S101-integration-and-bulk-verification.md)), and more replicas do not raise that. This remains the first limit for very high start rates in a single workspace; spreading load over several workspaces does not hit it.

## Not covered

One replica only for this comparison (two replicas were measured in S92); read load only, not a mixed start-and-decide load on the large data; production-class hardware.
