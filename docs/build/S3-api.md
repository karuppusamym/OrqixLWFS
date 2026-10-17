# S3 — API rules
Build specification · revision 1.0 · 7 October 2026

Authoritative contract: [`contracts/openapi.yaml`](../../contracts/openapi.yaml) (OpenAPI 3.1, 48 operations; structural checks passed: all references resolve, every mutating operation declares `x-orqix-permission` and `x-orqix-audit`). This page defines the cross-cutting behavior every endpoint must implement. Changes to an endpoint and to `openapi.yaml` ship in the same PR.

## 1. Versioning and shape

- Base path `/api/v1`. Within v1, changes are additive only (new optional fields, new endpoints, new problem codes). Removing or retyping a field requires `/api/v2`.
- JSON only (`application/json`; errors `application/problem+json`; export `application/zip`). UTF-8. Timestamps are RFC 3339 UTC with `Z`. Money and other decimals are JSON numbers that the server reads as `BigDecimal`; the client never does arithmetic on them.
- Unknown request properties are rejected (`additionalProperties: false`) with `400 MALFORMED_REQUEST`, not ignored.

## 2. Authentication summary

Details and threat controls are in [S6](S6-security.md).

| Client | Mechanism | Scope in R0 |
|---|---|---|
| Browser (SPA served same-origin) | OIDC authorization code + PKCE via Spring Security; server-side JDBC session cookie `ORQIX_SESSION` (Secure, HttpOnly, SameSite=Lax); tokens never reach the browser | All operations |
| Machine client | Bearer access token (JWT) validated for issuer, audience, expiry; mapped to a `subject` row (issuer + `sub`) | `startRun`, `getRun`, `listRunEvents` only, with an explicit `workflow.start` grant |

Unauthenticated `/api/**` requests return `401 UNAUTHENTICATED` problem JSON (never a login redirect). The SPA then navigates to `/oauth2/authorization/orqix`.

**CSRF:** browser state changes (POST/PUT/DELETE) must send header `X-XSRF-TOKEN` equal to the `XSRF-TOKEN` cookie (Spring Security cookie repository, SPA mode). Missing or wrong token → `403 CSRF_INVALID`. Bearer-token requests are exempt.

## 3. Authorization rules visible at the API

1. The workspace is taken from the **stored resource**, then compared with `{workspaceId}` in the path. A mismatch is treated as not found.
2. If the caller cannot read the resource → `404 NOT_FOUND`. If the caller can read it but lacks the action permission → `403 FORBIDDEN` (or a more specific 403 code). Non-members of a workspace receive 404 for every path under it, including `GET /workspaces/{id}`.
3. `x-orqix-permission` values used in the contract:

| Value | Meaning |
|---|---|
| `authenticated` | Any signed-in subject |
| `workspace.member` | Any role binding or permission grant in the workspace (direct or through an IdP group) |
| A permission name | That permission in the resource's workspace (S6 §3) |
| `initiator` | The run's initiator (own requests) |
| `taskParticipant` | Subject who holds or held a task in the run, or is an eligible candidate for an open one |
| `candidate` | Member of the task's candidate group, not blocked by separation of duties |
| `assignee` | Current assignee of the task |
| `recipient` | Recipient of the notification |
| `a \| b` | Either suffices; `a + b` both required |

4. Responses are filtered by permission, not only gated: `RunDetail.input`/`output` are `null` unless the caller is the initiator, a task participant or holds `run.read-data`; `effects` only appears for `run.operate`; connection bindings only for `connection.manage`.

## 4. Error model (RFC 9457)

Every error body:

```json
{
  "type": "https://orqix.example/problems/task-version-conflict",
  "title": "The task changed since you loaded it",
  "status": 409,
  "detail": "Reload the task to see its current state.",
  "code": "TASK_VERSION_CONFLICT",
  "correlationId": "c0a8017e-6f1b-4b7e-9d0c-2a4f5e0d9b11",
  "currentVersion": 4
}
```

- `code` is the stable machine key (enum in OpenAPI); `title`/`detail` are human text and may change. Clients switch on `code` only.
- Never include stack traces, SQL, class names, remote response bodies or values of sensitive fields.
- `fieldErrors[]` (`pointer`, `code`, `message`) for request/form validation; `issues[]` (`ValidationIssue`) for workflow validation; `currentRevision`/`currentVersion` for concurrency conflicts.

