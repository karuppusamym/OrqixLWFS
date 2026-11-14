# S135: Agent tools, durable agent loops, trace and agent tests

5 November 2026. Wave B of the Camunda gap plan ([doc 29](../29-camunda-gap-implementation-plan.md) §8): **CG-M01** tool registry and pinning, **CG-M02** Orqix operations, decisions, workflows and "ask a person" as tools, **CG-M03** durable agent loop and human in the loop, **CG-M05** agent trace and control plane, **CG-M07** agent testing, and the **§8.8 tool security checklist** as tests. Builds on [S13](S13-agents-mcp.md) (agents and MCP), [S75](S75-approved-agent-write-tools.md) (approved write tools) and [S76](S76-native-async-agent-protocols.md) (native async gateway, unchanged). Migrations **V270–V275**. OpenAPI: one block tagged **AgentTools**. Connection definitions stay on `schemaVersion "0.1"`; an S13 agent without the new fields behaves exactly as before.

## 1. Decisions taken (doc 29 defaults where it gave one)

1. **A tool server is a connection of kind `mcpServer`** (`connection-definition-v0.schema.json`): its binding is the MCP endpoint, its definition is the tool catalog (`mcpServer.tools`: name, label, **administrator-written description** (required), data classes) and allow-listed **resources**. It has no operations, so no workflow can call it directly.
2. **The pin is a workspace snapshot, not part of the portable definition.** Bindings, and so the server behind a key, are workspace facts. `POST .../connections/{key}/tool-snapshot` (fresh `connection.manage`) lists the bound server's tools and stores, per catalog tool, the `inputSchema` and the SHA-256 of its canonical JSON (`mcp_tool_snapshot`, V270). Agents use the **latest definition version** of a server and its pins; a definition change is an audited administrator action like a binding change.
3. **Namespacing:** agents name server tools `server.tool`. The model sees `server__tool` (OpenAI-style function names allow no dot); registration refuses two tools with the same model name (`AGENT_TOOL_NAME`), so a second server never shadows a tool. Tools without a dot still run on the agent's own binding as in S13 (no pin, S13 behavior).
4. **Durable turns reuse the engine's own job.** No compiler change and no new step: a `Call a system` step on an operation with `agent.loop` runs `AgentLoops.turn` instead of the dispatcher. One model turn (and the tool calls it asked for) runs per execution of the step's async job; the job then **defers itself** with the S131 deferral (no attempt used), at once for the next turn or until the loop deadline while it waits. An answer, an approval or the child's end moves the timer job to due now (`EngineCommands.wakeTimer`); the scheduler repeats the wake for a wait that ended while a turn was running. The job transaction is rolled back by the deferral, so every loop write uses its own transaction, and child requests are started by the scheduler outside the job (the engine must not start a process inside a job's command).
5. **Turn state is encrypted and short-lived.** The conversation between turns lives only in `agent_loop.state_cipher`: AES-GCM with the mounted key ring (`oauth-token-keyring`, `OAuthVault`), AAD bound to workspace, run and loop. It is cleared when the loop ends and at the latest one day after the deadline; questions, proposed arguments and answers of human requests are encrypted the same way and cleared when the agent moves on. Without the key ring a durable agent fails visibly; nothing is stored in plain text.
6. **Human requests are their own small inbox, not Tasklist tasks.** "Ask a person", "approval before a tool" and MCP elicitation create an `agent_human_request` for a group; members (`task.act` plus group membership) see the details and answer on the **Agents** page. An approval is never decided by the person whose request the agent works on (403 `SEPARATION_OF_DUTIES`, CHECK `agent_human_request_separation`). Reusing the BPMN task model would have needed a generated user task per tool call.
7. **Elicitation:** the client declares the capability only for a durable agent with `agent.elicitation.group`. The request is answered `cancel` at once (the server never waits on a person over an open stream), becomes a task with the server's flat form, and the person's answer goes back to the model with the original tool result so it can call the tool again. Every other agent declines elicitation; **sampling is always refused** (JSON-RPC error), as is every other server request except `ping`.
8. **Operations as tools** inherit the effect journal: each call is dispatched by `EffectDispatcher` with node id `<step>:op:<tool>:<call index>:<argument digest>` and the step occurrence, so the key derives from run, step, occurrence, call index and argument digest. `readOnly` and the idempotent classes run freely; `nonIdempotent` needs `write: true` and runs only under an active S75 approval of that tool name (same grant, scope and separation of duties). An unknown outcome stops the agent (`TOOL_OUTCOME_UNKNOWN`, attention `EFFECT_UNKNOWN`). Connection tests offer no operation tools (no run, no journal).
9. **Decision tools** are pinned versions of shared decision tables (`source.decision.key` + `version`), evaluated with the canonical semantics; arguments are the fields the conditions read.
10. **Workflow tools** pin a release number (`source.workflow.key` + `release`); the child's input must satisfy its request form; depth and quota rules are those of child steps. The model receives the child's outcome, reference and output.
11. **Agents that use only registered servers need no binding of their own** (dispatcher, connection test and publish checks).
12. **The trace is opt-in per workspace and redacted** (`agent_trace_setting`, `agent_trace_event`, V273): off by default (metadata only, S13); on, inputs, turns and tool calls are kept for 1–30 days (default 7) after built-in redaction (e-mail addresses, runs of 12–19 digits, bearer/API/JWT tokens), the named JSON fields and workspace regular expressions (nested repetition refused). Changing it needs `workspace.manage-access`; reading a step's trace needs `run.operate`.
13. **The judge model is pinned by profile and exact id.** A suite names `judge.profile` and `judge.model`; when the AI policy resolves the profile to another model, the judge assertion fails (`JUDGE_MODEL_CHANGED`). The provider and policy classes are untouched (called through `AgentModelPort`).
14. **No new permission.** Pins, switches and suites: `connection.manage`; trace setting: `workspace.manage-access`; trace and dashboard: `run.operate` (dashboard also `connection.read`); human requests: `task.act` and group membership. This keeps the grant constraints out of this change.

