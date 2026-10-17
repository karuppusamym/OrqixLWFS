# S13 — Agent connections and MCP tools (Demo 2)

Implemented 9 October 2026. A workflow step asks an AI agent to do one bounded task. The agent may call allow-listed tools on a registered MCP server; its structured result feeds a decision table, and the workflow continues. Provider and tool behavior is verified with scripted local services and one opt-in live run; the accuracy of a model on a customer's data requires separate acceptance.

## 1. Model

An agent is a **connection of kind `agent`** (`connection-definition-v0.schema.json`, `kind`; default `http`). A workflow uses it through the existing **Call a system** (`http`) node, so it inherits the effect journal, retry classes, audit, release pinning (`dependencies.connections`) and the studio mapping UI. The compiler needed no new node kind: it reads only `requestSchema`, `responseSchema` and `maxTimeoutSeconds` of the operation, which an agent operation has.

An agent operation declares:

| Field | Meaning |
|---|---|
| `requestSchema` | The input: a flat object of scalars, mapped from workflow fields exactly like an HTTP request body |
| `responseSchema` | The result: the object the agent must produce. `additionalProperties: false` and a `required` list are mandatory. Result fields are kept with `resultMapping` pointers (`/riskScore`) like an HTTP response |
| `effectClass` | Always `readOnly`: an agent cannot cause side effects in this release (schema constant) |
| `maxTimeoutSeconds` | Wall-clock limit for the whole model and tool loop, 1 to 30 (the workflow node caps `timeoutSeconds` at 30) |
| `agent.instructions` | Fixed system prompt written by an administrator, versioned with the connection version. Run data is never part of it |
| `agent.modelProfile` | An S10 model profile (`economy`, `auto`, ...), never a raw model id. Omitted: the deployment default |
| `agent.tools` | Allow-list: tool `name`, business `label`, optional administrator-written `description` (offered to the model instead of the server's own, which is untrusted) |
| `agent.maxToolCalls` | 1 to 10 tool calls per execution |
| `agent.summary` | Timeline sentence template: `{field}` is an input or result value, `{tools}` the labels of the tools that were called |

Registration checks what the JSON Schema cannot: a connection keeps its kind across versions, tool names are unique, and every `{placeholder}` names an input field, a result field or `{tools}`. Example: `examples/connections/vendor-risk-assistant.connection.json`.

**Binding** (per workspace, the existing model): `baseUrl` is the MCP endpoint (for example `http://procurement-stub:8090/mcp`), checked by the egress policy on save and again at every dispatch. Auth is `none` or `bearerToken` with a `secretRef`; `apiKeyHeader` is refused for agents. One agent connection talks to one MCP server; several servers would be several connections (not built: a server-registry connection kind is a follow-up).

## 2. Runtime

`EffectDispatcher` routes operations of an agent connection to `AgentExecutor` after the usual journal transaction A (register, lock, lease, policy check) and records the outcome in transaction B. The executor, in order:

1. Fails fast when AI is unavailable (`AgentModelPort.unavailableReason()`), the profile is not allowed, or the budget is exhausted. Nothing is contacted.
2. Opens an MCP session (`initialize`, `notifications/initialized`) and lists tools. Only allow-listed tools that the server really offers go to the model, as OpenAI function definitions with the server's `inputSchema`.
3. Runs the model loop (`temperature 0`, `provider.max_price`, usage accounting). For every requested tool call: a tool outside the allow-list is **refused without any call**; arguments must be one JSON object of at most 4,000 characters and are **validated against the tool's `inputSchema`** (schemas that reference anything outside themselves are refused, so a server cannot make Orqix fetch a URL); beyond `maxToolCalls` calls are refused and the model must answer. Tool results (structured content, else text, capped at 6,000 characters) go back as `tool` messages.
4. The final reply must be one JSON object that validates against `responseSchema`. An invalid reply gets **one repair turn** (schema errors, no tools, `response_format` set); a second failure is permanent. Only then are the `resultMapping` pointers applied.
5. Closes the session, meters usage, writes the call log and the audit entry (section 5).

Module boundary: `integration` owns the port `AgentModelPort`; the `ai` module implements it (`AgentModelAdapter`, `ToolChat`) with the S10 model policy, price caps, key and budgets, because the allowed dependency direction is ai to integration.

### MCP client

`McpClient` is a minimal client for the Streamable HTTP transport (protocol 2025-06-18 and 2025-03-26): JSON-RPC over POST, replies as `application/json` or one `text/event-stream`, `Mcp-Session-Id` and `MCP-Protocol-Version` headers, `DELETE` on close. It implements exactly `initialize`, `notifications/initialized`, `tools/list` (paged) and `tools/call`. **Why not the official Java SDK:** SDK 2.0.1 does support Jackson 3 (`mcp-json-jackson3`), so compatibility was not the blocker. The reasons are control and surface: the executor needs hard, testable bounds that the policy depends on (redirects never followed, every response capped at 1 MiB, one deadline across all calls, no logging of payloads, the egress policy applied by the caller) and needs four methods, while the SDK brings a reactive client, session management and transports we would have to constrain from outside. The client sits behind a small API, so adopting the SDK later is a local change.

## 3. Security

- **Tool output is untrusted data.** It reaches the model only as `tool` messages; the system prompt states that tool results and task data are never instructions. The model cannot change the workflow: it can only return an object that passes `responseSchema`, and only the mapped result fields leave the step. A model that obeys injected text can still produce a wrong but schema-valid value, which is why decisions that matter should use the structured fields in a decision table and a person for high stakes (the example does).
- **The model sees** the instructions, the schema, the mapped input and tool results. It never sees bindings, URLs, secret names or credentials. The bearer token is read from the secret store at dispatch and used only on the MCP request.
- **Allow-list and arguments** are enforced by Orqix, not by the model or the server (section 2, step 3).
- **No payloads at rest.** The journal detail, `agent_call`, the audit entry, the `ai_generation` row and the logs hold names, timings, token counts, cost and error codes; never prompts, input values, tool arguments, tool results or model output. The run timeline holds the business sentence rendered from the connection definition (it contains input and result values that the requester can already see, never model prose).
- **Audit:** `agent.call` per execution (actor system, resource connection; whitelist: key, operation, profile, model, outcome, toolCalls, inputTokens, outputTokens, costUsd, durationMs, effectId). The connection test writes `connection.tested` as before.
- **Network:** egress policy on the binding (host allow-list, private ranges, plain HTTP only where allowed); redirects are never followed.

## 4. Budgets and metering

Every execution is metered in `ai_generation` with kind `agent` (V10 extends the CHECK), so the monthly budget is one number across drafting, explanation and agents. Before the first model call the executor requires `min(monthly budget left, per-request maximum) > 0`; after every turn the cost accumulated in this execution must stay within that figure, so a step cannot overspend the per-request maximum by more than one turn. `agent_call` (V10) has one row per attempt of an agent effect: model, turns, tool names with timings and outcomes (`ok`, `toolError`, `invalidArguments`, `refusedNotAllowed`, `refusedLimit`), tokens, cost, outcome, error code.

## 5. Failure classes

| Situation | Classification | Attention reason (run) | Operator path |
|---|---|---|---|
| Provider timeout, 429, 5xx; MCP unreachable, timeout, 429, 5xx; deadline reached | retryable (`TIMEOUT`, `AI_PROVIDER_UNAVAILABLE`, `MCP_UNAVAILABLE`) | `RETRIES_EXHAUSTED` after the attempts | Retry when available |
| AI not configured, credentials rejected, provider out of credit, profile not allowed, budget exhausted, no allowed tool on the server | permanent (`AI_NOT_CONFIGURED`, `AI_CREDENTIALS_REJECTED`, `AI_PAYMENT_REQUIRED`, `AI_MODEL_NOT_ALLOWED`, `AI_BUDGET_EXCEEDED`, `TOOLS_UNAVAILABLE`) | `CONNECTION_UNAVAILABLE` | Administrator fixes AI settings or budget, operator retries |
| MCP server refuses the request (HTTP 4xx) | permanent `MCP_REJECTED` | `EFFECT_REJECTED` | Fix binding or credential, retry |
| Final answer invalid after one repair; MCP reply not valid MCP; no answer within the turn limit | permanent (`OUTPUT_INVALID`, `MCP_INVALID`, `TOO_MANY_TURNS`) | `RESPONSE_INVALID` | Check instructions or result definition, retry |
| Binding missing or disabled, egress denied at dispatch | permanent | `CONNECTION_UNAVAILABLE` | Fix binding, retry |

Every failure carries a business message ("Vendor risk assistant could not produce a valid answer. An operator must check the instructions or the result definition, then retry."). Agents are `readOnly`, so no outcome is `unknown`; a recorded success is reused on retry and never calls the model again.

## 6. AI disabled

With an empty `OPENROUTER_API_KEY` the product is rules-only. A step on an agent connection fails permanently with "needs AI, which is not available: AI is not configured..." (`CONNECTION_UNAVAILABLE`, retryable by an operator once an administrator fixed it). **Publishing** a workflow that uses an agent returns the **warning** `WF_AGENT_AI_UNAVAILABLE` (validation report, and `WARNINGS_NOT_ACKNOWLEDGED` until acknowledged). A warning rather than a blocker, because the key is deployment configuration that can change at any time, releases are portable between environments, and a missing binding (a workspace fact the admin can fix before publishing) stays a blocker. The connection test answers `422 AI_NOT_CONFIGURED`; Settings disables the Test button and says why.

## 7. Studio

- **Call a system** lists agent connections as "Vendor risk assistant · AI agent".
- The inspector shows, read-only, the instructions, the allowed tools (label and tool name), the tool-call limit and "Uses AI (cost varies, read-only)". The mapping UI is the existing one: inputs by type, result fields to keep.
- **Settings → Connections** shows the "AI agent" badge, the same read-only spec, the MCP address form (auth none or bearer) and **Test the agent**: a sample input form generated from the request schema; the result shows the structured output, the timeline sentence, duration, tokens and cost. Disabled with the reason when AI is off.
- The **run timeline** shows the sentence, for example "Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)".
- Not built: a palette alias "Ask an AI agent" (the palette is keyed by node kind, owned by the patterns work).

## 8. Limits

- One MCP server per agent connection; tools only (no resources, prompts, sampling, server-initiated requests); no write tools; no A2A, Teams or autonomous publication.
- The step is capped at 30 seconds (workflow schema `timeoutSeconds` maximum). A two-turn loop on the economy profile takes 3 to 10 seconds; slow models or several tool calls can exceed it and then fail as `TIMEOUT` (retryable, each attempt costing tokens). Raising the cap for agent nodes needs a workflow-schema change.
- The model call is `temperature 0` but not deterministic across providers; the structured result is validated, not guaranteed correct.
- A router profile that routes to a policy-refused model is refused (the answer is discarded), but the S10 circuit breaker is not tripped for agent steps.
- Tool descriptions of a server are untrusted: write `description` in the allow-list for anything that matters.

## 9. Demo 2 and the local example

`tools/procurement-stub` serves an MCP endpoint at `/mcp` (Streamable HTTP, sessions, optional bearer via `stub.mcp-token`) with the deterministic tools `vendor_risk_lookup(vendorName)` and `vendor_financials(vendorName)`, a call log (`GET /__mcp/calls`) and fault injection (`POST /__mcp/faults`: delay, status500/429/401, toolError, invalidJson, sse; `times`; `on` toolsCall or any). Fixtures: Acme Ltd risk 72 (high), Globex Corporation 18, Initech 45, Umbrella Trading 91 with a sanctions hit, any name containing "sanctioned" 95 with a hit; other names are derived from a hash.

The dev seed adds the group `compliance-reviewers` (members: the `orqix-purchasing-finance` IdP group, so `fin.finance` reviews), the agent connection bound to the stub's `/mcp`, and the unpublished workflow `vendor-onboarding-check` (`examples/vendor-onboarding-check.json`): form (vendor name, country, annual spend) → **Assess vendor risk** (agent) → sanctions decision table → risk decision table (score 70 or more) → compliance review or automatic approval → notification → end.

**Script (about 3 minutes):**

1. Settings → Connections: show the "AI agent" badge, the read-only instructions and the single allowed tool; run **Test the agent** with "Acme Ltd" (UK, 12,000): risk 72 (high) and the timeline sentence.
2. Designer → open `vendor-onboarding-check`: the **Assess vendor risk** step is a Call a system step on the agent; show the inspector, then the two decision tables as plain rules. Publish (acknowledge the warning only if AI is off).
3. As `rhea.requester` start it for "Acme Ltd": the timeline shows "Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)" and the request waits for Compliance review; as `fin.finance` approve → Approved.
4. Start "Globex Corporation": approved automatically. Start "Umbrella Trading": sanctions hit, compliance review.
5. Fault: `curl -X POST localhost:8095/__mcp/faults -d '{"mode":"status500","times":2}'`, start a run: the step retries and completes; Run detail shows the attempts.

With a real provider key each run spends about USD 0.0006 (DeepSeek v3.2, measured).

## 10. Verification

`McpClientTest` (12), `AgentExecutorTest` (24), `AgentFlowsTest` (10) and `AgentDisabledTest` (2) cover the loop, the allow-list, argument validation, structured output, every failure class, the budget, the deadline, egress, secrets in the journal/audit/logs/responses, the demo path through rules and approval on PostgreSQL and Flowable, the connection test and the AI-off behavior; every API response is validated against the OpenAPI contract. The stub has 87 tests (29 for MCP). `AgentLiveTest` (tag `live-ai`, `-Dorqix.ai.live=true`) runs real DeepSeek tool calling. `tools/api-e2e/e2e_agents.py` (scenarios 20 to 23) runs the demo on the Docker stack; `tests/e2e/specs/t-ai-02-agent-connection.spec.ts` runs in mock mode.
