# S85 - Agent write tools: approval expiry, pre-send fence and write record retention (IN04 hardening)

25 October 2026. Branch `fix/post-validation`. Builds on [S75](S75-approved-agent-write-tools.md). The backend of this slice arrived in commit `48aaf4c` without tests, frontend or a build note, and its comments cited `docs/build/S79`, which is the unrelated pinned-approval-views note. This note records the decisions and exact semantics, the review of that commit against the S75 rules, the fixes, the missing tests and UI, and the evidence. Delivery still goes only to the `LocalStubTransport`.

## Decisions

- **Expiry is optional and off by default.** `approveAgentWriteTool` accepts `expiresInDays` (1 to 90). Omitted or `null` means no expiry, exactly as before; approvals made before V120 have `expires_at` null and behave unchanged.
- **An expired approval authorizes nothing, like a revoked one.** It is not renewable in place and cannot be revoked (`409 CONFIGURATION_VERSION_CONFLICT`, "already expired"). To continue, a holder of `agent.approve-write-tool` approves the tool again.
- **Retention follows DOC08 file retention** (`AttachmentLifecycle`, [S52](S52-document-lifecycle.md)): a per-workspace number of days, set by `workspace.manage-access`, version-checked, audited, applied by an hourly sweep. It is conservative: anything not provably finished is kept.
- **The S75 revocation race is closed** by re-reading the approval under a row lock in the transaction that performs the send.

## Exact semantics

### Expiry

- `expires_at = approved_at + expiresInDays * 24 h` (absolute hours, not calendar days in any zone). `approved_at` is truncated to microseconds before computing, so the stored, returned and audited instants are identical.
- An approval is expired at instant `t` when `expires_at <= t` (the boundary instant itself is expired). Every reader uses the application `Clock`:
  - compiler facts (`activeApprovals`, used by `CompileContextFactory`): `status = 'active' AND (expires_at IS NULL OR expires_at > now)`. Publish with only an expired approval fails with `WF_AGENT_WRITE_TOOL_UNAPPROVED`, whose message now says the tool "was never approved, or its approval was revoked or has expired";
  - `authorize` (before the model, and again at the start of every `write`): an expired approval returns `WRITE_APPROVAL_EXPIRED` with no approval id (fail closed); the executor stops the step before any model call with a message naming expiry (`CONNECTION_UNAVAILABLE`);
  - the fence (below) returns `WRITE_APPROVAL_EXPIRED` when the approval expired after it was authorized.
- Storage: the row stays `active` past its expiry; the API reports it as `expired` (computed per request). When the same workflow/connection version/operation/tool is approved again, `approve` first sets `status = 'expired'` (version + 1) on the expired active row so the one-active unique index admits the new approval. CHECK constraints: `expires_at` within `(approved_at, approved_at + 2160 hours]` (V122) and `status = 'expired'` only with an `expires_at` (V120).
- Audit: `agent.write-tool.approved` carries `expiresAt` (null for none). Nothing is audited at the instant an approval expires.

### Pre-send fence

After the slot row and the journal entry exist (`inFlight`), `write` opens a new transaction, reads the authorizing approval row `FOR SHARE`, and only then calls the transport inside that transaction:

- row missing, `revoked`, or any status other than `active` -> `WRITE_NOT_APPROVED`; `expired`, or `expires_at <= now` -> `WRITE_APPROVAL_EXPIRED`. The journal entry becomes `permanentFailure` with that code, the write is audited (`denied`), the agent is told nothing was sent and the step needs attention. A later retry after a new approval may send (a `permanentFailure` slot proceeds).
- A revocation (`FOR UPDATE` on the same row) committed before the fence is seen. One that arrives during the send waits until the send transaction ends (at most `lock_timeout`, 10 s) and applies to the next write.
- If the fence itself fails (e.g. lock timeout) the transport was not called: `retryableFailure`, `WRITE_NOT_SENT`. If the transport throws after the fence passed: `unknown`, never resent (S75). If the transport returned but the transaction could not commit: treated as delivered.

### Retention

- `GET/PUT /workspaces/{ws}/agent-write-retention` (`getAgentWriteRetention`, `setAgentWriteRetention`), permission `workspace.manage-access` (404 for foreign principals, 403 for members without it). Body `retentionDays` 1 to 3,650 or `null`, `expectedVersion` (0 before the first save); mismatch `409 WORKFLOW_VERSION_CONFLICT`; audit `agent.write.retention.updated` (`retentionDays`, `version`). The response includes `eligibleNow`.
- Sweep (`AgentWriteRetention.purgeExpired`, `@Scheduled` `orqix.agent-writes.retention-delay`, default 1 h, 200 rows per run, `FOR UPDATE ... SKIP LOCKED`): deletes `agent_write_call` rows whose request is `completed` or `cancelled` with `ended_at` older than the period, and only if **every** write row of that request has a journal entry in `succeeded`, `permanentFailure` or `notApplied`. A request with any write that is `unknown`, `inFlight`, `pending`, `retryableFailure`, or never registered in the journal keeps all its rows. Each deleted row is audited `agent.write.purged` (system actor; `runId`, `writeId`, `retentionDays`).
- Never touched: `effect_journal` (including the write entries), `agent_call`, `audit_event`, approvals.

