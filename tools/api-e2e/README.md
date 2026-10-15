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

For a separate verification stack, set `ORQIX_BASE_URL` (for example `http://localhost:18080`) and
`ORQIX_STUB_URL` (for example `http://localhost:18095`). Run scenario scripts sequentially because they share
the stub's fault settings and order journal. Existing stack volumes do not need to be reset.

## Reviewed imports and conditional row forms (`e2e_authoring.py`)

```bash
python tools/api-e2e/e2e_authoring.py
```

Uses real OIDC, API authorization, immutable registration, publication and PostgreSQL/Flowable execution.
Checks import permission denials, credential redaction, explicit retry confirmation and separate registration;
then publishes a conditional form with strict numeric rows and a pinned multi-input decision. Forged hidden
answers and malformed rows are rejected. Actual count/sum outputs are checked against simulation, including
exact decimal addition and zero totals for omitted optional rows. Creates unique resources without changing
connection bindings or calling remote source systems. This establishes local runtime behavior, not tenant or
source-system acceptance.

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


## Demo 2 scenarios 20-23 (`e2e_agents.py`)

```bash
python tools/api-e2e/e2e_agents.py [--faults]   # needs the stack rebuilt with the dev seed; real model calls cost about USD 0.0006 per run
```

| # | Scenario | Checks |
|---|---|---|
| 20 | Agent -> MCP tool -> rule -> approval | Acme Ltd (risk 72) waits at Compliance review, the timeline carries the business sentence, the event lists model and tool names only, the stub saw exactly one `vendor_risk_lookup`, `fin.finance` approves -> Approved with the result in the output; Globex Corporation (18) is approved automatically; Umbrella Trading (sanctions hit) goes to compliance and is rejected |
| 21 | Connection test | Admin test with a sample input returns the structured output and the sentence; mismatching input -> 422; requester -> 403; the connection list marks `kind: agent` |
| 22 (`--faults`) | Tool server HTTP 500 twice | The step is retried (attempts >= 2) and the run completes |
| 23 | AI not configured | Skips 20-22; validation warns `WF_AGENT_AI_UNAVAILABLE`, publish needs the acknowledgement, the test is 422 `AI_NOT_CONFIGURED`, the tool server is never contacted |

## Workflow patterns, scenarios 15-17 (`e2e_patterns.py`)

```bash
python tools/api-e2e/e2e_patterns.py     # ~3 minutes: the template's one-minute wait is real
```

Needs the dev seed groups `it-desk` and `facilities-team` (restart the dev stack after pulling) and publishes
`purchase-approval` when it has no active release.

| # | Scenario | Checks |
|---|---|---|
| 15 | Demo 4: parent -> child -> result -> continue | Both approvals open at once for different groups; the join waits for the second; the timer step shows its due time; the purchase starts as its own run (own ORQ reference, `parentRun` link, release pinned in the parent's manifest); no order before the child is approved; one order after; the parent finishes `Ready` with the mapped `equipmentOrderId`; timeline has `CHILD_STARTED` before `CHILD_ENDED`, gap-free |
| 16 | Pinning and cancellation | Republishing `purchase-approval` while a parent waits does not change the release the parent starts; cancelling the parent cancels the child and its task; no order |
| 17 | Rejection ends everything | One rejected branch completes the request as Rejected and cancels the other branch's task |

## Request files and case stages, scenario 50 (`e2e_cases.py`)

```
python tools/api-e2e/e2e_cases.py     # ~20 seconds; creates its own workflow from the case-with-stages template
```

Two files chosen on the request form (upload, then reference), start, list and download (attachment, nosniff, sandbox),
refused types and the 10 MiB cap enforced by the container, a file added later and removed, outsiders get 404, the
required file question, and stage 1 of 3 moving to stage 2 with the timeline events once each. Design: docs/build/S25.
