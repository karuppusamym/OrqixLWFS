# S5 — Runtime execution
Build specification · revision 1.0 · 7 October 2026

Defines how the `runtime` and `integration` modules drive Flowable: commands, delegates, projections, external effects, recovery and multi-pod behavior. Data shapes: [S2](S2-data-model.md). Generated artifacts: [S4](S4-workflow-compiler.md).

## 1. Engine configuration (`runtime` module)

| Setting | Value | Reason |
|---|---|---|
| Engines | BPMN + DMN only; no Flowable REST, IDM, modeler or task app | Orqix owns identity, API and UI |
| Expression beans | Explicit map: `orqixRoute`, `orqixDecisionTask`, `orqixHttpTask`, `orqixNotificationTask` | No other bean reachable from expressions |
| Tenancy | `tenantId = workspace.engine_tenant_id` on every deployment, start and query | Tenant IDs do not enforce access; Orqix authorization always runs first |
| Event listener | `RuntimeProjectionListener`, `isFailOnException() = true` | Projection writes share the engine transaction (§2) |
| Failed-job handling | Custom failed-job command factory | Non-retryable failures dead-letter immediately (§6.4) |
| Async executor | Activated on pods, off in `migrate`; lock owner = pod name; lock time 60 s; expired-job reset 15 s; pool core 4 / max 6 | Values tuned by resilience tests |
| History | `audit` | Engine history for diagnostics; business history is Orqix's |
| Authenticated user | `Authentication.setAuthenticatedUserId(subjectId)` around user commands, cleared in `finally`; never set for jobs | Correct engine attribution without leaking thread state |

Only `com.orqix.runtime` may import `org.flowable` (ArchUnit). Every engine lookup by an external identifier (task ID, run ID) first resolves the Orqix projection **in the caller's workspace**; engine IDs from requests are never passed to Flowable unchecked.

## 2. Projection listener

`RuntimeProjectionListener` handles these engine events synchronously inside the engine command (spike S-10 confirms they fire in-transaction with the expected payloads):

| Engine event | Action |
|---|---|
| `TASK_CREATED` | Insert `task_context` (copy `initiator`, `self_approval_allowed`, compute `due_at` from `dueAfterHours`); add approval step to `current_steps`; lifecycle → `waiting` unless an http step is pending; event `TASK_CREATED` |
| Async job created for an activity (`ENTITY_CREATED` with a job entity) | Add `{kind: http}` step; lifecycle → `running` |
| `PROCESS_COMPLETED` | Lifecycle `completed`, outcome from the reached end node's `orqix:outcome`, output from `outputMapping`, `ended_at`; event `RUN_COMPLETED` |
| `PROCESS_CANCELLED` | Handled by the cancel command (§7.3); listener only asserts consistency |

The listener reads Orqix extension elements from the cached `BpmnModel` of the execution's `processDefinitionId`. If a write fails, the engine command and the user's request fail together.

## 3. Start a run (`POST /runs`)

One transaction; returns `201` only after commit.

1. Authenticate; authorize `workflow.start` on the workflow (workspace-wide or workflow-specific grant).
2. Claim the idempotency key (S3 §5).
3. Load the workflow's active release → none: `422 NO_ACTIVE_RELEASE`; `expectedReleaseId` differs: `409 RELEASE_NOT_ACTIVE`.
4. Validate `input` against the release `inputSchema` with `BigDecimal` numerics → `422 VALIDATION_FAILED` with `fieldErrors`. The initiator always comes from the principal, never from input.
5. Soft quota: active runs of the workflow ≥ `maxConcurrentRuns` → `429 QUOTA_EXCEEDED` (approximate under concurrent starts; documented).
6. Insert `workflow_run` (`running`, input) and event `RUN_STARTED`.
7. Start the engine instance by `processDefinitionId`, tenant, `businessKey = runId`, variables `orqix_runId`, `orqix_workspaceId`, `orqix_initiator`, `orqix_input`. The engine runs to the first wait state; the listener records the first task.
8. Store `engine_process_instance_id`; audit `run.started`; complete the idempotency record; commit.

