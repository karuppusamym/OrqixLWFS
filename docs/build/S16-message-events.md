# S16 — Message events

Implemented 11 October 2026. Contracts remain at schema version `0.1` (additive node kind and message API). An external system, or another process, can tell a waiting request "payment received", "documents arrived" or "the customer replied".

Current concurrency, quota, replay and retention guarantees are maintained in [S23 - Durable message events](S23-message-events.md). This document records the original implementation contract.

## 1. What it is

A new step **Wait for a message** (`waitForMessage`) pauses a request until a named message with a matching *correlation key* arrives. A new API, `POST /api/v1/workspaces/{ws}/messages`, delivers messages. There is no broadcast: see section 7.

```json
{ "id": "waitPayment", "type": "waitForMessage", "label": "Wait for the payment",
  "config": { "message": "payment-received", "correlationField": "/input/orderNumber", "timeoutAfter": "P2D",
              "payload": [ { "name": "paymentReference", "type": "string", "required": true },
                           { "name": "amountPaid", "type": "number" } ] } }
```

| Config | Meaning |
|---|---|
| `message` | Message name, `^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$`, 2 to 64 characters (kebab-case or camelCase), for example `payment-received`. |
| `correlationField` | A field reference (`/input/x` or a guaranteed earlier step output) whose value identifies the request. Text or whole number. The message must carry the same value (exact, case-sensitive match). |
| `timeoutAfter` | Optional ISO duration, same limits as a wait (`PT1M` to `P90D`). Adds the route `timedOut` (exactly one). |
| `payload` | Optional list (at most 10) of values the message may carry: `name` (like a form field key), `type` (`string`, `number`, `integer`, `boolean`, `date`), optional `title`, optional `required`. They are the step's outputs (`/steps/<id>/<name>`). |

Routes: `next`, plus `timedOut` when a time limit exists.

## 2. Compiler (S4 sections 5 and 6 updated)

Rules (all blockers): `WF_MESSAGE_CORRELATION_UNKNOWN` (the field does not exist), `WF_MESSAGE_CORRELATION_TYPE` (not text or a whole number), `WF_MESSAGE_CORRELATION_OPTIONAL` (an optional request field cannot identify a request), `WF_MESSAGE_PAYLOAD_DUPLICATE`, `WF_MESSAGE_TIMEOUT_ROUTE` (a time limit needs exactly one `timedOut` path; a `timedOut` path without a time limit is `WF_OUTGOING_INVALID`), `WF_MESSAGE_DUPLICATE_WAIT` (two waits for the same message name and the same correlation field in different paths of one "Run in parallel" would compete for one message). The time limit reuses `WF_DURATION_*`. The message name and payload names are checked by the schema (`WF_SCHEMA_INVALID`).

Availability follows the usual dominance rules plus one more: a payload value that is *required* is guaranteed only on the `next` path. With a time limit, a step reached from both the `next` and the `timedOut` path cannot use it (`WF_FIELDREF_NOT_GUARANTEED`), and it cannot be a required result when a Finish step is also reachable through `timedOut` (`WF_OUTPUT_NOT_GUARANTEED`). A value that is not required may be missing everywhere. A `timedOut` path may leave a parallel branch when it leads to a rejection tail, exactly like a timed-out approval.

BPMN (deterministic names): one `<message id="message__<name>" name="<name>"/>` per message name before the process (sorted). Without a time limit the step is one `intermediateCatchEvent` (id = step ID) with a `messageEventDefinition`. With a time limit it is an `eventBasedGateway` (id = step ID) followed by `<id>__message` (message catch event) and `<id>__timeout` (timer catch event, `timeDuration`); the canonical `next` path leaves from `<id>__message`, `timedOut` from `<id>__timeout`. (A boundary event cannot be attached to a catch event in BPMN, so the event gateway is the standard form for "message or time limit, whichever comes first".) The message event carries `orqix:message node name correlationField` with `orqix:field name type required` children; the timer carries `orqix:messageTimeout node after`. **No new expression:** the message name is a literal, and the correlation is done by Orqix tables, so the closed expression allowlist (S4 section 6) is unchanged. Golden files: `examples/order-payment-wait.json`.

## 3. Runtime

### Tables (V21)

`message_subscription` (one row per wait: workspace, run, step, message name, correlation key, engine execution id, `waiting | received | timed_out | cancelled`, the message that ended it, deadline) and `inbound_message` (one row per API call: name, correlation key, payload, `buffered | delivered | expired | discarded`, how many waits it reached, expiry). Unique `(run_id, node_id)`. `permission_grant` now accepts `message.send`.