## 2. What an administrator declares

```json
{ "kind": "mcpServer", "key": "vendor-registry", "mcpServer": {
    "tools": [{ "name": "vendor_risk_lookup", "label": "Vendor registry", "description": "Looks a vendor up ..." }],
    "resources": [{ "name": "policy", "label": "Procurement policy", "uri": "orqix://policy/procurement", "maxChars": 2000 }] } }
```

An agent operation (`agent`) gains:

| Field | Meaning |
|---|---|
| `tools[].name` `server.tool` | A pinned tool of a registered server (description from the server definition) |
| `tools[].source` | `operation {connection, operation}`, `decision {key, version}`, `workflow {key, release}`, `askPerson {group, answerSchema}` |
| `tools[].requiresApproval {group}` | The loop pauses before every call; an approval releases it (needs `loop`) |
| `tools[].write` | Operation tool with a `nonIdempotent` operation, under S75 approval |
| `tools[].dataClasses` | Classes the tool may receive (default `public`, `internal`) |
| `loop {maxTurns ≤ 30, maxToolCalls ≤ 50, maxDuration PT1M–P7D, budgetUsd}` | Makes the agent durable (CG-M03) |
| `resources ["server.name"]` | Read-only reference data, given to the model as untrusted text, capped |
| `elicitation {group}` | MCP elicitation becomes a task for this group (needs `loop`) |
| `inputDataClasses {field: class}` | Class of request fields (default `internal`) |

Registration rules (field errors with the code as keyword): `MCP_SERVER_TOOLS`, `AGENT_TOOL_SERVER`, `AGENT_RESOURCE`, `AGENT_TOOL_NAME`, `AGENT_TOOL_SOURCE`, `AGENT_LOOP_REQUIRED`, `AGENT_LOOP_LIMITS`, `AGENT_GROUP`, `AGENT_DATA_CLASS` (`AgentToolRules`). Examples: `examples/connections/vendor-registry.mcp-server.json`, `examples/connections/vendor-due-diligence.connection.json`.

## 3. Runtime