| Status | Codes |
|---|---|
| 400 | `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_CURSOR` |
| 401 | `UNAUTHENTICATED` |
| 403 | `FORBIDDEN`, `CSRF_INVALID`, `SELF_APPROVAL_DENIED`, `NOT_ELIGIBLE` |
| 404 | `NOT_FOUND` |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `DUPLICATE_KEY`, `WORKFLOW_VERSION_CONFLICT`, `RELEASE_NOT_ACTIVE`, `TASK_VERSION_CONFLICT`, `TASK_ALREADY_CLAIMED`, `TASK_NOT_CLAIMED_BY_CALLER`, `TASK_NOT_OPEN`, `RUN_NOT_ACTIVE`, `RUN_BUSY`, `RUN_NOT_RECOVERABLE`, `RUN_SUSPENDED`, `RUN_NOT_SUSPENDED`, `RUN_NOT_SUSPENDABLE`, `RUN_NOT_MIGRATABLE`, `EFFECT_NOT_RECONCILED`, `EFFECT_STATE_CONFLICT`, `GROUP_REVISION_CONFLICT` |
| 412 | `STALE_REVISION` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `VALIDATION_FAILED`, `WORKFLOW_INVALID`, `WARNINGS_NOT_ACKNOWLEDGED`, `NO_ACTIVE_RELEASE`, `REASON_REQUIRED`, `CONNECTION_NOT_BOUND`, `EGRESS_NOT_ALLOWED`, `OPERATION_NOT_TESTABLE` |
| 428 | `PRECONDITION_REQUIRED` |
| 429 | `QUOTA_EXCEEDED`, `RATE_LIMITED` (with `Retry-After`) |
| 503 | `DEPENDENCY_UNAVAILABLE` (with `Retry-After`; nothing was committed) |

## 5. Idempotency (start, publish, decision, cancel, retry, reconcile)

Header `Idempotency-Key`, 8–128 chars `[A-Za-z0-9._:-]`, unique per logical command (clients use a UUID created when the user first presses the button and reuse it for retries). Behavior follows the IETF HTTPAPI Idempotency-Key draft (still an Internet-Draft; we implement the semantics below, not a ratified RFC).

Algorithm (one database transaction with the command):

1. Validate the header (`400 IDEMPOTENCY_KEY_REQUIRED` if absent on a required operation).
2. Authenticate and authorize the command with **current** permissions. A replay never bypasses a revocation.
3. `requestHash = SHA-256(JCS({operation, pathParams, body}))`.
4. `INSERT INTO idempotency_record … ON CONFLICT DO NOTHING` keyed by (workspace, principal, operation, key), `expires_at = now() + 30 days`. A concurrent duplicate blocks on the unique index until the first transaction ends (`lock_timeout` 10 s → `503` with `Retry-After`).
5. Inserted → execute the command, then set `resource_type`, `resource_id`, `response_status`, commit, return the original status (201/200).
6. Existing row, expired → delete it and continue as step 5.
7. Existing row, different `request_hash` → `409 IDEMPOTENCY_KEY_REUSED`.
8. Existing row, same hash → return `200` with `Idempotent-Replayed: true` and the **current** representation of the stored resource (a replayed start returns the run as it is now).
9. If the command fails, the whole transaction including the record rolls back, so the client may retry with the same key.

## 6. Concurrency

| Resource | Token | Behavior |
|---|---|---|
| Draft | `ETag: "r<revision>"`, `If-Match` required on PUT | Missing → `428`; stale → `412 STALE_REVISION` with `currentRevision`. Implemented as `UPDATE workflow SET draft_revision = n+1 WHERE id = ? AND draft_revision = n` then insert the revision row |
| Connection binding | `ETag: "r<revision>"` | Same as drafts once a binding exists |
| Task | `expectedVersion` (body) | `UPDATE task_context SET version = version + 1 WHERE engine_task_id = ? AND version = ?` → 0 rows = `409 TASK_VERSION_CONFLICT` with a fresh snapshot; Flowable's own optimistic locking is a second guard |
| Workflow active release | `expectedVersion` | `409 WORKFLOW_VERSION_CONFLICT` |
| Run (cancel/retry) | `expectedVersion` | `409 RUN_NOT_ACTIVE` / `RUN_BUSY` when the run changed or a job holds the instance |
| Group | `expectedRevision` | `409 GROUP_REVISION_CONFLICT` |