## 4. Task commands

All task commands resolve `task_context` by `(workspaceId, taskId)` first (absent → 404) and use `expectedVersion` (S3 §6).

**Eligibility** of subject *s* for an open task: *s* has `task.act` in the workspace, *s* belongs to the workspace group `candidate_group_key` (directly or via an IdP group), and either `self_approval_allowed` or *s* ≠ initiator. Membership is evaluated with current policy on every command.

| Command | Preconditions → errors | Effects |
|---|---|---|
| Claim | Not visible → 404; not eligible → `403 NOT_ELIGIBLE` / `SELF_APPROVAL_DENIED`; not open → `409 TASK_NOT_OPEN`; claimed → `409 TASK_ALREADY_CLAIMED` | `taskService.claim`; set assignee; version+1; event `TASK_CLAIMED`; audit |
| Release | Caller is assignee | `taskService.unclaim`; version+1; event `TASK_RELEASED`; audit |
| Reassign | `run.operate`; target eligible or omitted (back to group) → else `422 VALIDATION_FAILED`; reason required | `setAssignee`; version+1; event `TASK_REASSIGNED`; audit. Never records a decision |
| Decide | Caller is assignee (else `409 TASK_NOT_CLAIMED_BY_CALLER`) and still eligible; idempotency key; reject needs non-blank reason (`422 REASON_REQUIRED`) | Below |

Decide transaction:

1. Version compare-and-set on `task_context`.
2. Insert `task_decision` (unique per engine task; a concurrent loser fails here → `409 TASK_NOT_OPEN`).
3. Set `orqix_step_<nodeId>` = `{decision, reason?, comment?, decidedBy, decidedAt}`.
4. `taskService.complete(taskId)`. The engine continues synchronously through gateways, decision tasks and notifications to the next wait state (next user task, async HTTP job, or end).
5. `task_context.status = completed`; event `TASK_DECIDED` (`action`, `hasReason`, `hasComment`); policy flag `selfApprovalAllowed` when applicable; audit `task.decided`; complete idempotency; commit → `201`.

`201` therefore means the decision **and** the continuation up to the next wait state are durable. No network call happens in this transaction.

## 5. Decision node (`orqixDecisionTask`, synchronous)

1. Read `orqix:decision` (key, version) from the model.
2. Resolve the input field value from `orqix_input` / `orqix_step_*` (missing → `IllegalStateException`: compiler guarantees availability, so absence is a defect and fails the transaction).
3. Execute the DMN decision **pinned to the process definition's deployment** (S4 §7).
4. Exactly one rule matches (FIRST + catch-all). Store typed outputs in `orqix_step_<nodeId>`.
5. Event `DECISION_EVALUATED` with `{decisionKey, version, matched: "r1"|"default", outputs}` (decision outputs are policy results, not request data).

`orqixRoute.condition(execution, transitionId)` reads the flow's `orqix:condition`, resolves the field the same way and compares with `BigDecimal.compareTo` / `String.equals` / `Boolean.equals`.

## 6. HTTP node and effect journal (`orqixHttpTask`, asynchronous job)

### 6.1 Algorithm

Runs inside a Flowable job transaction (job lock held by this pod).

