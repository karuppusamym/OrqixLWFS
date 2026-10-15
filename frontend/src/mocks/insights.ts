import { delay, http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { isRunParticipant, currentRelease, toRun } from './engine'
import { getCurrentUser } from './handlers'
import { buildRunPath } from './runPath'
import { WORKSPACES, db, isMember, newId, permissionsOf, type MockRun } from './state'

type S = components['schemas']

/**
 * Mock of getRunPath and the Insights endpoints. Insights are computed from the live mock runs plus a deterministic
 * synthetic history (about three months of closed purchase requests for the Purchase approval workflow), so the
 * dashboard has something realistic to show. The history is a pure function of today's date: nothing is stored or
 * listed anywhere else (the Runs page shows only real mock runs). Same rules as the backend: run.read only, aggregates
 * only (no titles or data), range at most 365 days, unknown workflow is 404.
 */
const B = '/api/v1'

function problem(status: number, code: string, title: string, detail?: string): Response {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...(detail ? { detail } : {}) },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

// Facts ----------------------------------------------------------------------------------------------------

interface StepFact {
  nodeId: string
  label: string
  kind: 'approval' | 'wait'
  startedAt: number
  /** Seconds waited; null while the request is still at the step. */
  waited: number | null
  timedOut: boolean
  dueAt: number | null
}

interface RunFact {
  runId: string
  reference: string
  workflow: S['InsightsWorkflow']
  createdAt: number
  endedAt: number | null
  lifecycle: S['Lifecycle']
  outcomeKind: S['OutcomeKind'] | null
  attention: { reason: S['InsightsAttentionRun']['reason']; since: number } | null
  stepLabel: string | null
  steps: StepFact[]
}

function liveFact(run: MockRun): RunFact {
  const wf = db.workflows.get(run.workflowId)
  const steps: StepFact[] = []
  const openAt = new Map<string, { at: number; kind: StepFact['kind']; label: string }>()
  const labelOf = (id: string) => currentRelease(run).definition.nodes.find((n) => n.id === id)?.label ?? id
  const closeStep = (id: string, at: number, timedOut: boolean) => {
    const o = openAt.get(id)
    if (!o) return
    openAt.delete(id)
    steps.push({ nodeId: id, label: o.label, kind: o.kind, startedAt: o.at, waited: Math.max(0, (at - o.at) / 1000), timedOut, dueAt: null })
  }
  for (const e of run.events) {
    const at = Date.parse(e.occurredAt)
    if (!e.nodeId) continue
    if (e.type === 'TASK_CREATED') openAt.set(e.nodeId, { at, kind: 'approval', label: labelOf(e.nodeId) })
    else if (e.type === 'WAIT_STARTED') openAt.set(e.nodeId, { at, kind: 'wait', label: labelOf(e.nodeId) })
    else if (e.type === 'TASK_DECIDED' || e.type === 'WAIT_ENDED') closeStep(e.nodeId, at, e.type === 'WAIT_ENDED' && e.data.outcome === 'timedOut')
    else if (e.type === 'TASK_TIMED_OUT') closeStep(e.nodeId, at, true)
  }
  const isOpen = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
  if (isOpen) {
    for (const [id, o] of openAt) {
      const due = o.kind === 'approval' ? run.tasks.find((t) => t.nodeId === id && t.status === 'open')?.dueAt : run.waits?.[id]?.dueAt
      steps.push({ nodeId: id, label: o.label, kind: o.kind, startedAt: o.at, waited: null, timedOut: false, dueAt: due ? Date.parse(due) : null })
    }
  }
  return {
    runId: run.id,
    reference: run.reference ?? run.id.slice(0, 8),
    workflow: { id: run.workflowId, key: wf?.key ?? 'workflow', name: wf?.name ?? 'Workflow' },
    createdAt: Date.parse(run.createdAt),
    endedAt: run.endedAt ? Date.parse(run.endedAt) : null,
    lifecycle: run.lifecycle,
    outcomeKind: run.outcomeKind,
    attention: run.attention ? { reason: run.attention.reason as S['InsightsAttentionRun']['reason'], since: Date.parse(run.attention.since) } : null,
    stepLabel: isOpen ? (steps.find((s) => s.waited === null)?.label ?? null) : null,
    steps,
  }
}

function mulberry32(seed: number): () => number {
  let a = seed
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const HOUR = 3_600_000

/** About three months of closed requests, the same shape every day (seeded by the day number). */
function historyFacts(wsId: string, now: number): RunFact[] {
  const wf = [...db.workflows.values()].find((w) => w.wsId === wsId && w.key === 'purchase-approval')
  if (!wf) return []
  const ref = { id: wf.id, key: wf.key, name: wf.name }
  const facts: RunFact[] = []
  const today = Math.floor(now / (24 * HOUR))
  for (let back = 1; back <= 100; back++) {
    const day = today - back
    const rand = mulberry32(day * 7919 + 13)
    const weekday = new Date(day * 24 * HOUR).getUTCDay()
    const count = weekday === 0 || weekday === 6 ? Math.floor(rand() * 2) : 3 + Math.floor(rand() * 6)
    for (let i = 0; i < count; i++) {
      const createdAt = day * 24 * HOUR + (8 + rand() * 9) * HOUR
      const steps: StepFact[] = []
      // Manager review: usually a few hours, now and then a day or more.
      const slow = rand() < 0.12
      const managerWait = (slow ? 20 + rand() * 40 : 0.5 + rand() * rand() * 9) * HOUR
      steps.push({ nodeId: 'manager', label: 'Review request', kind: 'approval', startedAt: createdAt, waited: managerWait / 1000, timedOut: false, dueAt: null })
      let end = createdAt + managerWait
      let outcomeKind: RunFact['outcomeKind'] = 'success'
      let lifecycle: S['Lifecycle'] = 'completed'
      if (rand() < 0.02) {
        lifecycle = 'cancelled'
        outcomeKind = null
      } else if (rand() < 0.16) {
        outcomeKind = 'rejected'
      } else if (rand() < 0.35) {
        const timedOut = rand() < 0.07
        const financeWait = (timedOut ? 72 : 4 + rand() * rand() * 60) * HOUR
        steps.push({ nodeId: 'finance', label: 'Finance review', kind: 'approval', startedAt: end, waited: financeWait / 1000, timedOut, dueAt: null })
        end += financeWait
        if (rand() < 0.12) outcomeKind = 'rejected'
      }
      end += (5 + rand() * 20) * 1000
      facts.push({ runId: `history-${day}-${i}`, reference: `ORQ-H${day}-${i}`, workflow: ref, createdAt, endedAt: end, lifecycle, outcomeKind, attention: null, stepLabel: null, steps })
    }
  }
  return facts
}

function allFacts(wsId: string, now: number): RunFact[] {
  const live = [...db.runs.values()].filter((r) => r.wsId === wsId).map(liveFact)
  return [...historyFacts(wsId, now), ...live]
}

// Aggregation ---------------------------------------------------------------------------------------------

function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0
  const pos = (sorted.length - 1) * p
  const lo = Math.floor(pos)
  const hi = Math.ceil(pos)
  return (sorted[lo] as number) + ((sorted[hi] as number) - (sorted[lo] as number)) * (pos - lo)
}

const day = (ms: number) => new Date(ms).toISOString().slice(0, 10)
const isOpen = (f: RunFact) => f.lifecycle === 'running' || f.lifecycle === 'waiting' || f.lifecycle === 'needsAttention'

interface Window {
  facts: RunFact[]
  from: number
  to: number
  fromDay: string
  toDay: string
  now: number
}

function parseWindow(url: URL, wsId: string): Window | Response {
  const now = Date.now()
  const param = (name: string) => url.searchParams.get(name)
  const parse = (value: string | null, fallback: string, name: string): string | Response => {
    if (!value) return fallback
    return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) ? value : problem(400, 'MALFORMED_REQUEST', 'Malformed request', `${name} must be a date such as 2026-10-01.`)
  }
  const to = parse(param('to'), day(now), 'to')
  if (typeof to !== 'string') return to
  const from = parse(param('from'), day(Date.parse(to) - 29 * 24 * HOUR), 'from')
  if (typeof from !== 'string') return from
  const days = Math.round((Date.parse(to) - Date.parse(from)) / (24 * HOUR)) + 1
  if (days < 1) return problem(400, 'MALFORMED_REQUEST', 'Malformed request', 'from must not be after to.')
  if (days > 365) return problem(400, 'MALFORMED_REQUEST', 'Malformed request', 'The date range may be at most 365 days.')
  const workflowId = param('workflowId')
  if (workflowId) {
    const wf = db.workflows.get(workflowId)
    if (!wf || wf.wsId !== wsId) return problem(404, 'NOT_FOUND', 'Not found')
  }
  let facts = allFacts(wsId, now)
  if (workflowId) facts = facts.filter((f) => f.workflow.id === workflowId)
  return { facts, from: Date.parse(from), to: Date.parse(to) + 24 * HOUR, fromDay: from, toDay: to, now }
}

