import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { newId } from './state'

type S = components['schemas']

/**
 * Simplified UI double for the IN02/S73 adapter, inbound-trigger and inbound-event endpoints (mock mode and unit tests
 * only). It keeps the lists in memory and mirrors the server's shapes and version checks; the real validation and
 * permission rules live in the backend and are tested there.
 */
const B = '/api/v1/workspaces/:ws'
let adapters: S['Adapter'][] = []
let triggers: S['InboundTrigger'][] = []
let events: S['InboundEvent'][] = []

export function resetAdapterMocks(): void {
  adapters = []
  triggers = []
  events = []
}

/** Seeds adapters (for Studio tests) and received events (for the events list). */
export function seedAdapterMocks(seed: { adapters?: S['Adapter'][]; events?: S['InboundEvent'][] }): void {
  if (seed.adapters) adapters = [...seed.adapters]
  if (seed.events) events = [...seed.events]
}

export function mockInboundEvents(): S['InboundEvent'][] {
  return events
}

const problem = (status: number, code: string, title: string) =>
  HttpResponse.json({ code, title, status, type: 'about:blank' }, { status, headers: { 'Content-Type': 'application/problem+json' } })

export function createAdapterHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/adapters`, () => HttpResponse.json({ items: adapters })),
    http.post(`${B}/adapters`, async ({ request }) => {
      const body = (await request.json()) as S['AdapterCreateRequest']
      const created: S['Adapter'] = { id: newId(), key: body.key, kind: body.kind, name: body.name, secretRef: body.secretRef, config: body.config, enabled: true, version: 1, createdAt: new Date().toISOString(), updatedAt: null }
      adapters = [...adapters, created]
      return HttpResponse.json(created, { status: 201 })
    }),
    http.put(`${B}/adapters/:key`, async ({ params, request }) => {
      const body = (await request.json()) as S['AdapterUpdateRequest']
      const current = adapters.find((a) => a.key === params.key)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.version !== body.expectedVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The adapter or trigger changed since you loaded it')
      const updated: S['Adapter'] = { ...current, name: body.name, secretRef: body.secretRef, config: body.config, enabled: body.enabled, version: current.version + 1, updatedAt: new Date().toISOString() }
      adapters = adapters.map((a) => (a.key === current.key ? updated : a))
      return HttpResponse.json(updated)
    }),
    http.post(`${B}/adapters/:key/test-send`, ({ params }) => {
      const adapter = adapters.find((a) => a.key === params.key)
      if (!adapter) return problem(404, 'NOT_FOUND', 'Not found')
      if (!adapter.enabled) return problem(422, 'VALIDATION_FAILED', 'The adapter is disabled.')
      return HttpResponse.json({ adapter: adapter.key, kind: adapter.kind, outcome: 'stubbed', detail: { stub: true } })
    }),
    http.get(`${B}/inbound-triggers`, () => HttpResponse.json({ items: triggers })),
    http.post(`${B}/inbound-triggers`, async ({ params, request }) => {
      const body = (await request.json()) as S['InboundTriggerCreateRequest']
      const id = newId()
      const created: S['InboundTrigger'] = { id, key: body.key, source: body.source, name: body.name, workflowId: body.workflowId, secretRef: body.secretRef, filter: body.filter ?? {}, mapping: body.mapping ?? {}, enabled: true, version: 1, receivePath: `/api/v1/workspaces/${String(params.ws)}/inbound/${id}`, createdAt: new Date().toISOString(), updatedAt: null }
      triggers = [...triggers, created]
      return HttpResponse.json(created, { status: 201 })
    }),
    http.put(`${B}/inbound-triggers/:id`, async ({ params, request }) => {
      const body = (await request.json()) as S['InboundTriggerUpdateRequest']
      const current = triggers.find((t) => t.id === params.id)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (current.version !== body.expectedVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The adapter or trigger changed since you loaded it')
      const updated: S['InboundTrigger'] = { ...current, name: body.name, secretRef: body.secretRef, filter: body.filter ?? {}, mapping: body.mapping, enabled: body.enabled, version: current.version + 1, updatedAt: new Date().toISOString() }
      triggers = triggers.map((t) => (t.id === current.id ? updated : t))
      return HttpResponse.json(updated)
    }),
    http.get(`${B}/inbound-events`, () => HttpResponse.json({ items: events })),
    http.post(`${B}/inbound-events/purge`, async ({ request }) => {
      const body = (await request.json()) as S['InboundPurgeRequest']
      const cutoff = new Date(Date.now() - body.olderThanDays * 86_400_000)
      const before = events.length
      events = events.filter((e) => new Date(e.receivedAt) >= cutoff || (e.status === 'received' && !body.includeUnstarted))
      return HttpResponse.json({ deleted: before - events.length, receivedBefore: cutoff.toISOString() })
    }),
    http.post(`${B}/inbound-events/:id/start`, ({ params }) => {
      const event = events.find((e) => e.id === params.id)
      if (!event) return problem(404, 'NOT_FOUND', 'Not found')
      if (event.status === 'started') return HttpResponse.json({ eventId: event.id, runId: event.runId as string, alreadyStarted: true })
      const runId = newId()
      events = events.map((e) => (e.id === event.id ? { ...e, status: 'started', runId } : e))
      return HttpResponse.json({ eventId: event.id, runId, alreadyStarted: false })
    }),
  ]
}
