import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { newId } from './state'

type S = components['schemas']

/**
 * Simplified UI double for the IN02 adapter and inbound-trigger endpoints (mock mode and unit tests only). It keeps the
 * lists in memory and mirrors the server's shapes; the real validation rules live in the backend and are tested there.
 */
const B = '/api/v1/workspaces/:ws'
let adapters: S['Adapter'][] = []
let triggers: S['InboundTrigger'][] = []

export function resetAdapterMocks(): void {
  adapters = []
  triggers = []
}

export function createAdapterHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/adapters`, () => HttpResponse.json({ items: adapters })),
    http.post(`${B}/adapters`, async ({ request }) => {
      const body = (await request.json()) as S['AdapterCreateRequest']
      const created: S['Adapter'] = { id: newId(), key: body.key, kind: body.kind, name: body.name, secretRef: body.secretRef, config: body.config, enabled: true, createdAt: new Date().toISOString() }
      adapters = [...adapters, created]
      return HttpResponse.json(created, { status: 201 })
    }),
    http.post(`${B}/adapters/:key/test-send`, ({ params }) => {
      const adapter = adapters.find((a) => a.key === params.key)
      if (!adapter) return HttpResponse.json({ code: 'NOT_FOUND', title: 'Not found', status: 404, type: 'about:blank' }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } })
      return HttpResponse.json({ adapter: adapter.key, kind: adapter.kind, outcome: 'stubbed', detail: { stub: true } })
    }),
    http.get(`${B}/inbound-triggers`, () => HttpResponse.json({ items: triggers })),
    http.post(`${B}/inbound-triggers`, async ({ params, request }) => {
      const body = (await request.json()) as S['InboundTriggerCreateRequest']
      const id = newId()
      const created: S['InboundTrigger'] = { id, key: body.key, source: body.source, name: body.name, workflowId: body.workflowId, secretRef: body.secretRef, filter: body.filter ?? {}, mapping: body.mapping ?? {}, enabled: true, receivePath: `/api/v1/workspaces/${String(params.ws)}/inbound/${id}`, createdAt: new Date().toISOString() }
      triggers = [...triggers, created]
      return HttpResponse.json(created, { status: 201 })
    }),
  ]
}
