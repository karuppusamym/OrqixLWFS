# S101 - Integration and bulk verification on a real broker (27 October 2026)

Purpose: prove, on a running stack and not only in unit tests, that callbacks, signed inbound webhooks, outbound webhooks and Kafka triggers work together, and that bulk volumes are handled exactly once. Everything here ran on an isolated Docker stack (`integ-1028`, ports 42080 and up) built from `main`, with a **real single-node Kafka broker** (`apache/kafka:3.9.1`, KRaft, plain text, live consumer mode) and a webhook receiver that records what it is sent. No existing stack was touched.

## How to repeat it

```bash
printf 'e2e-inbound-secret-0123456789abcdef' > deploy/compose/secrets/e2e-inbound
printf 'e2e-webhook-secret-0123456789abcdef' > deploy/compose/secrets/webhook-e2e
EXTRA_COMPOSE=tools/demo/integration.override.yml bash tools/demo/demo-stack.sh up integ-1028 42000
cd tools/api-e2e
ORQIX_BASE_URL=http://localhost:42080 ORQIX_STUB_URL=http://localhost:42095 \
  INTEG_KAFKA_CONTAINER=orqix-integ-1028-kafka-1 INTEG_SINK_URL=http://localhost:42099 python e2e_integrations.py
python e2e_bulk.py          # BULK_N, BULK_KAFKA, BULK_THREADS change the sizes
```

Use a fresh database for each run (`demo-stack.sh down` then `up`): triggers cannot be deleted and a workspace holds at most five Kafka triggers. The secret files are throw-away development values and are not committed.

## Results

| Scenario | What it proves | Result |
|---|---|---|
| 50a Callback | A one-time token is issued, polls without a session (304 when unchanged), resumes a wait once, replays idempotently, refuses a different second call (409), and an unknown token is 404; the request finishes with the callback's value | 11/11 |
| 50b Inbound webhook | A signed event is recorded; the same event id is acknowledged as a duplicate; a wrong signature and a one-hour-old timestamp are 401; a person starts a request from the event (and a repeat returns the same request); a signed call with a callback token resumes a wait through a second trigger whose mapping describes the result | 10/10 |
| 50c Outbound webhook | The receiver gets a `v1=`-signed delivery carrying ids and states and none of the request's data; a receiver that answers 500 is retried; delivered rows are listed for operators | 8/8 |
| 50d Kafka, real broker | Live mode with cluster `local`; a record starts one request; a repeated record key starts nothing more; a malformed record is a dead letter without its content and the next good record is still handled; a record on a second topic resumes the waiting request by its order number | 10/10 |
| 51a Bulk starts | 200 concurrent starts by 8 sessions: all accepted, references unique and gap-free | 14.3 per second |
| 51b Bulk messages | 200 concurrent messages end 200 waits: every request completes exactly once | 10.1 per second; everything complete 34 s after the first start |
| 51c Kafka burst | 300 records on one topic start 300 requests, once each (still 300 six seconds later) | 22.9 s, about 13 per second |
| 51d Inbound burst | 200 signed calls (every event id sent twice): all accepted, none rate-limited, no server error | 1.6 s |

Totals: `e2e_integrations.py` **43/43** and `e2e_bulk.py` **10/10** on the final image.

## Defect found and fixed

**The open-request limit was not exact under concurrency.** `ReleaseQueries.lockWorkflow` used `FOR SHARE`, which does not make starts of one workflow wait for each other although its comment says it serializes the quota check. With a limit of 100 and eight concurrent callers, **107** starts were accepted. It now uses `FOR NO KEY UPDATE`: starts of one workflow queue behind each other and behind release changes, while the foreign-key checks of a new request still pass. New test `RunsAndTasksTest.tRun08b_quotaHoldsExactlyWhenStartsArriveAtTheSameTime` (limit 3, eight concurrent starts: exactly 3 accepted); **mutation check**: with `FOR SHARE` restored the test failed twice, with the fix it passes. Cost: a single workflow now starts at about 14 per second under this load (it was 22 per second with the unsafe lock); starts were already serialized per workspace by the `ORQ-n` counter ([S92](S92-scalability-qualification.md)).

## Behaviour worth knowing (not defects)

- One inbound trigger has one mapping. The mapping describes the start input for a start and the **result** for a callback resume, so use two triggers when one source does both (scenario 50b does).
- A request started by a Kafka trigger belongs to the person the trigger runs as; only that person and operators can read it.
- The webhook delivery list defaults to dead letters; ask for `status=delivered` to see successes.

## Not covered

TLS or SASL against a secured broker; two application processes in one consumer group on this stack (covered by `KafkaConsumerTest` with two consumers); an https webhook receiver; throughput on production-class hardware (the figures are one laptop, one database, one application process; they show correctness and a floor, not capacity).
