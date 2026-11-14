import { http, HttpResponse, type HttpHandler } from 'msw'
import type { ChatIdentity, StepTemplate, TaskActionLink, TaskActionMessage, TaskActionSettings, UpdateTaskActionSettings } from '@/api/types'
import { WORKSPACES, isMember, permissionsOf, userById, type MockUser } from './state'

/**
 * S141 mock backend: actionable task settings, chat identities, deliveries and signed action links (CG-T01) and
 * workspace step templates (CG-S03), for mock mode and unit tests. Off until switched on, like the server; the link
 * page answers for tokens registered with seedTaskActionLink.
 */
const B = '*/api/v1/workspaces/:ws'

const settings = new Map<string, TaskActionSettings>()
const identities = new Map<string, ChatIdentity[]>()
const messages = new Map<string, TaskActionMessage[]>()
const links = new Map<string, TaskActionLink & { decided?: string }>()
const templates = new Map<string, StepTemplate[]>()

/** Tests start with everything empty and switched off. */
export function resetTaskActionsMock() {
  settings.clear()
  identities.clear()
  messages.clear()
  links.clear()
  templates.clear()
}

export function seedTaskActionLink(token: string, link: TaskActionLink) {
  links.set(token, { ...link })
}

export function seedTaskActionMessages(ws: string, items: TaskActionMessage[]) {
  messages.set(ws, items)
}