function range(w: Window): S['InsightsRange'] {
  return { from: w.fromDay, to: w.toDay, days: Math.round((w.to - w.from) / (24 * HOUR)), generatedAt: new Date(w.now).toISOString() }
}

function stepRows(w: Window): S['InsightsStep'][] {
  const groups = new Map<string, { wf: S['InsightsWorkflow']; step: StepFact; waits: number[]; timeouts: number; open: number; label: string }>()
  const keyOf = (f: RunFact, s: StepFact) => `${f.workflow.id}|${s.kind}|${s.nodeId}`
  for (const f of w.facts) {
    for (const s of f.steps) {
      const g = groups.get(keyOf(f, s)) ?? { wf: f.workflow, step: s, waits: [], timeouts: 0, open: 0, label: s.label }
      groups.set(keyOf(f, s), g)
    }
  }
  // Open steps count whatever their age; finished steps only when they started inside the window.
  for (const f of w.facts) {
    for (const s of f.steps) {
      const g = groups.get(keyOf(f, s)) as NonNullable<ReturnType<typeof groups.get>>
      if (s.waited === null) g.open += 1
      else if (s.startedAt >= w.from && s.startedAt < w.to) {
        g.waits.push(s.waited)
        if (s.timedOut) g.timeouts += 1
      }
    }
  }
  return [...groups.values()]
    .filter((g) => g.waits.length > 0 || g.open > 0)
    .map((g) => {
      const sorted = [...g.waits].sort((a, b) => a - b)
      return {
        workflow: g.wf,
        nodeId: g.step.nodeId,
        label: g.label,
        kind: g.step.kind,
        count: sorted.length,
        openNow: g.open,
        averageWaitSeconds: sorted.length ? Math.round(sorted.reduce((a, b) => a + b, 0) / sorted.length) : null,
        p90WaitSeconds: sorted.length ? Math.round(percentile(sorted, 0.9)) : null,
        timeouts: g.timeouts,
        timeoutShare: sorted.length ? Math.round((g.timeouts / sorted.length) * 10000) / 10000 : 0,
      }
    })
    .sort((a, b) => (b.averageWaitSeconds ?? -1) - (a.averageWaitSeconds ?? -1) || b.openNow - a.openNow || a.label.localeCompare(b.label))
}