1. Load `orqix:http` config, the pinned connection definition version and the workspace binding. Map inputs into the request body (types per the operation `requestSchema`; numbers stay `BigDecimal`). `effectKey = sha256(workspaceId|runId|nodeId|occurrence)`; `requestHash = sha256(JCS(body))`.
2. **Transaction A (REQUIRES_NEW, touches `effect_journal` only):** insert the journal row if absent (`pending`), lock it, then by state:

   | State found | Action |
   |---|---|
   | `succeeded` | Commit A; skip the call; go to step 5 with the stored result |
   | `unknown` | Commit A; throw non-retryable `EFFECT_UNKNOWN` (a job must not run while unreconciled) |
   | `inFlight`, lease valid | Commit A; throw retryable `BUSY` without calling |
   | `inFlight`, lease expired | `nonIdempotent` → set `unknown`, commit A, throw non-retryable `EFFECT_UNKNOWN`; otherwise continue (resend with the same key) |
   | `pending`, `retryableFailure`, `permanentFailure`, `notApplied` | Continue |

   Then: stored `request_hash` must equal the new hash (else non-retryable `INTERNAL_ERROR`). Pre-dispatch policy check: binding exists and is enabled, host passes egress policy (S6 §6) → else set `permanentFailure`, commit A, throw non-retryable `CONNECTION_UNAVAILABLE`. Set `inFlight`, `lease_owner` = pod, `lease_until` = now + timeout + 15 s, `attempts + 1`; commit A.
3. **Call** with JDK `HttpClient`: redirects disabled; connect timeout `min(5 s, timeoutSeconds)`; request timeout `timeoutSeconds`; headers `Content-Type`/`Accept: application/json`, `X-Correlation-Id`, auth from the secret file, and the operation's idempotency header (= `effectKey`) for `idempotentWithKey`. Response bodies above 1 MiB are rejected as invalid.
4. **Classify** (§6.2) and run **transaction B (REQUIRES_NEW, journal only):** new state, `last_http_status`, `last_error_code`, redacted `last_error_detail`, and for success the mapped `result` and `remote_reference`.
5. **Success:** in the job transaction set `orqix_step_<nodeId>` = result, event `EFFECT_SUCCEEDED`, remove the http step, return; the engine continues to the next wait state and the job transaction commits. If that commit fails, the job retries and step 2 finds `succeeded`, so the remote system is not called twice.
6. **Failure:** throw retryable or non-retryable (with attention reason); the job transaction rolls back, the journal (A/B) is preserved.

Rule: REQUIRES_NEW transactions touch **only** `effect_journal`. They never write `workflow_run`, `run_event` or `task_context`, so they cannot wait on a row lock held by their own suspended job transaction.

### 6.2 Outcome classification

| Observation | `readOnly` | `idempotentWithKey` | `nonIdempotent` |
|---|---|---|---|
| Not sent: DNS failure, connection refused, TLS handshake failure, connect timeout | retryable | retryable | retryable |
| Maybe applied: read timeout, connection reset after write, 5xx, 408 | retryable | retryable | **unknown** |
| 429 (server declined) | retryable | retryable | retryable |
| 2xx in `successStatuses`, body valid, all result pointers resolve | succeeded | succeeded | succeeded |
| 2xx not in `successStatuses`, invalid body or missing pointer | permanent (`RESPONSE_INVALID`) | **unknown** | **unknown** |
| 3xx (redirects are never followed) | permanent | **unknown** | **unknown** |
| Other 4xx (400, 401, 403, 404, 409, 422 …) | permanent | permanent | permanent |

`unknown` and `permanent` never retry automatically. A remote outcome that may have happened is never shown as plain "Failed".

### 6.3 Attention reasons

| Reason | Cause | Operator path |
|---|---|---|
| `RETRIES_EXHAUSTED` | Retryable failures used all attempts | Retry after the dependency recovers |
| `EFFECT_UNKNOWN` | Maybe-applied outcome on a non-idempotent call, or invalid success response | Reconcile first (check the remote system), then resume or retry |
| `EFFECT_REJECTED` | Definitive 4xx (often credentials or contract) | Fix binding/contract, then retry |
| `RESPONSE_INVALID` | Read-only call returned an invalid body | Fix contract, then retry |
| `CONNECTION_UNAVAILABLE` | Binding missing/disabled or egress denied at dispatch | Fix binding, then retry |
| `POLICY_DENIED` | Reserved for R1 connection-use grants revoked at dispatch | Restore grant or cancel |
| `INTERNAL_ERROR` | Defect (unexpected exception) | Engineering triage; retry when fixed |