## Review of 48aaf4c against the S75 rules

| # | Finding | Severity | Resolution |
|---|---|---|---|
| R1 | V120's upper bound `expires_at <= approved_at + interval '90 days'` depends on the session time zone. pgjdbc sends the JVM zone (this machine: America/New_York); in such a zone a 90-day window that crosses the spring clock change is 2159 hours, while the service stores `approved_at + Duration.ofDays(90)` (2160 hours), so approving for 90 days in that window failed with a CHECK violation (HTTP 500). Reproduced: `theNinetyDayBoundDoesNotDependOnTheSessionTimeZone` fails without V122 (`evidence/s85/without-v122.log`). | Bug | **Fixed**: `V122__agent_write_expiry_absolute_bound.sql` (bound `interval '2160 hours'`). |
| R2 | `approve` audited `expiresAt` with nanoseconds while PostgreSQL stores (rounded) microseconds, so the audited instant could differ from the stored and enforced one. | Minor bug | **Fixed**: `approved_at` truncated to microseconds before computing the expiry; the test asserts audit == response. |
| R3 | The publish blocker for an expired approval said the tool was one "which nobody has approved", hiding the reason. | Gap | **Fixed**: `WF_AGENT_WRITE_TOOL_UNAPPROVED` message names revocation and expiry. Code unchanged. |
| R4 | No tests at all for expiry, fence or retention; `SecurityMatrixTest` only added a sample body. | Gap | **Fixed**: four new real-stack tests (below); matrix approve body now includes `expiresInDays`. |
| R5 | Comments, migrations and the contract cited `docs/build/S79`. | Doc | **Fixed** to S85 (AuditWriter, AgentWriteService, AgentWriteRetention, V120, V121, openapi.yaml; `schema.d.ts` regenerated). Changing V120/V121 comments changes their Flyway checksums: a database that applied them from `48aaf4c` (pushed on `origin/feature/r0-ai-import-wf-theme`) needs `flyway repair`. Same practice as `7526a1e`. |
| R6 | No frontend for expiry, expired state or retention. | Gap | **Fixed** (below). |

Checked and found correct (no change):

- Fail closed at every gate: compile facts, `authorize` (expired returns no approval id), executor pre-model gate, per-write `authorize`, fence. An expired, revoked or missing approval authorizes nothing; an enabled adapter or declared tool still authorizes nothing.
- Separation of duties unchanged: the approver is fixed per approval row; `authorize` still refuses the approver's own request; `agent_write_call_separation` unchanged.
- No argument text stored: new audit metadata is an instant, ids and numbers; the whitelist in `AuditWriter` restricts keys.
- Retention never deletes records of running requests, nor any record of an ended request that has an unknown/unreconciled, non-final or unregistered write; it is audited, permission-guarded, version-checked, and configurable through the API.
- Revoking an expired approval is refused rather than turning it into `revoked` (the history keeps "expired").

Remaining limits (not changed): an active row past expiry stays `active` in storage until re-approval, so any new reader must apply the expiry predicate (a bare `status = 'active'` query would fail open); expiry uses each pod's clock; no audit, notification or reminder at expiry; the sending transaction holds a connection and a share lock for the duration of the send (fine for the stub, must be bounded for a live transport); retention drains 200 rows per hourly run; `expectedVersion` is a primitive (an omitted value is 0, as in DOC08), which matters only before the first save.

## Frontend

`AgentWriteToolsPage` (**Agent writes**):

- Approve dialog: optional **Expires after (days, optional)** (1 to 90, empty = no end date, help text linked by `aria-describedby`); `expiresInDays` is sent only when given.
- Approvals: an active approval shows "expires <date>" or "no end date"; the latest expired approval of a workflow without an active successor shows an **Expired** badge (text, not colour) with its date and no **Revoke**.
- Validation in the approve, revoke and reconcile dialogs: every message is `role="alert"`, has an id, and its field carries `aria-invalid` and `aria-describedby` (the reconcile outcome uses a labelled `radiogroup`).
- **Settings → Agent write retention** (`AgentWriteRetentionPanel`, tab only with `workspace.manage-access`, next to **File retention** and built the same way as the DOC08 `AttachmentRetentionPanel`): days 1 to 3,650 or empty, eligible count, version conflict message and reload. It lives in Settings rather than on **Agent writes** because workspace administrators hold `workspace.manage-access` but not `run.operate`, so the **Agent writes** navigation entry is not shown to them.
- Mock mode: `mocks/agentWrites.ts` keeps an in-memory retention setting.

## API, schema and migrations

Operations `getAgentWriteRetention`, `setAgentWriteRetention`; schemas `AgentWriteRetention`, `SetAgentWriteRetention`; `AgentWriteToolApproval.expiresAt` and status `expired`; `AgentWriteToolApproveRequest.expiresInDays`; audit actions `agent.write.retention.updated`, `agent.write.purged`. Migrations `V120__agent_write_approval_expiry.sql`, `V121__agent_write_retention.sql` (from `48aaf4c`), `V122__agent_write_expiry_absolute_bound.sql` (this note). V115-V119 unused here.

