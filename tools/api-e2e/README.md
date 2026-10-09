# API end-to-end scenarios (Docker stack)

Drives the purchase-approval workflow through the real stack — Keycloak login (authorization code + PKCE through the
login form), the app container, and the procurement stub — and checks outcomes on both sides (run state in Orqix,
orders in the stub). Development stack only: it reads the dev-only test passwords from
`deploy/compose/keycloak/orqix-dev-realm.json` and never prints them.

```bash
cd deploy/compose && docker compose --profile app up -d --build
pip install -r tools/api-e2e/requirements.txt
python tools/api-e2e/e2e_scenarios.py            # scenarios 1-6 (~10 s)
python tools/api-e2e/e2e_scenarios.py --faults   # adds 7-9: retries, commit-then-timeout dedup, 400 -> operator retry (~2 min)
```

| # | Scenario | Checks |
|---|---|---|
| 1 | USD 1,800 approved → purchase order → Fulfilled | 201 start, waiting at manager, requester sees no task, one stub order, notification, gap-free events, other workspace gets 404 |
| 2 | Manager rejects | 422 `REASON_REQUIRED` without reason; Rejected; no order |
| 3 | USD 5,000 → finance approves | Waits at Finance review; Fulfilled |
| 4 | USD 4,999.99 | Never visits finance |
| 5 | Manager's own request | Not claimable by the requester; claimable by another manager |
| 6 | Idempotent start | Replay returns the same run with `Idempotent-Replayed`; same key + different body → 409; invalid input → 422 with field pointers |
| 7 | Stub fails twice with 500 | Completed after 3 attempts with exactly one order |
| 8 | Stub creates the order, then times out | Retry with the same key; still exactly one order |
| 9 | Stub rejects with 400 | `needsAttention` / `EFFECT_REJECTED`; operator retry completes the run |

The script publishes release 1 as `paul.publisher` if the template has no active release, and resets the stub before
each scenario group. Exit code 0 means every check passed.

## Operations scenarios 10-14 (`e2e_operations.py`)

```bash
python tools/api-e2e/e2e_operations.py     # ~1 minute; resets the stub, creates its own workflows
```

| # | Scenario | Checks |
|---|---|---|
| 10 | Release rollback (T-RUN-07) | Stale `expectedVersion` -> 409 `WORKFLOW_VERSION_CONFLICT`; rollback to release 1 makes new starts use it; a waiting release 1 run still routes 6,000 to finance, a waiting release 2 run completes without finance while release 1 is active; roll forward again |
| 11 | Reconcile `confirmedApplied` (T-EFF-06) | Stub rejects with 400 -> `needsAttention` / `EFFECT_REJECTED`; wrong result fields -> 422; requester -> 403; operator reconcile -> effect `succeeded`; same request again is a replay; run completes with the operator's reference; the stub holds no order (never called again); one `EFFECT_RECONCILED` event |
| 12 | Reconcile `confirmedNotApplied`, then retry (T-EFF-07) | Effect `notApplied`, run stays in `needsAttention`, no order; explicit retry; completes with exactly one order |
| 13 | Cancel (FR10) | Requester -> 403; operator cancel -> `cancelled`, task cancelled, claimable inbox cleared; second cancel -> 409 `RUN_NOT_ACTIVE`; no stub order |
| 14 | Forged fields and missing CSRF (T-SEC-04/05) | Unknown field `initiator` -> 400 problem; missing `X-XSRF-TOKEN` -> 403 `CSRF_INVALID`; foreign workspace -> 404; the run count did not change |

The unknown-outcome reconcile of T-EFF-04 needs a `nonIdempotent` operation, which the procurement stub cannot serve
(it requires an idempotency key); that path is covered against PostgreSQL/Flowable/WireMock by `EffectsTest` and
`ReconcileResumeTest`.

