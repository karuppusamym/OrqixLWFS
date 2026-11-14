# S131 Step retry policy, Retry-After, circuit breaker and connection health

Wave A tickets **CG-W04** (per-step retry policy with exponential backoff and jitter, `Retry-After` for 429 and 503,
circuit breaker and `maxRequestsPerMinute` per binding) and **CG-C09** (connection health in Settings → Connections),
as designed in [doc 29 §5.6 and §6.12](../29-camunda-gap-implementation-plan.md). Compiler version **0.3.0** (D-CG1,
one bump for Wave A). Flyway **V220**.

## 1. Step retry policy (`config.retry`)

```json
"retry": { "maxAttempts": 6, "delay": "PT10S", "multiplier": 3, "maxDelay": "PT30M", "honorRetryAfter": true }
```

- Allowed on `http`, `externalTask` and `sendAdapter` steps (`$defs/stepRetry` in `contracts/workflow-v0.schema.json`).
  A step without it keeps `execution.maxAttempts` and `retryDelaySeconds` exactly as before; its BPMN is unchanged.
- Bounds: `maxAttempts` 1–10 and `multiplier` 1–4 in the schema (`WF_SCHEMA_INVALID`); durations use hours, minutes
  and seconds (`PT..H..M..S`). The compiler checks:

| Code | Sev | Rule |
|---|---|---|
| `WF_RETRY_DELAY_INVALID` | B | `delay` or `maxDelay` is not a `PT..H..M..S` duration |
| `WF_RETRY_DELAY_TOO_SHORT` | B | `delay` under 5 seconds |
| `WF_RETRY_DELAY_TOO_LONG` | B | `delay` over 1 hour |
| `WF_RETRY_MAX_DELAY_TOO_LONG` | B | `maxDelay` over 6 hours |
| `WF_RETRY_MAX_DELAY_BELOW_DELAY` | B | `maxDelay` shorter than `delay` |

- Defaults: `multiplier` 1, `maxDelay` 6 hours, `honorRetryAfter` true. `multiplier: 1` and `honorRetryAfter: true`
  are written defaults in the BPMN round trip (`BpmnDefaults.RETRY_RULES`, S70 rule).
- Generated BPMN: the step's `flowable:failedJobRetryTimeCycle` is `R<maxAttempts>/PT<delay>S`, and a new leaf
  `<orqix:retry maxAttempts delay multiplier [maxDelay] honorRetryAfter/>` sits in the service task's
  `extensionElements`. `BpmnReader` reads it back into `config.retry`; fixtures `step-retry-*` in
  `examples/fixtures/compiler-cases.json` round-trip identically. External worker steps also carry the step values in
  `orqix:external maxAttempts/retryDelaySeconds`.
- Model: the policies are kept in `WorkflowDefinition.Execution.stepRetry` (by step id), not on the node records, so
  the connection v1 and polling work on `Node.Http` stays independent.

## 2. Next attempt

`OrqixFailedJobCommandFactory` (S5 table row 14) reads `<orqix:retry>` from the failing service task:

