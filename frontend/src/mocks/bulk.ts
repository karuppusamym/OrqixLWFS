import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { cancelRun, migrateRunTo, resumeRun, retryRun, suspendRun } from './engine'
import { matchesCondition } from './handlers'
import { db, isMember, permissionsOf, WORKSPACES, type MockRun, type MockUser } from './state'

/**
 * S142 mock backend: searchable request fields, bulk operations by filter and the job dashboard (CG-O04, CG-O01,
 * CG-O05) for mock mode and unit tests. A dry run stores the matching runs; a running operation settles one item each
 * time it is read, so progress is visible without timers.
 */
const B = '*/api/v1/workspaces/:ws'

type Operation = components['schemas']['BulkOperation']
type Item = components['schemas']['BulkOperationItem']
type Filter = components['schemas']['BulkRunFilter']

interface Stored {
  op: Operation
  wsId: string
  items: Item[]
  creatorId: string
}

const operations = new Map<string, Stored>()
let dashboard: components['schemas']['JobDashboard'] | null = null
let cap = 1000

export function resetBulkMock() {
  operations.clear()
  dashboard = null
  cap = 1000
}

export function setBulkCap(value: number) {
  cap = value
}

export function seedJobDashboard(value: components['schemas']['JobDashboard']) {
  dashboard = value
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

function eligible(run: MockRun, action: Operation['action'], target?: string): boolean {
  const open = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
  switch (action) {
    case 'retry': return run.lifecycle === 'needsAttention'
    case 'cancel': return open
    case 'suspend': return open && !run.suspension
    case 'resume': return !!run.suspension
    case 'migrate': return open && !!target && run.releaseId !== target
  }
}

function matches(run: MockRun, f: Filter): boolean {
  if (f.workflowId && run.workflowId !== f.workflowId) return false
  if (f.lifecycle && run.lifecycle !== f.lifecycle) return false
  if (f.businessKey && run.businessKey !== f.businessKey && run.reference !== f.businessKey) return false
  if (f.errorCode && (run.attention as { errorCode?: string } | null)?.errorCode !== f.errorCode) return false
  return (f.fields ?? []).every((c) => matchesCondition(run.input, c))
}

export function createBulkHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const has = (ws: string, ...wanted: string[]) => wanted.every((p) => (permissionsOf(currentUser(), ws) as string[]).includes(p))
  const refused = (ws: string, ...wanted: string[]) =>
    !visible(ws) ? problem(404, 'NOT_FOUND', 'Not found') : !has(ws, ...wanted) ? problem(403, 'FORBIDDEN', 'You are not allowed to do this') : null
  const me = () => ({ id: currentUser().id, displayName: currentUser().displayName })
  const view = (s: Stored, withSample = true): Operation => {
    const count = (state: Item['state']) => s.items.filter((i) => i.state === state).length
    return {
      ...s.op,
      progress: { total: s.items.length, pending: count('pending'), succeeded: count('succeeded'), failed: count('failed'), skipped: count('skipped'), cancelled: count('cancelled') },
      sample: withSample ? s.items.slice(0, 10) : [],
    }
  }
  const step = (s: Stored) => {
    if (s.op.state !== 'running') return
    const item = s.items.find((i) => i.state === 'pending')
    if (!item) {
      s.op = { ...s.op, state: 'completed', finishedAt: new Date().toISOString(), version: s.op.version + 1 }
      return
    }
    const run = db.runs.get(item.runId)
    item.finishedAt = new Date().toISOString()
    if (!run || !matches(run, s.op.filter) || !eligible(run, s.op.action, s.op.targetRelease?.id)) {
      item.state = 'skipped'
      item.errorCode = 'NO_LONGER_MATCHES'
      item.message = 'The request no longer matches the filter or the action’s state rule.'
      return
    }
    const reason = `${s.op.reason ?? ''} (bulk operation ${s.op.id.slice(0, 8)})`
    if (s.op.action === 'cancel') cancelRun(run, currentUser(), reason)
    else if (s.op.action === 'suspend') suspendRun(run, currentUser(), reason)
    else if (s.op.action === 'resume') resumeRun(run, currentUser(), reason)
    else if (s.op.action === 'retry') retryRun(run, currentUser(), reason)
    else migrateRunTo(run, currentUser(), s.op.targetRelease!.id, reason)
    item.state = 'succeeded'
    if (!s.items.some((i) => i.state === 'pending')) s.op = { ...s.op, state: 'completed', finishedAt: new Date().toISOString(), version: s.op.version + 1 }
  }
  const find = (ws: string, id: string) => {
    const s = operations.get(id)
    return s && s.wsId === ws ? s : null
  }
  return [
    http.get(`${B}/workflows/:wf/searchable-fields`, ({ params }) => {
      const ws = String(params.ws)
      const no = refused(ws, 'run.read')
      if (no) return no
      const wf = db.workflows.get(String(params.wf))
      if (!wf || wf.wsId !== ws) return problem(404, 'NOT_FOUND', 'Not found')
      const release = wf.releases.find((r) => r.id === wf.activeReleaseId) ?? wf.releases.at(-1)
      const props = (release?.definition.inputSchema.properties ?? {}) as unknown as Record<string, Record<string, unknown>>
      const items = Object.entries(props)
        .filter(([, f]) => f.searchable === true)
        .slice(0, 10)
        .map(([key, f]) => {
          const kind = f.type === 'number' || f.type === 'integer' || f.type === 'boolean' ? f.type : f.format === 'date' ? 'date' : Array.isArray(f.enum) ? 'choice' : 'text'
          return { key, title: String(f.title ?? key), kind: kind as components['schemas']['SearchableField']['kind'], options: Array.isArray(f.enum) ? (f.enum as string[]) : [] }
        })
      return HttpResponse.json({ items })
    }),
    http.post(`${B}/bulk-operations/preview`, async ({ params, request }) => {
      const ws = String(params.ws)
      const no = refused(ws, 'run.operate', 'run.bulk-operate')
      if (no) return no
      const body = (await request.json()) as components['schemas']['BulkOperationPreviewRequest']
      const f = body.filter
      if (!(f.workflowId || f.businessKey || f.createdFrom || f.createdTo || f.overdue || f.attentionReason || f.errorCode || f.fields?.length))
        return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { detail: 'Narrow the runs.', fieldErrors: [{ pointer: '/filter', code: 'required', message: 'Narrow the runs.' }] })
      const matched = [...db.runs.values()]
        .filter((r) => r.wsId === ws && matches(r, f) && eligible(r, body.action, body.targetReleaseId))
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      const now = new Date()
      const id = crypto.randomUUID()
      const target = body.targetReleaseId ? db.workflows.get(f.workflowId ?? '')?.releases.find((r) => r.id === body.targetReleaseId) : undefined
      const stored: Stored = {
        wsId: ws,
        creatorId: currentUser().id,
        items: matched.length <= cap ? matched.map((r, i) => ({ runId: r.id, reference: r.reference ?? r.id, position: i + 1, state: 'pending', errorCode: null, message: null, finishedAt: null })) : [],
        op: {
          id, action: body.action, state: 'previewed', filter: f,
          targetRelease: target ? { id: target.id, releaseNumber: target.releaseNumber } : null,
          matchedCount: matched.length, cap, exceedsCap: matched.length > cap, ratePerMinute: body.ratePerMinute ?? 60,
          reason: null, stopReason: null, createdBy: me(), createdAt: now.toISOString(),
          previewExpiresAt: new Date(now.getTime() + 15 * 60_000).toISOString(), startedAt: null, finishedAt: null,
          progress: { total: 0, pending: 0, succeeded: 0, failed: 0, skipped: 0, cancelled: 0 }, sample: [], version: 1,
        },
      }
      operations.set(id, stored)
      return HttpResponse.json(view(stored), { status: 201 })
    }),
    http.get(`${B}/bulk-operations`, ({ params }) => {
      const ws = String(params.ws)
      const no = refused(ws, 'run.operate')
      if (no) return no
      const items = [...operations.values()].filter((s) => s.wsId === ws).map((s) => view(s, false)).reverse()
      return HttpResponse.json({ items, nextCursor: null })
    }),
    http.get(`${B}/bulk-operations/:id`, ({ params }) => {
      const ws = String(params.ws)
      const s = find(ws, String(params.id))
      const no = !s ? problem(404, 'NOT_FOUND', 'Not found') : refused(ws, 'run.operate')
      if (no || !s) return no ?? problem(404, 'NOT_FOUND', 'Not found')
      step(s)
      return HttpResponse.json(view(s))
    }),
    http.get(`${B}/bulk-operations/:id/items`, ({ params, request }) => {
      const ws = String(params.ws)
      const s = find(ws, String(params.id))
      const no = !s ? problem(404, 'NOT_FOUND', 'Not found') : refused(ws, 'run.operate')
      if (no || !s) return no ?? problem(404, 'NOT_FOUND', 'Not found')
      const state = new URL(request.url).searchParams.get('state')
      return HttpResponse.json({ items: s.items.filter((i) => !state || i.state === state), nextCursor: null })
    }),
    http.post(`${B}/bulk-operations/:id/execute`, async ({ params, request }) => {
      const ws = String(params.ws)
      const s = find(ws, String(params.id))
      const no = !s ? problem(404, 'NOT_FOUND', 'Not found') : refused(ws, 'run.operate', 'run.bulk-operate')
      if (no || !s) return no ?? problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as components['schemas']['BulkOperationExecuteRequest']
      if (s.creatorId !== currentUser().id) return problem(403, 'FORBIDDEN', 'Only the person who ran the dry run can confirm it')
      if (s.op.state !== 'previewed') return problem(409, 'BULK_OPERATION_STATE_CONFLICT', 'The bulk operation is not in a state that allows this')
      if (s.op.exceedsCap) return problem(422, 'BULK_LIMIT_EXCEEDED', 'The filter matches more requests than one bulk operation may change')
      if (body.confirmedCount !== s.op.matchedCount) return problem(409, 'BULK_COUNT_MISMATCH', 'The confirmed count does not match the dry run', { matchedCount: s.op.matchedCount })
      if (!body.reason.trim()) return problem(422, 'REASON_REQUIRED', 'A reason is required')
      s.op = { ...s.op, state: 'running', reason: body.reason, startedAt: new Date().toISOString(), version: s.op.version + 1 }
      return HttpResponse.json(view(s))
    }),
    ...(['pause', 'resume', 'cancel'] as const).map((action) =>
      http.post(`${B}/bulk-operations/:id/${action}`, async ({ params, request }) => {
        const ws = String(params.ws)
        const s = find(ws, String(params.id))
        const no = !s ? problem(404, 'NOT_FOUND', 'Not found') : refused(ws, 'run.operate', 'run.bulk-operate')
        if (no || !s) return no ?? problem(404, 'NOT_FOUND', 'Not found')
        const body = (await request.json()) as components['schemas']['BulkOperationControlRequest']
        const allowed = action === 'pause' ? s.op.state === 'running' : action === 'resume' ? s.op.state === 'paused' : s.op.state !== 'completed' && s.op.state !== 'cancelled'
        if (body.expectedVersion !== s.op.version || !allowed) return problem(409, 'BULK_OPERATION_STATE_CONFLICT', 'The bulk operation is not in a state that allows this')
        if (action === 'cancel') {
          for (const i of s.items) if (i.state === 'pending') Object.assign(i, { state: 'cancelled', errorCode: 'CANCELLED_BY_OPERATOR', message: 'The bulk operation was cancelled.', finishedAt: new Date().toISOString() })
        }
        const state = action === 'pause' ? 'paused' : action === 'resume' ? 'running' : 'cancelled'
        s.op = { ...s.op, state, version: s.op.version + 1, ...(action === 'cancel' ? { finishedAt: new Date().toISOString() } : {}) }
        return HttpResponse.json(view(s))
      }),
    ),
    http.get(`${B}/job-dashboard`, ({ params }) => {
      const ws = String(params.ws)
      const no = refused(ws, 'run.operate')
      if (no) return no
      const base = dashboard ?? { jobQueues: [], pollChecks: [], packageTriggers: [], asOf: new Date().toISOString() }
      const { connections, ...rest } = base
      return HttpResponse.json(has(ws, 'connection.read') && connections ? { ...rest, connections } : rest)
    }),
  ]
}
