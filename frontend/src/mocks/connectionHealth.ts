import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { WORKSPACES, isMember, permissionsOf, type MockUser } from './state'

type S = components['schemas']

/**
 * Simplified UI double for the ConnectionHealth endpoints (CG-C09, CG-W04, docs/build/S131) in mock mode and unit
 * tests. Health figures are fixed samples; request limits are kept in memory. The real aggregation, circuit and limits
 * live in the backend and are tested there.
 */
const B = '*/api/v1/workspaces/:ws'

const MOCK_CONNECTIONS: Record<string, string> = {
  procurement: 'Procurement system',
  'vendor-risk-assistant': 'Vendor risk assistant',
}

const limits = new Map<string, number | null>()
let overrides = new Map<string, Partial<S['ConnectionHealth']>>()

export function resetConnectionHealthMocks(): void {
  limits.clear()
  overrides = new Map()
}

/** Replaces parts of a connection's mock health (for tests and demos), for example an open circuit. */
export function seedConnectionHealth(connectionKey: string, health: Partial<S['ConnectionHealth']>): void {
  overrides.set(connectionKey, health)
}

const problem = (status: number, code: string, title: string) =>
  HttpResponse.json({ type: 'about:blank', title, status, code }, { status, headers: { 'Content-Type': 'application/problem+json' } })

function windowOf(calls: number, succeeded: number, p50: number | null, p95: number | null, limited = 0): S['ConnectionHealthWindow'] {
  return { calls, succeeded, successRate: calls === 0 ? null : succeeded / calls, p50Ms: p50, p95Ms: p95, rateLimited: limited, tokenRefreshFailures: 0, deferred: 0 }
}

function health(ws: string, key: string): S['ConnectionHealth'] {
  const limit = limits.get(`${ws}:${key}`) ?? null
  const used = key === 'procurement'
  const base: S['ConnectionHealth'] = {
    connectionKey: key,
    name: MOCK_CONNECTIONS[key] ?? key,
    bound: true,
    enabled: true,
    lastHour: used ? windowOf(42, 41, 180, 640) : windowOf(0, 0, null, null),
    lastDay: used ? windowOf(615, 606, 172, 590, 3) : windowOf(0, 0, null, null),
    lastError: used ? { errorCode: 'RATE_LIMITED', httpStatus: 429, outcome: 'retryable', at: '2026-11-05T08:12:00Z' } : null,
    circuit: { state: 'closed', consecutiveFailures: 0, failureThreshold: 10, openedAt: null, openUntil: null, reason: null, timesOpened: 0 },
    rateLimit: { maxRequestsPerMinute: limit, perPod: limit, livePods: 1 },
    notices: [],
    asOf: new Date().toISOString(),
  }
  return { ...base, ...overrides.get(key) }
}

function access(user: MockUser, ws: string | readonly string[] | undefined) {
  const id = Array.isArray(ws) ? ws[0] : (ws as string | undefined)
  if (!id || !WORKSPACES.some((w) => w.id === id) || !isMember(user, id)) return null
  return { id, perms: permissionsOf(user, id) }
}

export function createConnectionHealthHandlers(currentUser: () => MockUser): HttpHandler[] {
  return [
    http.get(`${B}/connection-health`, ({ params }) => {
      const a = access(currentUser(), params.ws)
      if (!a) return problem(404, 'NOT_FOUND', 'Not found')
      if (!a.perms.includes('connection.read')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const items = a.id === WORKSPACES[2]?.id ? [] : Object.keys(MOCK_CONNECTIONS).map((key) => health(a.id, key))
      return HttpResponse.json({ items, noticeCount: items.reduce((n, h) => n + h.notices.length, 0) } satisfies S['ConnectionHealthList'])
    }),
    http.get(`${B}/connections/:key/health`, ({ params }) => {
      const a = access(currentUser(), params.ws)
      const key = String(params.key)
      if (!a || !(key in MOCK_CONNECTIONS)) return problem(404, 'NOT_FOUND', 'Not found')
      if (!a.perms.includes('connection.read')) return problem(403, 'FORBIDDEN', 'Forbidden')
      return HttpResponse.json(health(a.id, key))
    }),
    http.put(`${B}/connections/:key/rate-limit`, async ({ params, request }) => {
      const a = access(currentUser(), params.ws)
      const key = String(params.key)
      if (!a || !(key in MOCK_CONNECTIONS)) return problem(404, 'NOT_FOUND', 'Not found')
      if (!a.perms.includes('connection.manage')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const body = (await request.json()) as { maxRequestsPerMinute: number | null }
      const limit = body.maxRequestsPerMinute
      if (limit !== null && (!Number.isInteger(limit) || limit < 1 || limit > 100000)) {
        return problem(422, 'VALIDATION_FAILED', 'maxRequestsPerMinute must be between 1 and 100000, or null for no limit.')
      }
      limits.set(`${a.id}:${key}`, limit)
      return HttpResponse.json(health(a.id, key))
    }),
  ]
}
