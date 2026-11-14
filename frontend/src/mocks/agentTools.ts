import { http, HttpResponse, type HttpHandler } from 'msw'

/**
 * Minimal UI double for the S135 agent tool endpoints (mock mode and unit tests only): no agent ran, nothing waits for
 * a person, no switch is set and the trace is off. Pins, waits, limits, trace redaction and the judge live in the
 * backend and are tested there (AgentToolServersTest, AgentLoopTest, AgentTraceAndTestsTest); page tests override
 * these handlers with their own data.
 */
const B = '/api/v1/workspaces/:ws'

export function createAgentToolHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/agent-dashboard`, () => HttpResponse.json({
      days: 7, from: new Date(Date.now() - 7 * 86_400_000).toISOString(), agents: [], refusedToolCalls: {}, schemaRefusals: [],
      killSwitches: [], loops: {}, openRequests: 0, aiTaskAgreement: null,
    })),
    http.get(`${B}/agent-requests`, () => HttpResponse.json([])),
    http.get(`${B}/agent-kill-switches`, () => HttpResponse.json([])),
    http.get(`${B}/agent-trace-settings`, () => HttpResponse.json({
      enabled: false, retentionDays: 7, redactFields: [], redactPatterns: [], changedBy: null, changedAt: null, version: 0,
    })),
    http.get(`${B}/runs/:run/agent-trace`, ({ params }) => HttpResponse.json({ runId: params.run, nodeId: null, enabled: false, events: [] })),
    http.get(`${B}/connections/:key/agent-tests`, ({ params }) => HttpResponse.json({
      connectionKey: params.key, suite: { cases: [] }, lastResult: null, lastRunAt: null, version: 0,
    })),
  ]
}