## Evidence

All runs in this worktree (JDK 21, Testcontainers PostgreSQL/Flowable, Docker up; JVM time zone America/New_York). Logs in `evidence/s85/` (git-ignored). Backend and frontend were run one after the other, never concurrently.

- Bug R1 reproduced before the fix: `AgentWriteToolsTest#theNinetyDayBoundDoesNotDependOnTheSessionTimeZone` with V122 removed fails with `violates check constraint "agent_write_tool_approval_expiry"` (`without-v122.log`); with V122 it passes.
- Targeted run (`targeted-2.log`): `AgentWriteToolsTest` 8/8 (4 existing, 4 new), `SecurityMatrixTest` 4/4, `AgentExecutorTest` 29/29, compiler `AgentWriteToolRulesTest` 4/4.
- New real-stack tests in `AgentWriteToolsTest`:
  - `anApprovalMayExpireWithinNinetyDaysAndAnExpiredApprovalAuthorizesNothingUntilApprovedAgain`: 0 and 91 days refused with nothing stored; omitted and explicit `null` mean no expiry; 1 and 90 days are exactly 24 h and 2160 h after the approval and the audit carries the same instant; publish succeeds while valid; once expired the list shows `expired`, revoke is `409` naming expiry, a new request needs attention (`CONNECTION_UNAVAILABLE`, message names expiry) with no model call and nothing sent; publish of a workflow whose only approval expired is refused (`WF_AGENT_WRITE_TOOL_UNAPPROVED`, message names expiry); approving again stores the old row as `expired` (version 2), and an operator retry then delivers exactly once.
  - `theExpiryInstantIsExactAndTheFenceStopsTheSendAfterARevocationOrExpiryThatFollowsAuthorization` (production `AgentWriteService` on a `MutableClock`, journal hook after the slot is registered): 1 µs before expiry authorizes and compiler facts include it; at the instant `WRITE_APPROVAL_EXPIRED`, facts exclude it, and a write registers nothing and sends nothing; a revocation committed after authorization and before the send is caught by the fence (`WRITE_NOT_APPROVED`, journal `permanentFailure`, audit `denied`, nothing sent); an expiry reached between authorization and send is caught likewise (`WRITE_APPROVAL_EXPIRED`).
  - `theNinetyDayBoundDoesNotDependOnTheSessionTimeZone`: in an America/New_York session `'90 days'` is shorter than 2160 hours across the spring change; a 2160-hour window is accepted, one second more is refused.
  - `retentionIsAdministratorOnlyVersionedAndRemovesOnlyFinalWriteRecordsOfLongEndedRequests`: designers, operators and requesters get 403 on GET and PUT; 0 and 3651 refused; save with version, `eligibleNow` 1, audit `agent.write.retention.updated`, stale version 409; the sweep removes only the delivered write of the request ended 40 days ago and keeps the unknown-outcome, running, recently ended and never-registered requests' records; the journal of the purged request is unchanged and still `succeeded`; `agent.write.purged` audited by the system with run id and days; after the unknown write is reconciled its record is removed; clearing the setting stops removal even for a request ended 400 days ago.
- `SecurityMatrixTest`: foreign principals get 404 and change nothing for every operation including `getAgentWriteRetention`, `setAgentWriteRetention` and `approveAgentWriteTool` with `expiresInDays`; the snapshot includes `workspace_agent_write_policy`, `agent_write_tool_approval`, `agent_write_call`.
- Full `backend/mvnw.cmd -f backend/pom.xml clean verify` (`backend-verify.log`): **BUILD SUCCESS**, exit 0. Compiler module 524 tests, 0 failures; application module 897 tests, 0 failures, 0 errors, 4 skipped (the existing intentional skips); coverage gate and packaging ran.
- Frontend (`frontend-full.log`, after the backend run): `vitest run` **967 passed in 135 files**; `tsc -b --noEmit`, `eslint .` and `api:check` clean (`frontend-static.log`). New/changed: `AgentWriteToolsPage.test.tsx` (4: alerts and `aria-invalid`/`aria-describedby` in the approve, revoke and reconcile dialogs; optional expiry 0/91/2.5/text refused, 7 sent as `expiresInDays`; expiry date and **Expired** state; expired approval with an active successor not shown), `AgentWriteRetentionPanel.test.tsx` (2).
- `tools/contract-check/contract_check.py`: PASS, 88 checks (`contract-check.log`).
- Not run: browser journeys (mock or real), Docker stack, image refresh, any live transport.

## Not included (exclusions)

- Renewing or extending an approval in place, reminders or notifications before/at expiry, an audit event at the expiry instant, expiry per release.
- Retention of `agent_call`, effect journal or audit events; legal hold for write records; a purge-now action.
- Live transports, browser journeys, Docker stack runs and image refresh.
