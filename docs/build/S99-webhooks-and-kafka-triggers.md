# S99 - Webhook resume, outbound webhooks and a Kafka consumer trigger (IN05)

26 October 2026. Implements roadmap item IN05 ([docs/25 section 6](../25-studio-and-integration-roadmap.md#6-in05---webhook-and-kafka-triggers)) on top of the EX10 callback model ([S98](S98-callback-tokens-and-triggers.md)), S47/S73 (signed inbound events, adapters, stub transport) and S16 (messages).

## Decisions

### (a) Signed inbound webhook that resumes a wait

| # | Decision |
|---|---|
| A1 | The existing signed route `POST /workspaces/{ws}/inbound/{triggerId}` accepts an optional `X-Orqix-Callback-Token`. Signature, timestamp window, event id, 64 KiB and depth checks run first and unchanged. With a token the body is mapped by the trigger's `mapping` (field name -> JSON Pointer) into the **result** and handed to the EX10 resume service; no `inbound_event` is recorded, so the 1,000-pending cap is not consumed. |
| A2 | The token must belong to a request of the **trigger's own target workflow** in the same workspace; otherwise the answer is 404 exactly as for an unknown token. A trigger for workflow A can never resume workflow B, even with a valid token. |
| A3 | Idempotency key = `inbound:<triggerId>:<eventId>`, so a re-sent event replays the original result (`status: resumed, duplicate: true`). |
| A4 | Message content is data: neither body nor headers can choose the workspace, workflow, step or request; only the token (scoped to one wait) and the trigger (fixed workflow) do. |

### (b) Outbound webhooks

| # | Decision |
|---|---|
| B1 | A workspace subscribes up to **10** HTTPS endpoints (`connection.manage`, fresh) to any of `request.started`, `task.opened`, `request.completed`, `request.needs-attention`, `callback.waiting`. They are derived from the run timeline (`RUN_STARTED`, `TASK_CREATED`, `RUN_COMPLETED`, `RUN_NEEDS_ATTENTION`) and the EX10 wait creation. |
| B2 | **Transactional outbox**: a delivery row is inserted in the same transaction as the timeline event, so an event is never lost and never sent for a rolled-back change. |
| B3 | **Payload: ids and states only** - `{id, type, occurredAt, workspaceId, request: {id, reference, workflowId, state}, step: {nodeId} or null}`. No input, output, task data, labels or names. A data-carrying option is not offered in this slice. |
| B4 | **Signing** as the inbound route: `X-Orqix-Signature: sha256=hex(HMAC-SHA256(secret, timestamp + "." + body))`, `X-Orqix-Timestamp`, `X-Orqix-Webhook-Id` (the delivery id, stable across retries: receivers deduplicate on it) and `X-Orqix-Event`. The secret comes from a `secretRef` file, read at every attempt. |
| B5 | **Egress**: the URL must pass the existing `EgressPolicy` (https - plain http only for hosts the deployment lists in `orqix.egress.plain-http-hosts` for development -, exact `host:port` allow-list, public DNS unless approved private, no userinfo/query/fragment) when saved **and before every attempt**; redirects are never followed; connect timeout 5 s, request 10 s; the response body is not read or stored, only the status code. A refusal at send time dead-letters immediately (`egress_denied`). |
| B6 | **Retries**: any non-2xx or I/O error is retried with exponential backoff `initial x 2^(attempt-1)` (default 30 s, capped at 1 h) plus up to 10 % jitter, at most **8 attempts**, then `dead`. Missing secret is retried (`secret_unavailable`). |
| B7 | **Dead letters** are listed to operators (`run.operate`) with event type, request id, attempts, last status code and error code; **Replay** (fresh `run.operate`) resets one delivery to pending. Audit `webhook.delivery-replayed`. |
| B8 | **Multi-replica**: the dispatcher claims due rows with `FOR UPDATE SKIP LOCKED` and a 60 s lease, sends outside the transaction and records the result; a pod that dies mid-send leaves the lease to expire and another pod retries. Delivery is **at least once**. |
| B9 | **Caps**: 10,000 pending deliveries per subscription (beyond that the new delivery is recorded directly as dead with `pending_cap`, so it stays visible and replayable); at most 50 sends per dispatcher tick per pod; delivered rows are deleted after 7 days, dead ones after 30. |

### (c) Kafka consumer trigger - designed, not built in this slice

These decisions are recorded so the consumer can be built against them. **Nothing of (c) is in this build**: no Kafka client dependency, no migration, no consumer, no API or UI. Because no dependency was added, docs/07 and the SBOM are unchanged; K1 states what must be recorded when it is added.

| # | Decision |
|---|---|
| K1 | **Client library**: `org.apache.kafka:kafka-clients` (Apache License 2.0), version managed by the Spring Boot 4.0.8 BOM. Its runtime transitive dependencies are `zstd-jni` (BSD-2-Clause), `lz4-java` (Apache 2.0), `snappy-java` (Apache 2.0) and `slf4j-api` (MIT): all permissive. Not `spring-kafka`: we need explicit control of commits and fewer moving parts. Tests: `org.testcontainers:testcontainers-kafka` (MIT) with the `apache/kafka` image (Apache 2.0), test scope only. To be recorded in [docs/07](../07-technology-decisions.md#open-source-boundaries) when added; the CI CycloneDX SBOM would pick the jars up automatically. |
| K2 | **Where brokers come from**: only from deployment configuration `orqix.kafka.clusters.<name>.bootstrap-servers` (+ `security-protocol`). A workspace trigger chooses a **cluster name** and a topic; it cannot type a host. Topics must also be listed in the cluster's `allowed-topics` (exact names, no patterns) - the allow-list. Credentials: a `secretRef` file holding `username` and `password` (SASL PLAIN/SCRAM) - never accepted inline, never logged. |
| K3 | **Consumer groups and replicas**: each trigger has a fixed `consumerGroup` (default `orqix-<triggerKey>`). Every replica starts one consumer per enabled trigger in that group; Kafka assigns each partition to exactly one consumer of the group (cooperative-sticky assignor), so N replicas share the partitions and fail over on rebalance. |
| K4 | **Offsets**: `enable.auto.commit=false`. Each record is handled in its **own database transaction** that records it (`kafka_inbound_record`) and starts or resumes; offsets are committed (`commitSync`) only for records whose transaction committed - after every batch and in `onPartitionsRevoked`. A crash between commit of the row and commit of the offset re-delivers the record, which the dedupe key drops. **At-least-once delivery, exactly-once effect.** |
| K5 | **Duplicates**: dedupe key = the record key when present (1-200 printable characters), else `topic/partition/offset`; unique per trigger. A duplicate is skipped and counted. |
| K6 | **Ordering**: per partition in offset order. A transient failure (database unavailable, request busy, rate or quota limit) stops that partition at that record (`seek`) and retries with backoff 1 s..60 s; after **10** attempts the record is dead-lettered so a partition can never stall forever. |
| K7 | **Dead letters**: malformed JSON, oversize (> 64 KiB), too deep, unmapped/invalid fields, no active release, validation failures and exhausted retries become a `dead` record with an error code, partition, offset, key digest, size and SHA-256 - **never the payload**. The consumer always continues. Operators (`run.operate`) list them. They can be re-produced from the topic; replay from Orqix is not offered because the payload is not kept. |
| K8 | **Start** maps fields by JSON Pointer into the start input and calls the normal `RunService.start` **as the trigger's run-as person** (the person who created or last enabled it, who must hold `workflow.start` for the workflow); start permission, input validation, quotas and idempotency (`kafka:<trigger>:<dedupeKey>`) are the manual start's. **Resume** reads the correlation key by pointer and calls the normal `MessageService.send` with the configured message name as the run-as person (needs `message.send`); buffering, fan-out caps and payload checks are S16's. |
| K9 | **Caps**: at most 5 Kafka triggers per workspace; batch (`max.poll.records`) 1..100 (default 50); rate 1..200 records/s per trigger per replica (default 20); value at most 64 KiB, depth 10; mapping at most 20 fields. |
| K10 | **Visibility**: per trigger and replica a status row (assigned partitions, lag summed over assigned partitions, records handled, duplicates, dead letters, last poll, last error **code**) refreshed every poll; the API aggregates replicas. |
| K11 | **Modes per environment** (`orqix.kafka.mode`): `off` (**default**; triggers can be configured, nothing consumes), `stub` (no broker: a "Send test message" action pushes one JSON value through exactly the same handler - the demo mode) and `live` (real consumers). |
| K12 | **Security**: message content never changes routing - topic, workflow, message name and mapping are fixed by the trigger; content only fills mapped values and the correlation key. No message text in logs or audit: audit `kafka.trigger-created`, `kafka.trigger-updated`, `kafka.record-dead` (counts and codes). |

## What was built

- **(a)** `InboundService.receive` takes an optional `X-Orqix-Callback-Token`; after the unchanged signature, size, depth and source-filter checks the trigger mapping produces the result and the integration port `InboundCallbacks` (implemented by the runtime `CallbackService`) resumes the wait, refusing any request that does not run the trigger's workflow. `InboundAccepted` gains `status: resumed` and `waitId` (`eventId` is null then).
- **(b)** Migration `V171__outbound_webhooks.sql` (`webhook_subscription`, `webhook_delivery`); integration port `OutboundWebhooks` with `WebhookService` (outbox, subscriptions, dead letters, replay) and `WebhookDispatcher` (claim with lease, sign, send, backoff, dead-letter, purge); runtime `WebhookEvents` maps `RUN_STARTED`, `TASK_CREATED`, `RUN_COMPLETED`, `RUN_NEEDS_ATTENTION` inside the event's transaction, and `CallbackService` emits `callback.waiting`. API: `listWebhookSubscriptions`, `createWebhookSubscription`, `updateWebhookSubscription`, `listWebhookDeliveries`, `replayWebhookDelivery`. Settings gains a **Webhooks** tab (subscriptions, pause/resume, dead letters with Replay).
- Settings: `orqix.webhooks.initial-backoff` (30s), `max-backoff` (1h), `max-attempts` (8), `dispatch-delay` (5s), `batch` (50), `lease` (60s), `max-pending` (10000).

## Evidence

Covers S98 and S99. Runs in worktree `build-ex10-in05` on 26 October 2026 (JDK 21, Testcontainers PostgreSQL 18 and Flowable). Docker Desktop was unavailable for part of the session; every result below comes from runs after it was back.

- Full `backend/mvnw.cmd clean verify`: **BUILD SUCCESS** - compiler **613/613**, application **954** run, 950 pass, the same **4** intentional skips, coverage gate and packaging.
- New backend cases: compiler `MessageEventsTest` +2 (token validity PT1M..P30D and schema shape; one extra BPMN attribute only when set, BPMN source round trip keeps the option; existing golden files unchanged). `CallbackTokenApiTest` (5): issue once and hash-only storage, ETag 304, 400/422 without side effects, single resume through the message path with idempotent replay and 409 for a changed key or body, cross-request and cross-workspace 404, replaced/revoked tokens 404, run.operate boundaries, RUN_SUSPENDED until resumeRun, expiry poll/409 and a single expiry audit, cancelled/requestEnded and answeredElsewhere, ordinary waits get no row, audit metadata without token or values, signed inbound resume limited to the trigger's workflow with duplicate replay and no event row. `OutboundWebhookApiTest` (4): permissions and egress refusal (including the metadata address), version conflict, readers without secretRef, signed delivery with ids/states only and no request data, 30 s first backoff, not resent before due, dead letter after 8 attempts with audit lacking URL and payload, operator replay delivered, dead-letter on egress refusal at send time. `SecurityMatrixTest` covers all ten new operations for foreign principals plus a new machine-route case (Operator role bound to a bearer subject reaches only the issue route).
- Full frontend `vitest run`: **146 files, 1080 tests passed**. `tsc -b --noEmit` and `api:check` clean. `eslint .` reports **one pre-existing error** in `src/mocks/decisionCsv.ts` (irregular whitespace), a file this change does not touch. New: `WebhooksPanel.test.tsx` (3), `CallbackWaitsPanel.test.tsx` (3), `MessageInspector.test.tsx` +1.
- `tools/contract-check/contract_check.py`: **PASS, 88 checks**.
- Not run: browser journeys and axe scans in a real browser (accessibility rests on labelled controls, status regions and component tests only), Docker stack rollout, two-process replica test for the webhook dispatcher (multi-pod safety rests on `FOR UPDATE SKIP LOCKED` and leases, not on a two-replica run).

## Not included

- **All of (c), the Kafka consumer trigger** (designed above, not built).
- Callback: retention/purge of callback rows, per-token request rate limiting, a timeline entry for issue and revoke.
- Webhooks: payloads with request data, per-subscription secrets rotation UI, custom headers, mutual TLS, DNS-pinned connections (the policy is re-checked before each attempt, as for HTTP steps), deleting subscriptions, an operator view of pending/delivered rows (API only).
