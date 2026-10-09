# S13 — Agent connections and MCP tools

Implemented 9 October 2026. Provider and tool behavior is verified with scripted local services; model accuracy and customer integrations require separate acceptance.

## 1. Connection contract

A connection with `kind: agent` exposes approved operations through the existing **Call a system** step. Each operation declares request and response schemas plus its agent instructions, model profile, tool allowlist and execution limits. See [`vendor-risk-assistant.connection.json`](../../examples/connections/vendor-risk-assistant.connection.json).

## 2. Model adapter

`AgentModelPort` isolates integration from the AI provider implementation. `AgentModelAdapter` uses the configured AI provider and model policy, including excluded models, price limits and budgets.

## 3. Tool transport

`McpClient` initializes a Streamable HTTP session, lists tools, invokes approved tools and handles session cleanup. Binding authentication and the existing egress policy govern network access. JSON and supported SSE responses are bounded; redirects and malformed responses fail explicitly.

## 4. Execution limits

The executor bounds model turns, tool calls, input/output sizes, time and spend. Only allowlisted tools are offered. Tool arguments are validated before invocation; a tool result cannot grant additional tools or change platform instructions. Final output must match the operation's response schema before result mappings are applied.

## 5. Effect outcomes

Agent calls use the existing effect dispatcher and journal. The supported tools are read-only. Retryable transport failures, unusable output and permanent configuration problems retain explicit classifications and operator recovery behavior. A recorded success is reused on retry.

## 6. Usage records

`agent_call` stores model, turn count, tool names/timings/outcomes, token counts, spend and execution outcome. Agent usage also contributes to the shared `ai_generation` budget. Prompts, request values, tool arguments, raw tool results and raw model output are excluded from those records and audit metadata.

## 7. Administration

Connection definitions configure agent behavior; bindings configure the MCP endpoint and credentials. Define and bind approved tools before publishing a workflow. AI provider configuration remains deployment-owned. The existing connection test endpoint can test an approved testable operation.

## 8. Product boundary

This slice provides bounded agent operations inside workflows. It does not enable autonomous publication, arbitrary tool access, write tools, Teams or A2A. Agent output is model-generated and may be inaccurate even when it matches the schema.

## 9. Local example

The procurement stub exposes `/mcp` with deterministic `vendor_risk_lookup` and `vendor_financials` tools and controllable fault modes. The dev seed supplies the vendor-risk connection, compliance group and an unpublished vendor workflow. [`vendor-onboarding-check.json`](../../examples/vendor-onboarding-check.json) routes low risk automatically and high risk or sanctions hits to a person. With a real provider key, running this example spends provider credit.

## 10. Verification

`AgentExecutorTest` and `McpClientTest` cover the bounded tool loop, schema checks, access restrictions, metering and transport failures. `AgentWorkflowTest` verifies publication, provider/tool calls, effect dispatch, rules and compliance tasks on PostgreSQL and Flowable. The procurement stub suite covers its MCP endpoint, authentication and fault modes.