function summary(w: Window): S['InsightsSummary'] {
  const cohort = w.facts.filter((f) => f.createdAt >= w.from && f.createdAt < w.to)
  const completed = cohort.filter((f) => f.lifecycle === 'completed')
  const totals: S['InsightsTotals'] = {
    started: cohort.length,
    completed: completed.length,
    fulfilled: completed.filter((f) => f.outcomeKind === 'success').length,
    rejected: completed.filter((f) => f.outcomeKind === 'rejected').length,
    cancelled: cohort.filter((f) => f.lifecycle === 'cancelled').length,
    open: cohort.filter(isOpen).length,
    needsAttention: cohort.filter((f) => f.lifecycle === 'needsAttention').length,
  }
  const byWorkflow = new Map<string, { wf: S['InsightsWorkflow']; secs: number[] }>()
  for (const f of completed) {
    const g = byWorkflow.get(f.workflow.id) ?? { wf: f.workflow, secs: [] }
    g.secs.push(((f.endedAt ?? f.createdAt) - f.createdAt) / 1000)
    byWorkflow.set(f.workflow.id, g)
  }
  const cycleTimes = [...byWorkflow.values()]
    .map((g) => {
      const sorted = g.secs.sort((a, b) => a - b)
      return { workflow: g.wf, completed: sorted.length, medianSeconds: Math.round(percentile(sorted, 0.5)), p90Seconds: Math.round(percentile(sorted, 0.9)) }
    })
    .sort((a, b) => b.completed - a.completed)
  const open = w.facts.filter(isOpen)
  const oldestOpen = [...open]
    .sort((a, b) => a.createdAt - b.createdAt)
    .slice(0, 10)
    .map((f) => ({ runId: f.runId, reference: f.reference, workflow: f.workflow, lifecycle: f.lifecycle, startedAt: new Date(f.createdAt).toISOString(), ageSeconds: Math.max(0, Math.round((w.now - f.createdAt) / 1000)), stepLabel: f.stepLabel }))
  const needsAttention = w.facts
    .filter((f) => f.lifecycle === 'needsAttention' && f.attention)
    .sort((a, b) => (b.attention?.since ?? 0) - (a.attention?.since ?? 0))
    .slice(0, 10)
    .map((f) => ({ runId: f.runId, reference: f.reference, workflow: f.workflow, reason: (f.attention as NonNullable<RunFact['attention']>).reason, since: new Date((f.attention as NonNullable<RunFact['attention']>).since).toISOString(), stepLabel: f.stepLabel }))
  const timeouts = stepRows(w)
    .filter((r) => r.timeouts > 0)
    .map((r) => ({ workflow: r.workflow, nodeId: r.nodeId, label: r.label, timeouts: r.timeouts, decided: r.count, share: r.timeoutShare }))
    .sort((a, b) => b.share - a.share)
    .slice(0, 50)
  const overdueAll = open.flatMap((f) =>
    f.steps
      .filter((s) => s.waited === null && s.dueAt !== null && s.dueAt < w.now)
      .map((s) => ({ runId: f.runId, reference: f.reference, workflow: f.workflow, kind: s.kind, stepLabel: s.label, dueAt: new Date(s.dueAt as number).toISOString(), overdueSeconds: Math.max(1, Math.round((w.now - (s.dueAt as number)) / 1000)) })),
  )
  overdueAll.sort((a, b) => b.overdueSeconds - a.overdueSeconds)
  return { range: range(w), totals, cycleTimes, oldestOpen, needsAttention, sla: { timeouts, overdue: overdueAll.slice(0, 20), overdueCount: overdueAll.length } }
}

