# S23 - Durable message events

The Wait for a message step is executable. It matches an exact workspace, message name and correlation key, validates a flat typed scalar payload, and follows next or the configured timedOut route. Input text keys must prove a nonempty length of at most 200 through form constraints or finite enum values; integer keys use canonical whole-number decimal text. The example is `examples/order-payment-wait.json`.

`POST /api/v1/workspaces/{workspaceId}/messages` requires `message.send`, supports authorized people and machine identities, and requires an idempotency key. Payload values and correlation keys do not enter global audit records. Delivery records are scoped to their run. A matching message fans out to at most 25 waiting requests; it is not an unrestricted broadcast. An early message is buffered and consumed by one later wait. Defaults are 24-hour buffer lifetime, 16 KiB payload, 5,000 buffered messages per workspace and 10 per name/key. Configuration rejects unbounded values.

One transaction covers receipt, validation, delivery, engine continuation, audit and idempotency. Send transactions serialize within a workspace before quota checks; matching runs are locked in deterministic run-id order. Buffer maintenance skips busy runs and subscriptions rather than waiting while holding work rows. After-commit consumption and the sweeper recover waits interrupted by a process restart. Cancellation and timeout close subscriptions so stale messages cannot continue a closed wait.

An immutable acceptance response is retained in V21 and replayed exactly, including an original buffered response after eventual consumption or expiry. Maintenance retains message rows referenced by live idempotency records. If an original acceptance cannot be recovered, replay fails explicitly rather than inventing a delivery count. Expiry and retention are bounded; defaults sweep every five seconds and retain closed records seven days.

Evidence: compiler MessageEventsTest and the order-payment-wait BPMN golden; runtime MessageEventsTest covers authorization, payloads, timing, durable recovery, concurrent delivery, concurrent quota enforcement and exact replay after consumption/expiry. Frontend message inspector, model and mock tests cover authoring and routing. Java verification is coordinated by the root build; do not infer passing tests from this document alone.

Limits: message send is workspace permission scoped, not per-request recipient authorization; callers must use appropriately specific keys. No arbitrary expression evaluation, nested payload objects or unbounded correlation fan-out is supported.

Camunda source import supports the bounded message-catch slice described in S11: a canonical declared message name and one plain-variable correlation subscription, with explicit review of payload omission and buffering differences. Ambiguous declarations/subscriptions remain blockers.
