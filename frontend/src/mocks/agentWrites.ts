import { http, HttpResponse, type HttpHandler } from 'msw'

/**
 * Minimal UI double for the IN04 agent write tool endpoints (mock mode and unit tests only): no tool is declared and
 * nothing was written. The approval, separation-of-duties and reconciliation rules live in the backend and are tested
 * there (AgentWriteToolsTest); page tests override these handlers with their own data.
 */
const B = '/api/v1/workspaces/:ws'

export function createAgentWriteHandlers(): HttpHandler[] {
  let retention: { retentionDays: number | null; version: number; eligibleNow: number; updatedAt: string | null } = {
    retentionDays: null, version: 0, eligibleNow: 0, updatedAt: null,
  }
  return [
    http.get(`${B}/agent-write-tools`, () => HttpResponse.json({ tools: [], approvals: [], canApprove: false, canRevoke: false })),
    http.get(`${B}/agent-writes`, () => HttpResponse.json({ items: [], canReconcile: false })),
    // S85: the write record retention setting, kept in memory for the session.
    http.get(`${B}/agent-write-retention`, () => HttpResponse.json(retention)),
    http.put(`${B}/agent-write-retention`, async ({ request }) => {
      const body = (await request.json()) as { retentionDays: number | null; expectedVersion: number }
      if (body.expectedVersion !== retention.version) {
        return HttpResponse.json({ type: 'https://orqix.example/problems/workflow-version-conflict', title: 'The workflow changed since you loaded it',
          status: 409, code: 'WORKFLOW_VERSION_CONFLICT', correlationId: crypto.randomUUID(), detail: 'The retention setting changed. Reload before saving.',
          currentVersion: retention.version }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
      }
      retention = { retentionDays: body.retentionDays, version: retention.version + 1, eligibleNow: 0, updatedAt: new Date().toISOString() }
      return HttpResponse.json(retention)
    }),
  ]
}