function throughput(w: Window): S['InsightsThroughput'] {
  const days = new Map<string, S['InsightsDay']>()
  for (let t = w.from; t < w.to; t += 24 * HOUR) days.set(day(t), { date: day(t), started: 0, completed: 0, fulfilled: 0, rejected: 0 })
  for (const f of w.facts) {
    if (f.createdAt >= w.from && f.createdAt < w.to) (days.get(day(f.createdAt)) as S['InsightsDay']).started += 1
    if (f.lifecycle === 'completed' && f.endedAt !== null && f.endedAt >= w.from && f.endedAt < w.to) {
      const d = days.get(day(f.endedAt)) as S['InsightsDay']
      d.completed += 1
      if (f.outcomeKind === 'success') d.fulfilled += 1
      else if (f.outcomeKind === 'rejected') d.rejected += 1
    }
  }
  return { range: range(w), days: [...days.values()] }
}

// Handlers -------------------------------------------------------------------------------------------------

export function createInsightsHandlers(): HttpHandler[] {
  const slow = async () => {
    if (db.latencyMs > 0) await delay(db.latencyMs)
  }
  const insights = (name: 'summary' | 'throughput' | 'steps') =>
    http.get(`${B}/workspaces/:ws/insights/${name}`, async ({ params, request }) => {
      await slow()
      const ws = String(params.ws)
      const user = getCurrentUser()
      if (!WORKSPACES.some((x) => x.id === ws) || !isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissionsOf(user, ws).includes('run.read')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const w = parseWindow(new URL(request.url), ws)
      if (w instanceof Response) return w
      if (name === 'summary') return HttpResponse.json(summary(w))
      if (name === 'throughput') return HttpResponse.json(throughput(w))
      const rows = stepRows(w)
      return HttpResponse.json({ range: range(w), items: rows.slice(0, 200), truncated: rows.length > 200 } satisfies S['InsightsSteps'])
    })
  return [
    http.get(`${B}/workspaces/:ws/runs/:id/path`, async ({ params }) => {
      await slow()
      const ws = String(params.ws)
      const user = getCurrentUser()
      if (!WORKSPACES.some((x) => x.id === ws) || !isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      const run = db.runs.get(String(params.id))
      if (!run || run.wsId !== ws || !(permissionsOf(user, ws).includes('run.read') || run.initiatorId === user.id || isRunParticipant(run, user))) return problem(404, 'NOT_FOUND', 'Not found')
      return HttpResponse.json(
        buildRunPath({ runId: run.id, lifecycle: run.lifecycle, definition: currentRelease(run).definition, events: run.events, currentSteps: toRun(run, user).currentSteps, attentionNodeId: run.attention?.nodeId ?? null }),
      )
    }),
    insights('summary'),
    insights('throughput'),
    insights('steps'),
  ]
}
