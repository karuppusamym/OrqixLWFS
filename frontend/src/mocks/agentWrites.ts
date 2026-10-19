import { http, HttpResponse, type HttpHandler } from 'msw'

/**
 * Minimal UI double for the IN04 agent write tool endpoints (mock mode and unit tests only): no tool is declared and
 * nothing was written. The approval, separation-of-duties and reconciliation rules live in the backend and are tested
 * there (AgentWriteToolsTest); page tests override these handlers with their own data.
 */
const B = '/api/v1/workspaces/:ws'

export function createAgentWriteHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/agent-write-tools`, () => HttpResponse.json({ tools: [], approvals: [], canApprove: false, canRevoke: false })),
    http.get(`${B}/agent-writes`, () => HttpResponse.json({ items: [], canReconcile: false })),
  ]
}
