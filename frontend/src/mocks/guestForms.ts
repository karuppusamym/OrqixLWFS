import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'

type S = components['schemas']

/**
 * Simplified UI double for lookup questions and guest response links (docs/build/S140), mock mode and unit tests only.
 * Lookups answer from a fixed list of people; guest links start empty. Calls to the connected system, token hashing,
 * domain checks, expiry and permissions are the backend's and are tested there.
 */
const B = '/api/v1/workspaces/:ws'

export const MOCK_LOOKUP_PEOPLE: S['LookupOption'][] = [
  { value: 'ann.buyer@supplier.example', label: 'Ann Buyer', detail: 'ann.buyer@supplier.example' },
  { value: 'andy.other@supplier.example', label: 'Andy Other', detail: 'andy.other@supplier.example' },
  { value: 'bob@elsewhere.example', label: 'Bob Elsewhere', detail: 'bob@elsewhere.example' },
]

let links: S['GuestLink'][] = []
let domains: string[] = []

export function resetGuestFormMocks(): void {
  links = []
  domains = []
}

export function seedGuestLinks(seed: S['GuestLink'][]): void {
  links = [...seed]
}

export function createGuestFormHandlers(): HttpHandler[] {
  return [
    http.post(`${B}/workflows/:workflowId/lookups/:fieldKey`, async ({ request }) => {
      const body = (await request.json().catch(() => ({}))) as { search?: string }
      const text = (body.search ?? '').trim().toLowerCase()
      if (text.length < 2) return HttpResponse.json({ options: [], truncated: false, cached: false, minSearchLength: 2 } satisfies S['LookupOptions'])
      const options = MOCK_LOOKUP_PEOPLE.filter((o) => o.value.startsWith(text) || o.label.toLowerCase().includes(text))
      return HttpResponse.json({ options, truncated: false, cached: false, minSearchLength: 2 } satisfies S['LookupOptions'])
    }),
    http.get(`${B}/guest-links`, ({ request }) => {
      const runId = new URL(request.url).searchParams.get('runId')
      return HttpResponse.json({ items: links.filter((l) => !runId || l.runId === runId) } satisfies S['GuestLinkList'])
    }),
    http.post(`${B}/guest-links/:linkId/token`, ({ params }) => {
      const link = links.find((l) => l.id === params.linkId)
      if (!link) return HttpResponse.json({ code: 'NOT_FOUND', status: 404, title: 'Not found' }, { status: 404 })
      const expiresAt = new Date(Date.now() + 7 * 86400000).toISOString()
      Object.assign(link, { tokenIssued: true, tokenExpiresAt: expiresAt, tokensIssued: link.tokensIssued + 1, state: 'waiting' })
      const token = `ogf_${'M'.repeat(43)}`
      return HttpResponse.json({ linkId: link.id, url: `${window.location.origin}/guest/${String(params.ws)}#${token}`, expiresAt } satisfies S['GuestLinkIssued'], { status: 201 })
    }),
    http.delete(`${B}/guest-links/:linkId/token`, ({ params }) => {
      const link = links.find((l) => l.id === params.linkId)
      if (link) Object.assign(link, { tokenIssued: false, tokenExpiresAt: null, state: 'revoked' })
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${B}/guest-links/:linkId/send`, ({ params }) => {
      const link = links.find((l) => l.id === params.linkId)
      if (!link) return HttpResponse.json({ code: 'NOT_FOUND', status: 404, title: 'Not found' }, { status: 404 })
      link.sendState = 'pending'
      return HttpResponse.json(link)
    }),
    http.get(`${B}/guest-domains`, () => HttpResponse.json({ domains } satisfies S['GuestDomains'])),
    http.put(`${B}/guest-domains`, async ({ request }) => {
      const body = (await request.json()) as S['GuestDomains']
      domains = [...new Set(body.domains.map((d) => d.trim().toLowerCase()))].sort()
      return HttpResponse.json({ domains } satisfies S['GuestDomains'])
    }),
  ]
}