### 6.4 Failed-job handling

The custom failed-job command runs in a new transaction after the job transaction rolled back:

- Retryable exception → default decrement using the node's retry cycle; event `EFFECT_FAILED` with `nextRetryAt`.
- Non-retryable exception → retries set to 0.
- When retries reach 0 (job moved to the dead-letter table): lifecycle `needsAttention` with `attention`, event `RUN_NEEDS_ATTENTION`, in-app notification to the initiator ("Your request is delayed; an operator has been alerted", dedup key per run + effect + attempt count), metric gauge update.
- Unexpected exceptions are treated as retryable until exhausted, then `INTERNAL_ERROR`.

## 7. Operator actions

All require `run.operate`, an idempotency key, `expectedVersion`, a reason, and write audit + run events.

### 7.1 Retry (`POST /runs/{id}/retry`)
Allowed when the run is `needsAttention` and the effect is not `unknown` (`409 EFFECT_NOT_RECONCILED`). Moves the dead-letter job back to executable with retries = `maxAttempts`; lifecycle `running`; clears attention; event `OPERATOR_RETRY`.

### 7.2 Reconcile (`POST /runs/{id}/effects/{effectId}/reconcile`)
For `unknown` or `permanentFailure` effects. Evidence note (≥ 10 chars) mandatory; `remoteReference` recommended.
- `confirmedApplied`: `result` keys must equal the node's `resultMapping` keys and match the response types. Journal → `succeeded` with the supplied result and `resolution`; dead-letter job → executable; the delegate finds `succeeded` and continues **without calling the remote system**.
- `confirmedNotApplied`: journal → `notApplied`; the run stays `needsAttention` until the operator explicitly retries (two deliberate steps).
Event `EFFECT_RECONCILED`. Orqix never fabricates a success without operator evidence.

### 7.3 Cancel (`POST /runs/{id}/cancel`)
Allowed for `running`, `waiting`, `needsAttention`. `deleteProcessInstance(pid, reason)`; if a job currently holds the instance, Flowable's optimistic lock fails → `409 RUN_BUSY` (retry shortly). Open tasks → `cancelled`; lifecycle `cancelled`; event `RUN_CANCELLED` with the count of already-succeeded effects; response lists them in `completedEffectsNotReversed`; initiator notified. Completed external effects are **not** reversed.

## 8. Run event catalog

Events are written in the same transaction as the change they describe, with the per-run sequence from S3 §7. `data` never contains request field values, credentials or remote bodies.

| Type | Written by | `data` |
|---|---|---|
| `RUN_STARTED` | Start command | `releaseNumber`, `businessKey` |
| `TASK_CREATED` | Projection listener | `label`, `candidateGroup`, `dueAt` |
| `TASK_CLAIMED`, `TASK_RELEASED` | Task command (actor) | `assignee` display name |
| `TASK_REASSIGNED` | Operator (actor) | `from`, `to` display names, `reason` |
| `TASK_DECIDED` | Decide command (actor) | `action`, `hasReason`, `hasComment`, `policyFlags` |
| `DECISION_EVALUATED` | Decision delegate | `decisionKey`, `version`, `matched`, `outputs` |
| `EFFECT_SUCCEEDED` | HTTP delegate | `connectionKey`, `operation`, `attempt`, `httpStatus`, `remoteReference` |
| `EFFECT_FAILED` | Failed-job command | `attempt`, `classification`, `httpStatus`, `errorCode`, `nextRetryAt` |
| `EFFECT_UNKNOWN` | Failed-job command | `attempt`, `errorCode` |
| `RUN_NEEDS_ATTENTION` | Failed-job command | `reason`, `effectId` |
| `OPERATOR_RETRY` | Operator (actor) | `reason` |
| `EFFECT_RECONCILED` | Operator (actor) | `resolution`, `remoteReference` |
| `NOTIFICATION_CREATED` | Notification delegate / failed-job command | `kind`, `recipient: initiator` |
| `RUN_COMPLETED` | Projection listener | `outcome`, `outcomeKind` |
| `RUN_CANCELLED` | Cancel command (actor) | `reason`, `completedEffects` |