### Correlation and delivery

* The engine projection listener sees the engine's message subscription being created, reads the correlation value from the process data, writes the subscription row, shows the step and records `WAIT_STARTED` (with `message`). The step has `kind: wait`, `messageName` and `dueAt` (the time limit).
* `POST /messages` finds subscriptions of **active** requests in the **caller's workspace** with the same `(name, correlation key)`, oldest first, and delivers to each. Cross-workspace delivery is impossible: every query is scoped by the workspace in the URL and the caller must be visible there (404 otherwise).
* Delivery locks the request row, then the subscription row; the subscription leaves `waiting` exactly once, so a message reaches a given wait at most once even when two pods or two senders race (tested with 8 parallel senders: exactly one match). It then writes the declared values into the step output, records `WAIT_ENDED` (`outcome: received`) and calls the engine's `messageEventReceived`; the engine continues in the same transaction. A timer that wins the race, or a competing message, shows up as an optimistic-lock conflict and the API returns `409 RUN_BUSY` (retry with the same Idempotency-Key; nothing was changed).
* One transaction covers the message row, every delivery, the audit record and the idempotency record.
* A message is delivered to **every** matching waiting request (up to 25; more is `422`: use a more specific key). Messages are not queued per request.

### Buffering decision

A message that finds no waiting request is **kept** (`status: buffered`) for `orqix.messages.buffer-ttl` (default **24 hours**) and consumed by the **first** wait that starts with the same name and key (oldest message first, once); after the window it expires and is dropped (the row is emptied at once and deleted after `retention`, 7 days). The consumption happens right after the wait's transaction commits and, as a safety net for a pod that stopped in between, in a sweeper that runs every 5 seconds on every pod (`FOR UPDATE SKIP LOCKED`, idempotent). A buffered message is checked against the wait's declared values only when it is consumed: if it does not fit, it is discarded, the timeline records `MESSAGE_DISCARDED` and the wait goes on. Bounds: 10 buffered messages per `(name, key)`, 5,000 per workspace (`429 RATE_LIMITED`, `Retry-After`). A message that arrives after the time limit passed is kept like any message nobody waits for. A message for a cancelled or finished request is never delivered (the subscription is closed; the message is kept like any unmatched one).

Why 24 hours: long enough for "the payment arrived before the order was entered" (the usual race between two systems), short enough that stale data and the table stay small. Change it per deployment.

### Run lifecycle and timeline

The run is `waiting`. Run detail says "Waiting for message payment-received (gives up ...): Wait for the payment". Timeline: "Wait for the payment: waiting for the message payment-received", then "... the message payment-received arrived" (adds "(it was received before the wait started)" for a buffered one) or "... did not arrive in time". Events reuse `WAIT_STARTED` and `WAIT_ENDED` (so the run diagram and insights need no change); `WAIT_ENDED` carries `outcome` (`received` or `timedOut`), `buffered` and the *names* of the values received. Event data never contains payload values or the correlation key. A new event `MESSAGE_DISCARDED`. The last values are available to later steps as process data.

### Limits and validation

Payload: a flat object of at most 10 scalar values, names like request fields, text up to 1,000 characters, at most `orqix.messages.max-payload-bytes` (16 KiB; `413`). Sent values must be declared by the waiting step (`422` with `fieldErrors` under `/payload/...`: `required`, `type`, `unknown`). Nothing is delivered or kept when validation fails. `Idempotency-Key` is required: a replay returns the same message and `Idempotent-Replayed: true`; the same key with another body is `409`.

## 4. Authorization decision

`message.send` is a new **workspace-wide permission**. It is part of the **Operator** role (so operators can send test messages from run detail) and can be **granted explicitly** like `connection.manage` (`POST /access/grants`, `workflowId` omitted) to a person, a group or a **machine client**. Machine clients (bearer JWT) are now allowed to call `sendMessage` in addition to `startRun`, `getRun` and `listRunEvents`; they still need the grant, otherwise `403` (and `404` when they know nothing of the workspace). The generated security matrix covers the operation. The permission is checked for the workspace of the URL only. Granting it per message name is a follow-up.

## 5. Audit and privacy decision

Audit action `message.received` (actor type `service` for machine clients), resource `message`, metadata exactly `{name, matched, buffered}`. **Never the correlation key and never payload values**: both can be personal (order numbers, names, amounts). The same rule holds for run events and logs. The correlation key is stored (it is needed to match) in `message_subscription` and `inbound_message`, and equals data that is already in the request; payloads of buffered messages are removed as soon as the message is delivered, expired or discarded.

