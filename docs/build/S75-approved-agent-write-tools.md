# S75 - Approved write-capable agent tools (IN04)

18 October 2026. A bounded, real slice of IN04 on top of the S13 agent loop, the IN02/S73 adapters and the effect journal. An AI agent step may send a message through a **write tool** only when a person holding a grant-only permission has approved exactly that tool for that workflow and connection version, and never for a request that person started. Every write is its own effect-journal entry with a stable key and a recorded outcome; an unknown outcome is never sent again automatically, and an operator records what they found at the target. All delivery stays on the `LocalStubTransport`: nothing leaves the process.

## Declaring a write tool (authorizes nothing)

`contracts/connection-definition-v0.schema.json`, `agentSpec.writeTools` (1-4 per agent operation):

```json
{ "name": "notify_procurement", "label": "Tell procurement",
  "description": "Emails the procurement team a short note about a high-risk vendor.",
  "adapter": "procurement-mail", "kind": "email", "to": ["procurement@example.com"], "maxCalls": 1 }
```

- `kind` is `email` (requires fixed `to`, 1-10 addresses) or `teams` (no `to`: the adapter's channel). Recipients are part of the immutable, hashed connection version; they are never arguments, never request data and never model output (rule 4).
- The tool "version" is the connection version and its definition hash. A changed tool is a new connection version and needs a new approval.
- Registration refuses a write tool whose name equals a read tool name (`uniqueItems` field error). The agent operation's own `effectClass` stays `readOnly` (the agent task is repeatable); each write is journaled separately as `nonIdempotent`.
- Connection tests (`POST .../connections/{key}/test`) never offer or run a write tool: there is no run, so there is no scope (fail closed).

## Approval, permissions and separation of duties

- New permission **`agent.approve-write-tool`**: grant-only and workspace-wide like `workflow.approve-promotion` (no role carries it; V110 extends both `permission_grant` CHECK constraints). Granted through the existing access API; the Access panel explains it.
- `approveAgentWriteTool` (fresh grant check, reason required, `422 REASON_REQUIRED`) stores an `active` row in `agent_write_tool_approval` for exactly *workflow key + connection key + connection version (+ definition hash) + operation + tool name*. One active approval per combination (`409 DUPLICATE_KEY`). The workflow key, connection version, agent operation and declared tool must exist.
- `revokeAgentWriteTool`: holders of the grant **or `connection.manage`** (revoking only removes authority), reason required, `expectedVersion` checked (`409 CONFIGURATION_VERSION_CONFLICT`).
- **Separation of duties - decided: always on, no opt-out.** The approver of a tool never triggers a write through it: a run whose initiator is the approver is refused before the model is asked and again before each write (`WRITE_SELF_APPROVAL`), and `agent_write_call_separation` (CHECK `approved_by <> requested_by`) repeats it in the database. Another person's request through the same approval may write. Test workspaces' self-approval setting does not relax this.
- Listing (`listAgentWriteTools`): `connection.read`, `run.operate` or the grant. Write list (`listAgentWriteCalls`): `run.operate` or the grant. Reconciliation: `run.operate` (role-appropriate, same as step reconciliation).
- Adapter availability or an enabled connection never implies authorization (rule 1): with a declared but unapproved tool, publishing fails and the runtime refuses.

## Compiler

`AgentWriteToolRules` (compiler, new `CompileContext.writeToolApprovals`, filled by `CompileContextFactory` from active approvals; contexts built without it contain none, so they fail closed). For every "Call a system" step whose agent operation declares write tools:

- `WF_AGENT_WRITE_TOOL_UNAPPROVED` - no active approval for this workflow key, connection version, operation and tool (an approval for another workflow, version or tool does not count).
- `WF_AGENT_WRITE_TOOL_ADAPTER` - adapter missing, of another kind, or disabled.
- `WF_AGENT_WRITE_TOOL_RECIPIENT` - a fixed recipient's domain is not on the adapter allowlist, or too many recipients.
- `WF_AGENT_WRITE_TOOL_INVALID` - a write tool shares a read tool's name; `WF_AGENT_WRITE_TOOL_PLACEMENT` - automatic undo steps may not use agents with write tools.

Publishing pins nothing about the approval: the runtime checks it again. A revocation after publish makes the agent step need attention (`CONNECTION_UNAVAILABLE`, `WRITE_NOT_APPROVED`) **before** the model is asked - a write the workflow relies on is never skipped silently.

## Runtime

`AgentExecutor` (S13 loop) with the new port `AgentWriteTools`, implemented by `integration.agentwrite.AgentWriteService`:

1. Before the model: every declared write tool must be authorized for the run (active approval for the run's workflow key, same definition hash, initiator is not the approver). Otherwise the step fails permanently without a model call.
2. The model is offered each write tool with a **closed JSON Schema** (`WriteToolArguments`): email `subject` (one line, <=200) and `body` (<=4000); Teams optional `title` (one line, <=200) and `text` (<=4000); string members only, `additionalProperties: false`. A fixed rule is appended to the system prompt. Raw argument text is capped at 5000 characters.
3. A call with unknown members (e.g. `to`), wrong types, line breaks in a single-line member, control characters or over-long text is refused back to the model with member names and rules only (never values), and nothing is journaled. `maxCalls` per tool and the operation's `maxToolCalls` bound the uses.
4. A valid call is re-authorized, then journaled: an `agent_write_call` row (approver, requester, SHA-256 digest and character count of the arguments - never the arguments) is written first in its own transaction, then the effect is registered with key `sha256(workspace|run|<node>:write:<tool>:<n>|occurrence)` (node id `<node>:write:<tool>:<n>`, operation `agentWrite.email|teams`, `connection_key` = adapter key, adapter version, class `nonIdempotent`). The adapter is read again and its current allowlist applied (`AdapterRules.validateSend`); a refusal is a `permanentFailure` (nothing sent). Delivery goes to `LocalStubTransport`; the journal keeps only the stub's `{outcome, detail}`.
5. Outcomes for the agent: delivered -> "accepted, do not send again"; a slot that already **succeeded** (e.g. after an automatic or operator retry) is **reused**, never resent; a slot left `inFlight` with an expired lease, or a transport exception, becomes **`unknown`**, the agent stops at once (`WRITE_OUTCOME_UNKNOWN`, attention `EFFECT_UNKNOWN`) and the existing run retry is refused (`EFFECT_NOT_RECONCILED`) until it is reconciled; a refusal stops the step (`CONNECTION_UNAVAILABLE`).
6. Slot `n` is the n-th accepted use of the tool in one execution, so a rerun maps the model's first write to the same slot: if it was delivered it is reused even when the model would phrase it differently (no duplicate). Text may change for a slot only while nothing can have been delivered for it (pending, retryable/permanent failure, or reconciled as not applied: `EffectJournal.replaceUnappliedRequest`).
7. Audit (system actor, ids/names/sizes only): `agent.write` with outcome (`succeeded`, `reused`, `unknown`, `unknownUnreconciled`, `refusedByAdapter`, `WRITE_NOT_APPROVED`, ...). The run timeline's agent summary lists the write tool label; `agent_call.tool_calls` records `written`/`reused`/`unknown`/`refused`/`invalidArguments`/`refusedLimit`.

## Reconciliation of unknown outcomes (rule 3)

`reconcileAgentWrite` (`POST /workspaces/{ws}/agent-writes/{writeId}/reconcile`, fresh `run.operate`): `outcome` `succeeded` (it was delivered; reused, never resent) or `notApplied` (it was not; an explicit operator retry of the request may send it again), a required reason (`REASON_REQUIRED`), optional remote reference, and `expectedVersion` of the write row (`CONFIGURATION_VERSION_CONFLICT`). Only an `unknown` write (`409 EFFECT_STATE_CONFLICT` otherwise). It records the journal resolution (`confirmedApplied`/`confirmedNotApplied`, by, at, note), the row's reconciled fields and audit `agent.write.reconciled`. It never resumes the request: retrying stays a separate operator action. The generic step reconcile (`reconcileEffect`) refuses agent writes (`EFFECT_STATE_CONFLICT`), because a write has no step result to confirm. Nothing is ever inferred.

## Operator UI

New page **Agent writes** (`/w/:ws/agent-writes`; nav for `agent.approve-write-tool` or `run.operate`): declared write tools with fixed targets and active approvals; "Approve for a workflow" (workflow key + reason) for grant holders; "Revoke" (reason, version) for grant holders and connection managers; a list of writes (only unknown outcomes by default) with "Record outcome" (delivered / not delivered, what was checked, optional reference, version) for operators. No message text is shown (Orqix does not keep it). The run detail technical table links unknown agent writes to this page instead of offering the step Reconcile dialog; retry/reconcile dialogs ignore agent writes. A minimal empty mock double serves mock mode.

## API, schema and migrations

Operations `listAgentWriteTools`, `approveAgentWriteTool`, `revokeAgentWriteTool`, `listAgentWriteCalls`, `reconcileAgentWrite` (tag "Agent write tools"); permission value `agent.approve-write-tool`; audit actions `agent.write-tool.approved`, `agent.write-tool.revoked`, `agent.write`, `agent.write.reconciled`. No new problem code. Migrations `V110__agent_write_tool_approvals.sql` (permission constraints, approvals) and `V111__agent_write_calls.sql` (write slots). V112-V114 unused.

Merge note: V110 recreates the `permission_grant` CHECK constraints with every grantable permission (as V99 did); a concurrent migration changing that list must be reconciled.

## Evidence - 18 October 2026

All runs in this worktree (JDK 21, Testcontainers PostgreSQL/Flowable, Docker up). Logs in `evidence/s75/` (git-ignored).

- Full `backend/mvnw.cmd -f backend/pom.xml clean verify` (`backend-verify.log`): **BUILD FAILURE**. Compiler module 515 tests, all pass. Application module 868 tests: 867 pass, 4 skipped (the same intentional skips), **1 failure**: `SeparationOfDutiesTest.thePersonWhoDecidedTheFirstStepCannotClaimDecideOrSeeTheSecond` (line 59). Its assertion reads only the first 100 claimable tasks of `max.manager`; in the full run the shared database held more than 100 open manager tasks (the response carries a `nextCursor`, 184 `manager` items across the captured output, none from this slice's vendor/agent-write workflows), so the new task was on page 2. The class passes alone together with `AgentWriteToolsTest` (`sod-rerun.log`). Recorded as a pre-existing, accumulation-dependent test assumption, not as fixed; the coverage gate and packaging did not run because of it. `ArchitectureTest` (6) and `SecurityMatrixTest` (3, covering the five new operations) pass in the full run.
- New backend cases: `AgentWriteToolRulesTest` (compiler, 4: approved tool compiles, unapproved / other workflow / other version / other tool refused, adapter missing/disabled/wrong kind, unapproved recipient domain, Teams with recipients and email without recipients are malformed definitions, name shared with a read tool); `AgentExecutorTest` +5 (closed schema offered and text reaching only the write port, connection test never offers or uses a write tool, unapproved/self-approved tool stops before the model, recipient member/over-long/wrong-type arguments never reach the port and the refusal names no value, an unknown outcome ends the execution without another model turn); `AgentWriteToolsTest` (4, real stack: publish refused until approved, approval needs the grant and a reason, duplicate approval 409, exactly one stub delivery whose digest equals fixed recipient + model text, write entry `agentWrite.email`/`nonIdempotent`/`succeeded`, approver and requester recorded, the canary text absent from `effect_journal`, `agent_write_call`, `agent_call`, `audit_event`, `run_event` and the captured logs; recipient injection, line break, over-long body and a second use refused with one delivery; revocation with reason/version checks stops the next run before the model; the approver's own request refused, another person's request through the same approval delivered, DB CHECK `agent_write_call_separation`; a crashed in-flight write becomes `unknown` on operator retry and is not resent, run retry `EFFECT_NOT_RECONCILED`, generic step reconcile `EFFECT_STATE_CONFLICT`, dedicated reconcile refuses requester/stale version/blank reason, records `succeeded` once, audit written, and the next retry reuses the write and continues).
- Frontend: full `vitest run` **931 passed in 125 files** (`frontend-full.log`, run after the backend build, not concurrently); `tsc -b --noEmit`, `eslint .` and `api:check` clean (`frontend-static.log`). New: `AgentWriteToolsPage.test.tsx` (3), `operatorText.test.ts` (1); `Shell.test.tsx` operator navigation now lists "Agent writes".
- `tools/contract-check/contract_check.py`: PASS, 88 checks (permission vocabulary, audit whitelist, schema.d.ts, LF line endings).
- Not run: browser journeys (mock or real), Docker stack, image refresh, any live transport.
- Follow-up, same day: the one failure above was a test fragility, not a product defect. `SeparationOfDutiesTest` read only the first 100 claimable tasks, and the shared test database held more open manager tasks than that. The test now walks every page (`claimableQueueContains`). After that fix a full `clean verify` of this branch exited 0, including the coverage gate and packaging. Frontend was not re-run (no frontend change since the 931-test run).

## Not included (exclusions)

- Any live transport, Kafka/JDBC/HTTP/MCP write tools, Teams cards, attachments, CC/BCC, or recipients from request data or model output.
- Approval workflow around approvals (request/second approver/quorum), expiry (added later, [S85](S85-agent-write-expiry-and-retention.md)), notifications to approvers, bulk approval; approval pinned to one workflow release (it is bound to the workflow key, see open questions).
- Interrupting an agent step mid-write, compensation (undo) of agent writes, retention/purge of `agent_write_call`, rate limits beyond `maxCalls`/`maxToolCalls`. (Retention was added later: [S85](S85-agent-write-expiry-and-retention.md).)
- Real mock-mode data for the page (the mock double is empty), browser journeys, Docker stack runs and image refresh.
- A small race remains between the final approval check and the stub send (milliseconds); a revocation in that window lets that one write through. Live transports would need the check and the send under one fence. (Closed in [S85](S85-agent-write-expiry-and-retention.md): the approval is re-read `FOR SHARE` in the transaction that sends.)

## Open questions for the user

1. **Approval scope.** Approvals bind to the workflow *key* (every release of it that pins the approved connection version), not to one release. Should a new workflow release require re-approval?
2. **Separation of duties breadth.** Only "approver is not the run initiator" is enforced. Should the approver also differ from the author of the connection version, the workflow publisher, or the operator who reconciles? Should test workspaces (self-approval allowed) relax it?
3. **Revocation by `connection.manage`.** Decided yes (it only removes authority); confirm.
4. **Missing approval behavior.** A revoked or missing approval fails the agent step visibly instead of running the agent without the tool. Confirm this is preferred over a silent read-only run.
5. **Who reconciles.** `run.operate` (as for HTTP effects). Should reconciling an agent write need a different person from the requester or approver?