## 9. Notifications (`orqixNotificationTask`)

Synchronous, inside the current engine transaction: insert `notification` (kind `workflowMessage`, recipient = `orqix_initiator`, message from `orqix:notification`, dedup key `runId:nodeId:occurrence:initiator`); a duplicate key is ignored (idempotent on retry). In-app delivery is the only channel in R0; the UI never claims email or Teams delivery.

## 10. Transaction and concurrency rules

| ID | Rule |
|---|---|
| TX-1 | Each user command is one database transaction; no network I/O inside it |
| TX-2 | Outbound HTTP happens only in async job delegates |
| TX-3 | REQUIRES_NEW transactions touch only `effect_journal` (§6.1) |
| TX-4 | A failed audit write fails the command |
| TX-5 | Projection writes run in the engine transaction; a failure fails the command |
| TX-6 | User transactions use `lock_timeout = 10s` and `statement_timeout = 30s`; job transactions `statement_timeout = 60s` |
| TX-7 | Engine optimistic-lock failures on user commands map to `409` (task) or `RUN_BUSY` (run), never 500 |

## 11. Multi-pod operation and capacity

- Every pod runs the API and the job executor (same image). A separate worker deployment later only changes scaling, not semantics.
- Flowable acquires jobs with lock owner + expiry; a killed pod's jobs are re-acquired after the lock time plus the reset interval (target ≤ 90 s with the R0 values; the doc 01 target of 120 s is the acceptance bound).
- Lock time (60 s) must exceed the maximum HTTP timeout (30 s) plus journal writes; the compiler caps `timeoutSeconds` at 30.
- A job transaction holds one database connection for the duration of its HTTP call. Keep executor max threads ≤ pool size − 9 (6 of 15) so API requests always have connections.
- **Connection budget:** `pods × 15 + 5 (migration job) + 10 (operations) ≤ 0.8 × max_connections`. With `max_connections = 100`: at most 4 pods (75 connections). The HPA ceiling must respect this budget and external API rate limits.
- No pod-local schedulers for business work; no sticky sessions (JDBC sessions); polling reads the durable event feed, so any pod can serve any request.
- Graceful shutdown: readiness goes false, job acquisition stops, in-flight requests and jobs drain within 45 s (`terminationGracePeriodSeconds: 60`). `flowable.process.async.executor.unlock-owned-jobs: false` is required: Flowable's default unlocks the pod's jobs at the start of shutdown, before the job pool has drained, so a running job failed to commit and recorded a spurious failure (found by T-RES-05). Jobs of a stopped pod are re-acquired by other pods after the lock expires, as for a killed pod. Liveness never checks the database (no restart storms during a DB outage).
- Database outage: `DatabaseUnavailableFilter` (outermost servlet filter) turns database-unavailable errors raised outside MVC, such as the JDBC session lookup, into `503 DEPENDENCY_UNAVAILABLE` with `Retry-After`. Hikari `connection-timeout` is 10 s, so requests fail fast instead of holding threads (T-RES-04).

## 12. Guarantees to verify (S9)

| Situation | Guarantee |
|---|---|
| Client receives 201 for start or decision | The change and its continuation to the next wait state are committed |
| Application restart while a task waits | Task, its pinned form/release and the inbox entry are intact |
| Pod killed during an HTTP call | Another pod resumes (measured 73–76 s after kill -9, T-RES-03); idempotent operations resend with the same key; non-idempotent ones become `unknown` and need an operator; no blind duplicate |
| Two users decide the same task | Exactly one decision row and one engine continuation; the other gets 409 |
| Database unavailable | Requests return `503 DEPENDENCY_UNAVAILABLE`; no success response for uncommitted work; jobs retry after recovery |
| New release published while runs wait | Waiting runs continue on their original `processDefinitionId` |