- Attempt `n` (the effect journal attempt, else derived from the job's retries) waits
  `min(maxDelay, delay × multiplier^(n−1))` × (1 ± 10 % jitter), never more than `maxDelay`
  (`runtime.internal.StepRetryPolicy`).
- With `honorRetryAfter`, the server's `Retry-After` of a 429 or 503 (seconds or an HTTP date, `integration.RetryAfter`)
  is used as it is when it is not longer than `maxDelay`, otherwise `maxDelay`. A step without its own policy keeps the
  fixed workflow delay and ignores `Retry-After` (no behaviour change for existing releases).
- `Retry-After` reaches the factory as a new field: `HttpExecutor.CallResult.retryAfter` (set only on the 429 and 503
  branches; request building is untouched) → `EffectDispatcher.Failed.retryAfter` → `JobFailure.withRetryAfter`.
- Effect classes are unchanged: `unknown` and `permanent` outcomes dead-letter immediately whatever the policy says.
- Operator retry (`POST /runs/{id}/retry`) gives a step with its own policy its own `maxAttempts` back
  (`EngineCommands.moveDeadLetterToExecutable`).
- External worker jobs (`external_job`, V220 columns `retry_multiplier`, `retry_max_delay_seconds`,
  `honor_retry_after`): a worker's retryable failure waits the same formula; the worker's `retryAfterSeconds` plays the
  role of `Retry-After` (capped, or ignored with `honorRetryAfter: false`).

## 3. Circuit breaker per binding

`integration.health.ConnectionCircuit`, table `connection_circuit` (one row per workspace and connection key).

- Failures that a retry may cure (not sent, maybe applied — 5xx, 408, timeout, reset — and 429) count across all
  requests. `orqix.integration.circuit.failure-threshold` (default 10) in a row, all within
  `orqix.integration.circuit.window` (default 5 minutes), open the circuit. A success or a definitive answer (4xx,
  invalid response) resets the count.
- Open: calls are deferred to `open_until` (cool-down 1 minute, doubling after each failed trial up to 15 minutes,
  `cooldown` / `max-cooldown` properties). Then the circuit is half-open: exactly one call (the row is locked for the
  decision, so pods agree) is the trial, bounded by its timeout plus 15 s. Success closes and resets the cool-down;
  failure reopens for twice the cool-down.
- **Deferral does not use an attempt.** `CallGuard.before` runs in `EffectDispatcher.dispatch` before the effect
  journal registers anything and throws `CallDeferredException`; the failed-job command moves the job back to a timer
  at the end of the wait with its retries unchanged and writes `EFFECT_DEFERRED` (`reason`, `connectionKey`, `until`)
  instead of `EFFECT_FAILED`. The run stays `running`.
- Every state change runs in its own transaction on `connection_circuit` only (like the journal, TX-3).
- Agent connections and calls refused by policy (binding disabled, egress, missing credential) are not gated.

## 4. Request limit per binding

`maxRequestsPerMinute`, table `connection_rate_limit`, set with `PUT /workspaces/{id}/connections/{key}/rate-limit`
(`connection.manage`, audited `connection.rate-limit.updated`; `null` removes it). `BindingRateLimiter` enforces it on
each pod as `limit / live pods` (at least 1) with a sliding one-minute window; live pods are rows of `integration_pod`
refreshed every 30 s and seen in the last 90 s. A call over the limit is deferred exactly like an open circuit (no
attempt used, `EFFECT_DEFERRED` with `RATE_LIMITED`). Exact global limits need a shared limiter (doc 29 §12 decision);
the per-pod share can overshoot by the rounding while pods start or stop. The limit is a separate endpoint so the
binding contract that connection v1 rewrites is not touched.

## 5. Connection health (CG-C09)

`connection_call` keeps one row per call attempt, deferral and credential failure for 24 hours (pruned hourly):
outcome, HTTP status, error code, duration, never payloads, headers or secrets. Token refresh failures are counted
when an OAuth binding's credential cannot be obtained at dispatch.

API (tag `ConnectionHealth`, one contiguous block in `contracts/openapi.yaml`):

| Operation | Permission | Returns |
|---|---|---|
| `GET /workspaces/{id}/connection-health` (`listConnectionHealth`) | `connection.read` | Every connection, notice count |
| `GET /workspaces/{id}/connections/{key}/health` (`getConnectionHealth`) | `connection.read` | One binding |
| `PUT /workspaces/{id}/connections/{key}/rate-limit` (`putConnectionRateLimit`) | `connection.manage` | The binding's health |

Per binding: calls, succeeded and success rate, p50 and p95 latency, 429 count, token refresh failures and deferred
calls for the last hour and the last 24 hours; the last error (code, status, time); circuit state, failures in a row,
threshold, opened at, open until, reason, times opened; request limit, per-pod share and live pods; operator notices:

| Notice | Severity | When |
|---|---|---|
| `CIRCUIT_OPEN` | critical | Circuit open |
| `CIRCUIT_TRIAL` | warning | Half-open, trial call running |
| `TOKEN_REFRESH_FAILING` | critical | Any token refresh failure in the last hour |
| `LOW_SUCCESS_RATE` | warning | Under 90 % over at least 10 calls in the last hour |
| `RATE_LIMITED_BY_SYSTEM` | warning | Any 429 in the last hour (suggests a request limit when none is set) |
| `CALLS_DEFERRED` | info | Calls waited in the last hour while the circuit is closed |

Settings → Connections shows the notices above the list and a Health section in each connection card (circuit badge,
the two windows, last error, request limit; the limit form needs `connection.manage`). Mock mode serves fixed samples
(`frontend/src/mocks/connectionHealth.ts`). The run timeline describes `EFFECT_DEFERRED` in business words.

Not in this slice (doc 29 §6.12 lists them for later): certificate expiry, poll-trigger lag and dead records, alert
rules (CG-O07), notifications to connection managers (notices are shown on the page and logged), and a studio editor
for `config.retry` (the policy is authored in the definition JSON or imported BPMN today).

## 6. Evidence

| Test | What it proves |
|---|---|
| `compiler: StepRetryTest`, `CompilerCasesTest` (9 `step-retry-*` cases), `BpmnSourceTest` | BPMN output, bounds, defaults, semantic hash, BPMN round trip |
| `app: StepRetryPolicyTest` | Formula, jitter bounds, cap, `Retry-After` seconds and HTTP date |
| `app: StepRetryAndCircuitTest.exponentialBackoffWithJitterIsCappedAtMaxDelay` | 10 s, 30 s, 60 s (cap) ± 10 % on PostgreSQL/Flowable; retries 3, 2, 1 |
| `…retryAfterIsHonouredAndCappedAtMaxDelay` | 429 `Retry-After: 45` → 45 s; 503 `Retry-After: 3600` → `maxDelay` 120 s; health 429 count and last error |
| `…anUnknownOutcomeIsNeverRetriedAutomaticallyWhateverThePolicySays` | Non-idempotent 503: one call, dead letter, `EFFECT_UNKNOWN`, no timer |
| `…anOpenCircuitDefersTwoRequestsWithoutUsingAttemptsUntilATrialCallClosesIt` | Request A opens the circuit; A and B are deferred with retries unchanged and no journal attempt; B's trial closes it; A then succeeds with 2 attempts |
| `…aFailedTrialReopensTheCircuitForTwiceTheCooldownAndOldFailuresDoNotCount` | Window, one trial at a time, cool-down 60 → 120 s, close resets |
| `…theBindingRequestLimitDefersCallsWithoutUsingAttempts` | Limit 1/min defers the second call (`RATE_LIMITED`), 422 and 403 checks, audit |
| `SecurityMatrixTest` | The three operations declare permissions; foreign principals get 404 and change nothing |
| `frontend: ConnectionHealthPanel.test.tsx`, `events.test.ts` | Panel, notices, limit form, `EFFECT_DEFERRED` text |

`RuntimeTestBase` clears `connection_circuit` and `connection_rate_limit` before each test so failures injected by one
test never pause another. The resilience nodes (`resilience.Nodes`, two executors, restarts) inject a 500 into 20 runs
at once, which opens the circuit as designed; they set `orqix.integration.circuit.failure-threshold` very high because
they test engine retries and failover, not the circuit.

Behaviour change to note: a burst of 10 retryable failures across requests of one binding now pauses that binding for
a minute instead of retrying each request on its own schedule.
