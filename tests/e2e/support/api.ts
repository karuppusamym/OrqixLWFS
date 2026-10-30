import { type Page } from '@playwright/test'
import { apiGet } from './app'

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

/** Read the draft, let `edit` change its definition, and save it (a plain draft save, as the studio does). Returns the new revision. */
export async function editDraft(page: Page, ws: string, id: string, edit: (definition: Record<string, any>) => void): Promise<number> { // eslint-disable-line @typescript-eslint/no-explicit-any
  const got = await page.evaluate(async ({ ws, id }) => {
    const r = await fetch(`/api/v1/workspaces/${ws}/workflows/${id}/draft`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    return { status: r.status, etag: r.headers.get('ETag'), body: (await r.json()) as { revision: number; definition: Record<string, unknown> } }
  }, { ws, id })
  if (got.status !== 200) throw new Error(`get draft ${got.status}`)
  edit(got.body.definition)
  const put = await apiSend<{ revision: number }>(page, 'PUT', `/workspaces/${ws}/workflows/${id}/draft`, { definition: got.body.definition }, { 'If-Match': got.etag ?? `"${got.body.revision}"` })
  if (put.status !== 200) throw new Error(`save draft ${put.status} ${JSON.stringify(put.body)}`)
  return put.body.revision
}

/** Same-origin GET returning status and body (no throw on 4xx). */
export async function apiRead<T = unknown>(page: Page, path: string): Promise<{ status: number; body: T }> {
  return page.evaluate(async (p) => {
    const response = await fetch(`/api/v1${p}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
    return { status: response.status, body: (await response.json().catch(() => null)) as never }
  }, path)
}

/** Procurement stub fault injection (real stack only; E2E_STUB_URL names the stub, S1 section 6.2). */
export async function stubFault(mode: 'none' | 'status400' | 'status500', times = 1): Promise<void> {
  const url = process.env.E2E_STUB_URL
  if (!url) throw new Error('E2E_STUB_URL is not set')
  const response = await fetch(`${url}/__faults`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode, times }) })
  if (!response.ok) throw new Error(`stub fault ${mode}: ${response.status}`)
}

/**
 * Open the approval task of a request by its run id. The claimable list is paged and a long-lived shared database holds many
 * open tasks, so a title search on the first page can miss a new task; this pages through the approver's own list.
 */
export async function openTaskOfRun(approver: Page, ws: string, runId: string): Promise<void> {
  type Page_ = { items: Array<{ id: string; runId: string }>; nextCursor?: string | null }
  let taskId: string | undefined
  for (let attempt = 0; attempt < 15 && !taskId; attempt++) {
    let cursor: string | null = null
    do {
      const r: { status: number; body: Page_ } = await apiRead<Page_>(approver, `/workspaces/${ws}/tasks?view=claimable${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
      taskId = r.body?.items?.find((t) => t.runId === runId)?.id
      cursor = taskId ? null : (r.body?.nextCursor ?? null)
    } while (cursor)
    if (!taskId) await new Promise((res) => setTimeout(res, 1000))
  }
  if (!taskId) throw new Error(`No claimable task for run ${runId}`)
  await approver.goto(`/w/${ws}/tasks/${taskId}`)
  await approver.getByTestId('task-actions').waitFor()
}

/**
 * Removes the workspace-wide grants of `permission` held directly by `subjectId`. Specs that grant a grant-only
 * permission call this when they finish, so later specs and the API scripts see the seeded access again.
 */
export async function revokeGrant(admin: Page, ws: string, permission: string, subjectId: string): Promise<void> {
  const access = await apiGet<{ grants: Array<{ id: string; permission: string; workflowId?: string | null; principal: { type: string; value: string } }> }>(admin, `/workspaces/${ws}/access`)
  const held = access.grants.filter((g) => g.permission === permission && !g.workflowId && g.principal.type === 'subject' && g.principal.value === subjectId)
  for (const grant of held) {
    const removed = await apiSend(admin, 'DELETE', `/workspaces/${ws}/access/grants/${grant.id}`)
    if (removed.status !== 204) throw new Error(`revoke ${permission}: ${removed.status} ${JSON.stringify(removed.body)}`)
  }
}
