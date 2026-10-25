import { delay, http, HttpResponse, type HttpHandler } from 'msw'
import type { AttachmentRetention, DocumentTemplate } from '@/api/types'
import { getCurrentUser } from './handlers'
import { WORKSPACES, clock, db, isMember, newId, permissionsOf, userById, userByName } from './state'

/**
 * Document template registry and file retention in mock mode (DOC08, docs/build/S52): the same rules and answers as
 * the server, in memory. Versions are immutable; the latest version the caller saw is required to add another.
 */

const B = '/api/v1'
const PLACEHOLDER = /\{\{([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*)}}/g
const templates = new Map<string, DocumentTemplate & { wsId: string }>()

/** Templates of one workspace, for the shared asset index (PL01, docs/build/S61). Rows are live: edits apply. */
export function documentTemplatesOf(wsId: string): Array<DocumentTemplate & { wsId: string }> {
  seed()
  return [...templates.values()].filter((t) => t.wsId === wsId)
}

function keyFor(wsId: string, name: string): string {
  let base = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  if (!/^[a-z]/.test(base)) base = `template-${base}`.replace(/-+$/, '')
  base = base.slice(0, 56).replace(/-+$/, '')
  const taken = new Set(documentTemplatesOf(wsId).map((t) => t.key))
  if (!taken.has(base)) return base
  for (let n = 2; ; n++) if (!taken.has(`${base}-${n}`)) return `${base}-${n}`
}
const retention = new Map<string, { days: number | null; version: number; updatedAt: string | null }>()

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}): Response {
  return HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } })
}
const notFound = () => problem(404, 'NOT_FOUND', 'Not found')
const forbidden = () => problem(403, 'FORBIDDEN', 'Forbidden')

function placeholders(body: string): string[] | string {
  const names = [...new Set([...body.matchAll(PLACEHOLDER)].map((m) => m[1]!))]
  const stripped = body.replace(PLACEHOLDER, '')
  if (stripped.includes('{{') || stripped.includes('}}')) return 'The template contains an invalid placeholder.'
  return names
}

const invalid = (message: string) => problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { detail: message })

function summary(t: DocumentTemplate & { wsId: string }) {
  const latest = t.versions[0]!
  return { id: t.id, key: t.key, name: t.name, description: t.description ?? null, latestVersion: t.latestVersion, format: latest.format, placeholderCount: latest.placeholders.length, archived: t.archived, createdAt: t.createdAt, updatedAt: t.updatedAt, owner: t.owner, revision: t.revision }
}

function strip(t: DocumentTemplate & { wsId: string }): DocumentTemplate {
  const { wsId: _ws, ...rest } = t
  return rest
}

function seed(): void {
  if (templates.size > 0) return
  const now = clock.iso()
  const body = 'Dear {{customer}},\nYour order total is {{amount}}.'
  const wsId = WORKSPACES[0]!.id
  const id = newId()
  templates.set(id, {
    wsId, id, key: 'order-confirmation-letter', name: 'Order confirmation letter', description: 'Sent when an order is approved.', latestVersion: 1, archived: false, createdAt: now, updatedAt: now,
    owner: { id: userByName('dana.designer').id, displayName: 'Dana Designer' }, revision: 1,
    versions: [{ version: 1, format: 'pdf', body, placeholders: ['customer', 'amount'], sha256: 'a'.repeat(64), changeNote: 'First version', createdBy: { id: 'seed', displayName: 'Dana Designer' }, createdAt: now }],
  })
}

export function resetDocumentLibrary(): void {
  templates.clear()
  retention.clear()
}

