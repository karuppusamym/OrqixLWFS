import { http, HttpResponse, type HttpHandler } from 'msw'
import type { McpCall, McpServer } from '@/api/types'
import { WORKSPACES, isMember, permissionsOf, userById, type MockUser } from './state'

/**
 * MCP server settings (CG-M04, docs/build/S136) for mock mode and unit tests: getMcpServer, setMcpServer and
 * listMcpCalls. Off until switched on, like the server. Calls are a small fixture shown once the server is on; the real
 * list comes from the audit log.
 */
const B = '*/api/v1/workspaces/:ws'

interface Switch {
  enabled: boolean
  updatedAt: string
  updatedById: string
}

const switches = new Map<string, Switch>()
const calls = new Map<string, McpCall[]>()

/** Tests start with the server off everywhere. */
export function resetMcpMock() {
  switches.clear()
  calls.clear()
}

/** Tests: recent calls of a workspace. */
export function seedMcpCalls(ws: string, items: McpCall[]) {
  calls.set(ws, items)
}

function state(ws: string, origin: string): McpServer {
  const s = switches.get(ws)
  const by = s ? userById(s.updatedById) : undefined
  return {
    enabled: s?.enabled ?? false,
    endpoint: `${origin}/mcp`,
    metadataUrl: `${origin}/.well-known/oauth-protected-resource/mcp`,
    audiences: ['orqix-mcp'],
    callsPerMinute: 60,
    confirmationSeconds: 300,
    updatedAt: s?.updatedAt ?? null,
    updatedBy: s ? { id: s.updatedById, displayName: by?.displayName ?? 'Unknown user' } : null,
  }
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

export function createMcpServerHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const admin = (ws: string) => permissionsOf(currentUser(), ws).includes('workspace.manage-access')
  const refused = (ws: string) => (!visible(ws) ? problem(404, 'NOT_FOUND', 'Not found') : !admin(ws) ? problem(403, 'FORBIDDEN', 'You are not allowed to do this') : null)
  return [
    http.get(`${B}/mcp-server`, ({ params, request }) => {
      const ws = String(params.ws)
      return refused(ws) ?? HttpResponse.json(state(ws, new URL(request.url).origin))
    }),
    http.put(`${B}/mcp-server`, async ({ params, request }) => {
      const ws = String(params.ws)
      const no = refused(ws)
      if (no) return no
      const body = (await request.json().catch(() => ({}))) as { enabled?: unknown }
      if (typeof body.enabled !== 'boolean') return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { detail: 'enabled must be true or false.' })
      if ((switches.get(ws)?.enabled ?? false) !== body.enabled) {
        switches.set(ws, { enabled: body.enabled, updatedAt: new Date().toISOString(), updatedById: currentUser().id })
      }
      return HttpResponse.json(state(ws, new URL(request.url).origin))
    }),
    http.get(`${B}/mcp-server/calls`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws) ?? HttpResponse.json({ items: calls.get(ws) ?? [] })
    }),
  ]
}