## 7. Lists and pagination

`limit` 1–100, default 25. Keyset pagination on `(created_at DESC, id DESC)` (decisions: `decided_at`). `nextCursor` is opaque (base64url of the last sort key plus a filter hash, HMAC-signed with an instance key so it cannot be forged or reused with different filters → `400 INVALID_CURSOR`). Totals are not returned except the bounded task counters on `listTasks` and `unreadCount` on notifications.

Run events use `?after=<seq>` instead of a cursor. The per-run sequence is allocated by `UPDATE workflow_run SET event_seq = event_seq + 1 … RETURNING event_seq` inside the writing transaction; the row lock orders commits, so a poller reading `seq > after` can never skip a late-committing event.

## 8. Limits

| Limit | Value | Error |
|---|---|---|
| Draft body | 256 KiB, JSON depth 32 | `413` / `400` |
| Other request bodies | 64 KiB | `413` |
| Start input | Validated against the release `inputSchema` | `422 VALIDATION_FAILED` with `fieldErrors` |
| Concurrent active runs per workflow | `execution.maxConcurrentRuns` of the active release | `429 QUOTA_EXCEEDED` |
| Business key | 1–100 chars, searchable, not unique unless a later workflow policy requires it | `400` |
| Per-IP/connection rate | OpenShift route annotations (S6 §6) | `429` from the router |

## 9. Polling guidance for clients

| View | Interval | Stop when |
|---|---|---|
| Run detail timeline (`listRunEvents`) | 3 s while `running`, 15 s while `waiting`/`needsAttention` | `completed`/`cancelled` |
| Task inbox counters | 30 s and on window focus | — |
| Notifications | 60 s and on window focus | — |

Every response is correct without SSE; SSE (E17, R1) will read the same `run_event` feed.

## 10. Operation index by area

| Area | Operations (operationId → permission) |
|---|---|
| Identity | `getMe` → authenticated; `getWorkspace` → workspace.member |
| Administration | `listOrganizations`, `createOrganization`, `listWorkspacesAdmin`, `createWorkspace` → platform.admin |
| Workflows | `listTemplates` → workflow.edit; `listWorkflows` → workflow.read or workflow.start; `createWorkflow`, `saveDraft` → workflow.edit; `getWorkflow`, `getDraft`, `validateDraft`, `evaluateDecision` → workflow.read |
| Releases | `publishDraft`, `setActiveRelease` → workflow.publish; `listReleases`, `getRelease` → workflow.read; `exportRelease` → workflow.export |
| Runs | `getStartForm`, `startRun` → workflow.start; `listRuns` → run.read; `getRun`, `listRunEvents` → run.read, initiator or task participant; `listMyRequests` → workspace.member; `cancelRun`, `retryRun`, `reconcileEffect`, `suspendRun`, `resumeRun`, `migrateRun`, `getRunMigrationOptions` → run.operate; `correctTaskInput` → task.act (holder) or run.operate + run.read-data; `fetchExternalJobs`, `extendExternalJobLock`, `completeExternalJob`, `failExternalJob` → job.work |
| Tasks | `listTasks` → task.act; `getTask` → eligible/assignee/decider or run.read; `claimTask` → task.act + candidate; `releaseTask` → assignee; `decideTask` → assignee + task.act; `reassignTask` → run.operate |
| Notifications | `listMyNotifications` → workspace.member; `markNotificationRead` → recipient |
| Connections | `listConnections`, `getConnection` → connection.read; `registerConnectionVersion`, `putConnectionBinding`, `testConnection` → connection.manage |
| Access | `listGroups` → workflow.read or workspace.manage-access (members only for the latter); `getAccessOverview`, `createRoleBinding`, `deleteRoleBinding`, `createPermissionGrant`, `deletePermissionGrant`, `putGroup` → workspace.manage-access |

The negative authorization suite (S9 T-SEC-01) is generated from this table via the `x-orqix-permission` extension, so adding an operation without a permission fails CI.