## 6. Studio and API use

* Palette **Wait for a message**; inspector: message name (validated), "Which field identifies the request?" (text and whole-number questions and earlier outputs), time limit with a plain-language reading, and the values the message may carry (name, kind, "The message must carry it"). The canvas card shows "payment-received · Order number" and a Timed out handle with a time limit; the Steps tab lists both routes. Preview: "Waits until a message named payment-received arrives for this request's Order number."
* Run detail: for people with `message.send`, **Send test message** (name fixed, identifying value filled from the request, one input per declared value, no reason needed). It calls the API.
* Mock mode: the in-browser API implements the same delivery, buffering, time limit (shortened like every timer) and validation.
* Template **Hold an order until the payment arrives** (`examples/order-payment-wait.json`).

```
curl -X POST https://orqix.example/api/v1/workspaces/$WS/messages \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: pay-SO-1043-1" -H "Content-Type: application/json" \
  -d '{"name":"payment-received","correlationKey":"SO-1043","payload":{"paymentReference":"PAY-77","amountPaid":125.5}}'
# 202 {"id":"...","status":"delivered","matched":1,"delivered":[{"runId":"...","runReference":"ORQ-1043","nodeId":"waitPayment"}],"bufferedUntil":null}
```

Demo: publish the template, start a request with order number `SO-1043`, open its run, click **Send test message** (payment reference required), watch the run finish "Order paid". Start a second request, do nothing for the time limit (mock mode: seconds) and watch it finish "Cancelled no payment".

## 7. Why there are no signals

Camunda's signal events broadcast to every listener in the engine. Orqix does not offer them: (1) **blast radius**: one call could advance every waiting request of a workflow or workspace, with no way to say which; a wrong or replayed call is an incident, while a correlated message touches only the requests it names; (2) **authorization**: "who may wake all of these" has no natural answer, and a broadcast crosses the per-request visibility the product is built on; (3) it cannot be made idempotent per recipient in a way an operator can audit. A message with a correlation key is the safe 95 percent of the need. If a real case appears, it needs its own permission, a named audience and a dry-run, not an extension of this API.

## 8. Importer mapping

For the Camunda importer (`com.orqix.migration.camunda`): a BPMN **intermediate message catch event** (and a **receive task**) maps to `waitForMessage`: `message` is the message `name` (converted to lowercase kebab-case when needed, reported as a conversion note); `correlationField` is the **correlation key expression** when it is a plain variable reference (`=orderNumber`, `${orderNumber}`) that matches a request field of the imported form (`/input/orderNumber`); any other expression (functions, concatenation, nested paths) is reported as a manual step with the original text. A boundary timer on a receive task, or an event-based gateway with a timer branch, maps to `timeoutAfter` plus the `timedOut` route. Message **start** events, throw events, message boundary events (interrupting or not) and signal events have no equivalent (report as unsupported). Variables the process reads from the message become `payload` entries only when the form or an output mapping names them; the importer cannot guess types and should use `string`.

## 9. Verification

Compiler: `MessageEventsTest` (schema, every `WF_MESSAGE_*` rule, availability, parallel duplicates, generated BPMN, golden files `order-payment-wait`, determinism). Application (PostgreSQL, Flowable, public API validated by the OpenAPI contract): `MessageEventsTest` (after the wait starts, before it starts within the window, expired window, discarded buffered message, duplicate delivery and replay, timeout route, wrong key, payload validation, permission 403, foreign workspace 404, cancelled request, executor restart, 8 concurrent senders, two requests with one key), `SecurityMatrixTest` (the generated sweep includes `sendMessage`; bearer token needs the grant). Frontend: Vitest (model, mock engine, inspector), Playwright mock spec `t-ui-13-message-wait.spec.ts`. Real stack: `python tools/api-e2e/e2e_messages.py` (scenario 40).

## 10. Limits and follow-ups

* Matching is exact and case-sensitive; there is no trimming or normalisation of keys.
* No per-message-name permission, no outbound `sendMessage` step, no message start events, no signals (section 7).
* The correlation key must be a text or whole number that exists when the wait starts; a key that changes later does not move the subscription.
* A buffered message waits for the first matching wait only; a second request with the same key finds nothing (send another message).
* Message names are not checked against published workflows at send time (a typo is simply buffered until it expires).
* A message is checked against the declared values once a wait exists; a buffered message that does not fit is discarded, not reported to the sender.