| Situation | Behavior |
|---|---|
| Tool without a pin, missing on the server, or with another schema hash | Not offered (`refusedNotPinned`, `refusedSchemaChanged` in `agent_call.tool_calls`); drift row (`mcp_tool_drift`), audit `mcp.tool-schema-changed` (hashes, no schema), one notification `agentToolChanged` to the person who pinned. Nothing offered at all: `TOOLS_UNAVAILABLE` before the model is asked |
| `notifications/tools/list_changed` on any response stream | The server's tools are listed and compared again before the next call; a changed tool is withdrawn for the rest of the execution |
| Server request on a response stream | Answered while the stream is open: sampling and unknown methods refused, `ping` answered, elicitation declined or (durable, allowed) cancelled and handed to a person |
| Argument carrying a request value of a class the tool may not receive | Refused back to the model (`refusedDataClass`), never sent |
| Kill switch on the agent | `AGENT_STOPPED` before the model, also at a durable loop's next turn (`CONNECTION_UNAVAILABLE`) |
| Kill switch on a server | Its tools are not offered and calls are refused (`refusedKillSwitch`) |
| Durable limits | `maxTurns` (+1 repair) `TOO_MANY_TURNS`; `maxToolCalls` refuses further calls; `budgetUsd` `AGENT_BUDGET_REACHED`; `maxDuration` `AGENT_DEADLINE` (open human requests expire) |
| Retryable failure in a turn | The journal entry records the attempt; the stored state is kept, so the retry repeats only that turn |
| Operator retry after a permanent failure | The loop starts again with a fresh state and deadline |
| Request ends while a loop waits | The sweep stops the loop, cancels its human requests and clears the state |

Timeline: one `EFFECT_SUCCEEDED` with the agent summary as in S13; `EFFECT_DEFERRED` with reason `AGENT_WAITING` when a loop waits (no entry per turn); `CHILD_STARTED`/`CHILD_ENDED` for workflow tools (`agentTool: true`). `agent_call` keeps one row per loop with the totals; `ai_generation` meters every turn; audit `agent.call` as in S13.

## 4. API, schema and migrations

Operations (tag AgentTools): `getMcpToolSnapshot`, `takeMcpToolSnapshot`, `listAgentKillSwitches`, `setAgentKillSwitch`, `getAgentTraceSettings`, `setAgentTraceSettings`, `getAgentTrace`, `getAgentTestSuite`, `saveAgentTestSuite`, `runAgentTestSuite`, `listAgentRequests`, `answerAgentRequest`, `getAgentDashboard`. Audit actions: `mcp.tool-snapshot.taken`, `mcp.tool-schema-changed`, `agent.kill-switch.changed`, `agent.trace.settings-changed`, `agent.request.answered`, `agent.test-suite.saved`, `agent.test-suite.ran`. Notification kind `agentToolChanged`. No new problem code or permission.

| Migration | Content |
|---|---|
| V270 | `mcp_tool_snapshot`, `mcp_tool_drift` |
| V271 | `agent_kill_switch` |
| V272 | `agent_loop`, `agent_human_request` (separation-of-duties CHECK) |
| V273 | `agent_trace_setting`, `agent_trace_event`, index `agent_call_ws_created` |
| V274 | `agent_test_suite` |
| V275 | `notification` kind CHECK gains `agentToolChanged` (recreated with every kind, as V177 did) |

Code: `integration` — `McpClient` (protocol 2025-11-25 first, capabilities, stream watcher, `resources/read`), `AgentExecutor` (tool registry over own binding, servers, operations, decisions, waits and S75 writes; `execute`, `turn`, `test`), `agenttools.*` (`McpServers`, `McpToolPins`, `AgentKillSwitches`, `AgentTrace`, `AgentDecisionTools`, `AgentToolRules`, `AgentTests`, `AgentDashboard`, `AgentTraceRetention`), `AgentWaitTools` port, `McpToolDriftDetected` event; `runtime` — `AgentLoops`, `AgentLoopScheduler`, `AgentHumanRequests`, `AgentWaitToolsService`, `AgentToolNotifications`, hooks in `HttpTaskDelegate`, `ChildRuns` and `FailedJobRecorder`. Frontend: **Agents** page (`/w/:ws/agents`: overview, waiting for people, tool servers and switches, conversation trace, tests), notification link, mock double.

