import { type Page } from '@playwright/test'

/** Same-origin API call from inside the page (session cookie + XSRF header on the real stack, in-page mock otherwise). */
export async function apiSend<T = unknown>(page: Page, method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: unknown, headers: Record<string, string> = {}): Promise<{ status: number; body: T }> {
  return page.evaluate(async ({ method, path, body, headers }) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=')
    const h: Record<string, string> = { Accept: 'application/json', ...headers }
    if (body !== undefined) h['Content-Type'] = 'application/json'
    if (method === 'POST') h['Idempotency-Key'] = h['Idempotency-Key'] ?? crypto.randomUUID()
    if (token) h['X-XSRF-TOKEN'] = decodeURIComponent(token)
    const response = await fetch('/api/v1' + path, { method, headers: h, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) })
    return { status: response.status, body: (await response.json().catch(() => null)) as never }
  }, { method, path, body, headers })
}

export const uniq = (prefix: string): string => `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`

/** Create a workflow from a template under a unique name and key; returns its id and name. */
export async function createWorkflow(page: Page, ws: string, name: string, templateId = 'purchase-approval'): Promise<{ id: string; name: string; key: string }> {
  const key = uniq('v').toLowerCase().replace(/[^a-z0-9-]/g, '')
  const r = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/workflows`, { key, name, templateId })
  if (r.status !== 201) throw new Error(`create workflow ${name}: ${r.status} ${JSON.stringify(r.body)}`)
  return { id: r.body.id, name, key }
}

/** Publish the current draft of a workflow and return the release id. */
export async function publish(page: Page, ws: string, id: string): Promise<string> {
  const g = await page.evaluate(async ({ ws, id }) => {
    const r = await fetch(`/api/v1/workspaces/${ws}/workflows/${id}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    return (await r.json()) as { draftRevision: number }
  }, { ws, id })
  const r = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/workflows/${id}/publish`, { draftRevision: g.draftRevision, acknowledgeWarnings: true })
  if (r.status !== 201) throw new Error(`publish ${id}: ${r.status} ${JSON.stringify(r.body)}`)
  return r.body.id
}

/** Change the draft's metadata description (a harmless edit that makes the next publish a new release). */
export async function touchDraft(page: Page, ws: string, id: string, description: string): Promise<void> {
  const got = await page.evaluate(async ({ ws, id }) => {
    const r = await fetch(`/api/v1/workspaces/${ws}/workflows/${id}/draft`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    return { status: r.status, etag: r.headers.get('ETag'), body: (await r.json()) as { revision: number; definition: { metadata?: Record<string, unknown> } } }
  }, { ws, id })
  if (got.status !== 200) throw new Error(`get draft ${got.status}`)
  const definition = got.body.definition
  definition.metadata = { ...(definition.metadata ?? {}), description }
  const put = await apiSend(page, 'PUT', `/workspaces/${ws}/workflows/${id}/draft`, { definition }, { 'If-Match': got.etag ?? `"${got.body.revision}"` })
  if (put.status !== 200) throw new Error(`save draft ${put.status} ${JSON.stringify(put.body)}`)
}