export function seedStepTemplates(ws: string, items: StepTemplate[]) {
  templates.set(ws, items)
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const empty = (): TaskActionSettings => ({ enabled: false, linkHours: 48, version: 0, channels: [], updatedBy: null, updatedAt: null })

export function createTaskActionHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const has = (ws: string, permission: string) => (permissionsOf(currentUser(), ws) as string[]).includes(permission)
  const refused = (ws: string, permission: string) =>
    !visible(ws) ? problem(404, 'NOT_FOUND', 'Not found') : !has(ws, permission) ? problem(403, 'FORBIDDEN', 'You are not allowed to do this') : null
  const me = () => ({ id: currentUser().id, displayName: currentUser().displayName })
  return [
    http.get(`${B}/task-actions/settings`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access') ?? HttpResponse.json(settings.get(ws) ?? empty())
    }),
    http.put(`${B}/task-actions/settings`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const body = (await request.json()) as UpdateTaskActionSettings
      const current = settings.get(ws) ?? empty()
      if (body.expectedVersion !== current.version) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The settings changed since you loaded them.', { currentVersion: current.version })
      const errors = body.channels.flatMap((c, i) => {
        const config = (c.config ?? {}) as { recipientDomains?: string[] }
        if (c.channel === 'email' && (config.recipientDomains ?? []).some((d) => d.includes('*'))) return [{ pointer: `/channels/${i}/config/recipientDomains`, code: 'format', message: 'Use exact domains such as example.com; wildcards are not allowed.' }]
        if (c.channel !== 'email' && !c.secretRef) return [{ pointer: `/channels/${i}/secretRef`, code: 'required', message: `Name the secret that signs interactions from ${c.channel}.` }]
        return []
      })
      if (errors.length) return problem(422, 'VALIDATION_FAILED', 'Some channel settings are not valid.', { fieldErrors: errors })
      const next: TaskActionSettings = {
        enabled: body.enabled,
        linkHours: body.linkHours,
        version: current.version + 1,
        channels: body.channels.map((c) => ({ channel: c.channel, enabled: c.enabled, config: c.config ?? {}, secretRef: c.channel === 'email' ? null : (c.secretRef ?? null) })),
        updatedBy: me(),
        updatedAt: new Date().toISOString(),
      }
      settings.set(ws, next)
      return HttpResponse.json(next)
    }),
    http.get(`${B}/task-actions/identities`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access') ?? HttpResponse.json({ items: identities.get(ws) ?? [] })
    }),
    http.put(`${B}/task-actions/identities`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const body = (await request.json()) as { platform: 'teams' | 'slack'; platformUserId: string; subjectId: string }
      const person = userById(body.subjectId)
      if (!person) return problem(422, 'VALIDATION_FAILED', 'Choose an active person.', { fieldErrors: [{ pointer: '/subjectId', code: 'enum', message: 'Unknown person' }] })
      const row: ChatIdentity = { platform: body.platform, platformUserId: body.platformUserId, person: { id: person.id, displayName: person.displayName }, linkedBy: me(), linkedAt: new Date().toISOString() }
      const rows = (identities.get(ws) ?? []).filter((r) => !(r.platform === body.platform && (r.platformUserId === body.platformUserId || r.person.id === person.id)))
      identities.set(ws, [...rows, row])
      return HttpResponse.json(row)
    }),
    http.delete(`${B}/task-actions/identities/:platform/:user`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const rows = identities.get(ws) ?? []
      const kept = rows.filter((r) => !(r.platform === params.platform && r.platformUserId === params.user))
      if (kept.length === rows.length) return problem(404, 'NOT_FOUND', 'Not found')
      identities.set(ws, kept)
      return new HttpResponse(null, { status: 204 })
    }),
    http.get(`${B}/task-actions/messages`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workspace.manage-access') ?? HttpResponse.json({ items: messages.get(ws) ?? [] })
    }),
    http.get('*/api/v1/task-action-links/:token', ({ params }) => {
      const link = links.get(String(params.token))
      if (!link) return problem(404, 'NOT_FOUND', 'Not found')
      const { decided: _decided, ...view } = link
      return HttpResponse.json(view)
    }),
    http.post('*/api/v1/task-action-links/:token', async ({ params, request }) => {
      const link = links.get(String(params.token))
      if (!link) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { action: 'claim' | 'approve' | 'reject'; expectedVersion: number; reason?: string; confirm?: boolean }
      if (body.confirm !== true) return problem(422, 'VALIDATION_FAILED', 'Confirm the action first.', { fieldErrors: [{ pointer: '/confirm', code: 'const', message: 'Must be true' }] })
      if (body.action !== 'claim' && link.decided) {
        return HttpResponse.json({ action: body.action, outcome: 'replayed', taskId: link.taskId, taskStatus: 'completed', version: link.version, decision: link.decided === 'approve' ? 'approved' : 'rejected' })
      }
      if (body.expectedVersion !== link.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The task changed since you loaded it.', { currentVersion: link.version })
      if (body.action === 'reject' && link.rejectReasonRequired && !body.reason?.trim()) {
        return problem(422, 'REASON_REQUIRED', 'Give a reason for the rejection.', { fieldErrors: [{ pointer: '/reason', code: 'required', message: 'A reason is required' }] })
      }
      if (body.action === 'claim') {
        link.version += 1
        link.actions = { ...link.actions, claim: { allowed: false, reasonCode: 'TASK_ALREADY_CLAIMED' } }
        return HttpResponse.json({ action: 'claim', outcome: 'done', taskId: link.taskId, taskStatus: 'open', version: link.version, decision: null })
      }
      link.decided = body.action
      link.version += 2
      link.status = 'completed'
      const closed = { allowed: false, reasonCode: 'TASK_NOT_OPEN' }
      link.actions = { claim: closed, approve: closed, reject: closed }
      return HttpResponse.json({ action: body.action, outcome: 'done', taskId: link.taskId, taskStatus: 'completed', version: link.version, decision: body.action === 'approve' ? 'approved' : 'rejected' })
    }),
    http.get(`${B}/step-templates`, ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.read')
      if (denied) return denied
      const url = new URL(request.url)
      const q = (url.searchParams.get('q') ?? '').toLowerCase()
      const archived = url.searchParams.get('includeArchived') === 'true'
      const own = (templates.get(ws) ?? []).filter((t) => archived || t.status === 'active')
      const shared = [...templates.entries()].filter(([other]) => other !== ws).flatMap(([, items]) => items)
        .filter((t) => t.shared && t.status === 'active').map((t) => ({ ...t, own: false }))
      const items = [...own, ...shared].filter((t) => !q || [t.name, t.description, t.stepType, t.operation, t.key].some((v) => v?.toLowerCase().includes(q)))
      return HttpResponse.json({ items })
    }),
    http.post(`${B}/step-templates`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.edit')
      if (denied) return denied
      const body = (await request.json()) as { key: string; name: string; description?: string | null; shared?: boolean; step: { type: string; label?: string; config?: Record<string, unknown> } }
      const items = templates.get(ws) ?? []
      if (items.some((t) => t.key === body.key)) return problem(409, 'DUPLICATE_KEY', 'A step template with this key already exists.', { fieldErrors: [{ pointer: '/key', code: 'unique', message: 'Already in use' }] })
      if (['start', 'end'].includes(body.step.type)) return problem(422, 'VALIDATION_FAILED', 'Choose a step other than start or end.', { fieldErrors: [{ pointer: '/step/type', code: 'enum', message: 'Choose a step other than start or end.' }] })
      const config = body.step.config ?? {}
      const operation = ['connectionRef', 'operation', 'topic', 'adapter', 'kind', 'decisionRef', 'skillRef', 'workflowRef', 'templateRef', 'candidateGroupRef']
        .map((m) => config[m]).filter((v): v is string => typeof v === 'string' && v !== '').join(' · ') || null
      const workspace = WORKSPACES.find((w) => w.id === ws)
      const created: StepTemplate = {
        id: crypto.randomUUID(), workspaceId: ws, workspaceKey: workspace?.key ?? 'workspace', key: body.key, name: body.name,
        description: body.description ?? null, stepType: body.step.type, operation, shared: body.shared ?? false, own: true, status: 'active',
        latestVersion: 1, step: { type: body.step.type, label: body.step.label ?? body.step.type, config }, sha256: '0'.repeat(64),
        updatedBy: me(), updatedAt: new Date().toISOString(),
      }
      templates.set(ws, [...items, created])
      return HttpResponse.json(created, { status: 201 })
    }),
  ]
}