## 5. Security checklist (doc 29 §8.8) and where it is tested

| Threat | Control | Test |
|---|---|---|
| Tool poisoning | Server tools carry the administrator's description only | `AgentToolServersTest.aServerToolIsOffered…` |
| Rug pull | Schema hash pins, `list_changed` re-check | `…aChangedSchemaIsRefused…`, `…listChangedMakes…` |
| Shadowing | `server.tool` namespacing, unique model names | `…twoServersWithTheSameToolName…`, registration rules |
| Prompt injection | Tool output and resources as untrusted data, schema-valid result only (S13) | `…anAllowListedResource…`, S13 `AgentExecutorTest` |
| Confused deputy / token passthrough | Per-binding credentials read at connect; no Orqix token on MCP requests (S13) | S13 `AgentFlowsTest` (credential), `McpServers` |
| Exfiltration through arguments | Data classes per tool | `…aToolNeverReceivesRequestData…` |
| SSRF through schemas | External `$ref` refused (S13) | S13 `AgentExecutorTest` |
| Runaway cost or loops | Turn, call, time and budget limits; kill switches | `AgentLoopTest.theTurnLimit…`, `…theKillSwitch…`, `AgentToolServersTest.killSwitches…` |
| Unapproved writes | S75 approvals for write tools and `nonIdempotent` operation tools; approval before tool with separation of duties | `AgentLoopTest.anApprovalReleases…`, S75 `AgentWriteToolsTest` |
| Cross-tenant access | Pins, switches, loops, requests, trace and suites are workspace rows; foreign principals get 404 | `SecurityMatrixTest`, trace and dashboard 404 assertions |

Sampling refusal and elicitation decline: `AgentToolServersTest.samplingIsRefused…`.

## 6. Verification

See section 8.

## 7. Not in this slice

- MCP prompts, resource templates and subscriptions; tasks-based async MCP calls stay in the S76 gateway.
- A compiler warning for server tools without a pin (the runtime refuses them; publishing does not check pins).
- "Waiting for" projection (CG-W08) rows for agent waits, Tasklist integration and e-mail notifications for agent questions (they appear on the Agents page and in the timeline).
- AI-task agreement with people on the dashboard (`aiTaskAgreement` stays null until CG-A03 records decisions).
- Test suites shared with AI skills (CG-A02), browser journeys and Docker-stack runs.
- A Flowable job per turn is reused through deferral; Flowable logs each deferral at ERROR level in one line (as for S131 deferrals).

## 8. Evidence - 5 and 6 November 2026

All runs in this worktree (JDK 21, Testcontainers PostgreSQL and Flowable, WireMock model and MCP stub, no live keys).

- Full `backend/mvnw -o clean verify`: compiler **884/884** pass. Application **1,259 run, 2 failed, 5 skipped**:
  - `SecretIsolationTest`: `listConnections` returned kind `mcpServer`, which the OpenAPI `kind` enum of the connection list did not allow. Fixed (enum and description); the class then passes alone.
  - `SecurityMatrixTest.tSec01_tSec11…`: the row-count snapshot changed during the calls because a run left by an earlier test class advanced in the background (one effect, three run events, one reminder; no agent row). It passes alone (8/8). Recorded as an accumulation-dependent test assumption, not as fixed.
  - Because of these failures, the coverage gate and packaging did not run; a full rerun after the enum fix was not done.
- New backend classes: `McpClientTest` 15 (3 new), `AgentToolServersTest` 9, `AgentLoopTest` 5, `AgentTraceAndTestsTest` 3. Existing S13/S75 agent classes (63 tests) and `ArchitectureTest` (6) pass.
- `tools/contract-check/contract_check.py`: PASS, 122 checks.
- Frontend: `tsc -b --noEmit`, `eslint .` and `api:check` clean. `vitest run`: 1,417 of 1,420 pass; the 3 failures (`ai.test.tsx`, `CaseDesignerPage.test.tsx`, `StudioPage.test.tsx`, 15 s timeouts) fail the same way on the unchanged base branch. New: `AgentToolsPage.test.tsx` (5); `Shell.test.tsx` lists "Agents".
- Not run: browser journeys, Docker stack, live MCP servers or models.