export function createDocumentLibraryHandlers(): HttpHandler[] {
  const slow = () => delay(db.latencyMs)
  const member = (wsId: string) => {
    const user = getCurrentUser()
    return WORKSPACES.some((w) => w.id === wsId) && isMember(user, wsId) ? user : null
  }
  return [
    http.get(`${B}/workspaces/:ws/document-templates`, async ({ params, request }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.read')) return forbidden()
      const all = new URL(request.url).searchParams.get('includeArchived') === 'true'
      const items = [...templates.values()].filter((t) => t.wsId === wsId && (all || !t.archived)).sort((a, b) => a.name.localeCompare(b.name)).map(summary)
      return HttpResponse.json({ items, templateLimit: 200 })
    }),
    http.post(`${B}/workspaces/:ws/document-templates`, async ({ params, request }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.edit')) return forbidden()
      const body = (await request.json()) as { name: string; description?: string | null; format: 'pdf' | 'docx'; body: string; changeNote?: string | null; key?: string }
      const names = placeholders(body.body)
      if (typeof names === 'string') return invalid(names)
      if ([...templates.values()].some((t) => t.wsId === wsId && (t.name.toLowerCase() === body.name.trim().toLowerCase() || t.key === body.key))) return problem(409, 'DUPLICATE_KEY', 'The key is already in use', { detail: 'A template with this name or key already exists.' })
      const now = clock.iso()
      const id = newId()
      const row = {
        wsId, id, key: body.key ?? keyFor(wsId, body.name.trim()), name: body.name.trim(), description: body.description?.trim() || null, latestVersion: 1, archived: false, createdAt: now, updatedAt: now,
        owner: { id: user.id, displayName: userById(user.id)?.displayName ?? user.displayName }, revision: 1,
        versions: [{ version: 1, format: body.format, body: body.body, placeholders: names, sha256: newId().replace(/-/g, '').padEnd(64, '0'), changeNote: body.changeNote?.trim() || null, createdBy: { id: user.id, displayName: userById(user.id)?.displayName ?? user.displayName }, createdAt: now }],
      }
      templates.set(id, row)
      return HttpResponse.json(strip(row), { status: 201 })
    }),
    http.get(`${B}/workspaces/:ws/document-templates/:id`, async ({ params }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.read')) return forbidden()
      const row = templates.get(String(params.id))
      return row && row.wsId === wsId ? HttpResponse.json(strip(row)) : notFound()
    }),
    http.post(`${B}/workspaces/:ws/document-templates/:id/versions`, async ({ params, request }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.edit')) return forbidden()
      const row = templates.get(String(params.id))
      if (!row || row.wsId !== wsId) return notFound()
      const body = (await request.json()) as { expectedVersion: number; format: 'pdf' | 'docx'; body: string; changeNote?: string | null }
      const names = placeholders(body.body)
      if (typeof names === 'string') return invalid(names)
      if (body.expectedVersion !== row.latestVersion) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The workflow changed since you loaded it', { detail: 'The template changed. Reload before saving.', currentVersion: row.latestVersion })
      if (row.archived) return invalid('Restore the template before adding a version.')
      const latest = row.versions[0]!
      if (latest.body === body.body && latest.format === body.format) return invalid('The new version is identical to the latest version.')
      row.latestVersion += 1
      row.updatedAt = clock.iso()
      row.versions.unshift({ version: row.latestVersion, format: body.format, body: body.body, placeholders: names, sha256: newId().replace(/-/g, '').padEnd(64, '0'), changeNote: body.changeNote?.trim() || null, createdBy: { id: user.id, displayName: userById(user.id)?.displayName ?? user.displayName }, createdAt: row.updatedAt })
      return HttpResponse.json(strip(row), { status: 201 })
    }),
    http.put(`${B}/workspaces/:ws/document-templates/:id/archived`, async ({ params, request }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.edit')) return forbidden()
      const row = templates.get(String(params.id))
      if (!row || row.wsId !== wsId) return notFound()
      const archived = ((await request.json()) as { archived: boolean }).archived
      if (archived !== row.archived) row.revision += 1
      row.archived = archived
      row.updatedAt = clock.iso()
      return HttpResponse.json(strip(row))
    }),
    http.get(`${B}/workspaces/:ws/attachment-retention`, async ({ params }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workspace.manage-access')) return forbidden()
      return HttpResponse.json(view(wsId))
    }),
    http.put(`${B}/workspaces/:ws/attachment-retention`, async ({ params, request }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workspace.manage-access')) return forbidden()
      const body = (await request.json()) as { retentionDays: number | null; expectedVersion: number }
      const current = retention.get(wsId) ?? { days: null, version: 0, updatedAt: null }
      if (body.expectedVersion !== current.version) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The workflow changed since you loaded it', { detail: 'The retention setting changed. Reload before saving.', currentVersion: current.version })
      if (body.retentionDays !== null && (!Number.isInteger(body.retentionDays) || body.retentionDays < 1 || body.retentionDays > 3650)) return invalid('Use 1 to 3650 days.')
      retention.set(wsId, { days: body.retentionDays, version: current.version + 1, updatedAt: clock.iso() })
      return HttpResponse.json(view(wsId))
    }),
  ]
}

function view(wsId: string): AttachmentRetention {
  const row = retention.get(wsId) ?? { days: null, version: 0, updatedAt: null }
  return { retentionDays: row.days, version: row.version, eligibleNow: 0, updatedAt: row.updatedAt }
}
