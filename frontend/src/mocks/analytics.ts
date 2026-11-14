import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { currentRelease, toRun } from './engine'
import { getCurrentUser } from './handlers'
import { buildRunPath } from './runPath'
import { WORKSPACES, db, isMember, permissionsOf } from './state'

type S = components['schemas']

/**
 * UI double of the analytics read model (docs/build/S145), mock mode and unit tests only. The heatmap is derived from the
 * live mock runs with the same path derivation as the run diagram; human work comes from the mock tasks; KPI targets
 * live in memory. Same rules as the backend: run.read for figures, workflow.edit to set targets, aggregates only.
 */
const B = '/api/v1/workspaces/:ws'
const targets = new Map<string, S['AnalyticsKpiTargets']>()

const problem = (status: number, code: string) => HttpResponse.json({ type: 'about:blank', title: code, status, code }, { status })

function access(ws: string, permission: string): Response | null {
  const user = getCurrentUser()
  if (!WORKSPACES.some((x) => x.id === ws) || !isMember(user, ws)) return problem(404, 'NOT_FOUND')
  if (!permissionsOf(user, ws).includes(permission as never)) return problem(403, 'FORBIDDEN')
  return null
}

function range(url: URL): S['InsightsRange'] {
  const today = new Date().toISOString().slice(0, 10)
  const to = url.searchParams.get('to') ?? today
  const from = url.searchParams.get('from') ?? new Date(Date.parse(to) - 29 * 86_400_000).toISOString().slice(0, 10)
  return { from, to, days: Math.max(1, Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1), generatedAt: new Date().toISOString() }
}

const freshness = (): S['AnalyticsFreshness'] => ({ lastProjectedAt: new Date().toISOString(), pendingUpdates: 0 })

function percentile(values: number[], p: number): number | null {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const i = (sorted.length - 1) * p
  const lo = Math.floor(i)
  const hi = Math.ceil(i)
  const a = sorted[lo] ?? 0
  const b = sorted[hi] ?? a
  return Math.round(a + (b - a) * (i - lo))
}

function heatmap(ws: string, url: URL): S['AnalyticsHeatmap'] | Response {
  const workflowId = url.searchParams.get('workflowId')
  if (!workflowId) return problem(400, 'MALFORMED_REQUEST')
  const wf = db.workflows.get(workflowId)
  if (!wf || wf.wsId !== ws) return problem(404, 'NOT_FOUND')
  const releaseId = url.searchParams.get('releaseId')
  if (releaseId && !wf.releases.some((r) => r.id === releaseId)) return problem(404, 'NOT_FOUND')
  const r = range(url)
  const user = getCurrentUser()
  const durations = new Map<string, { kind: string; secs: number[]; runs: Set<string> }>()
  const routes = new Map<string, number>()
  let runs = 0
  let finished = 0
  for (const run of db.runs.values()) {
    if (run.wsId !== ws || run.workflowId !== workflowId || (releaseId && run.releaseId !== releaseId)) continue
    const day = run.createdAt.slice(0, 10)
    if (day < r.from || day > r.to) continue
    runs++
    if (run.endedAt) finished++
    const path = buildRunPath({ runId: run.id, lifecycle: run.lifecycle, definition: currentRelease(run).definition, events: run.events, currentSteps: toRun(run, user).currentSteps, attentionNodeId: run.attention?.nodeId ?? null })
    const kinds = new Map(path.diagram.nodes.map((n) => [n.id, n.kind]))
    for (const v of path.visited) {
      if (!v.leftAt) continue
      const entry = durations.get(v.nodeId) ?? { kind: kinds.get(v.nodeId) ?? 'step', secs: [], runs: new Set<string>() }
      entry.secs.push(Math.max(0, Math.round((Date.parse(v.leftAt) - Date.parse(v.enteredAt)) / 1000)))
      entry.runs.add(run.id)
      durations.set(v.nodeId, entry)
    }
    for (const t of new Set(path.taken)) routes.set(t, (routes.get(t) ?? 0) + 1)
  }
  return {
    range: r, workflowId, releaseId: releaseId ?? null, runs, finishedRuns: finished,
    steps: [...durations.entries()].map(([nodeId, d]) => ({
      nodeId, kind: d.kind, visits: d.secs.length, runs: d.runs.size,
      averageSeconds: Math.round(d.secs.reduce((a, b) => a + b, 0) / d.secs.length), p95Seconds: percentile(d.secs, 0.95), maxSeconds: Math.max(...d.secs),
    })),
    routes: [...routes.entries()].map(([transitionId, n]) => ({ transitionId, runs: n, share: runs ? Math.round((n / runs) * 10000) / 10000 : 0 })),
    freshness: freshness(),
  }
}

function humanWork(ws: string, url: URL): S['AnalyticsHumanWork'] {
  const workflowId = url.searchParams.get('workflowId')
  const groups = new Map<string, S['AnalyticsHumanGroup']>()
  const now = Date.now()
  for (const run of db.runs.values()) {
    if (run.wsId !== ws || (workflowId && run.workflowId !== workflowId)) continue
    for (const task of run.tasks) {
      if (task.status !== 'open') continue
      const key = task.candidateGroup
      const g = groups.get(key) ?? { groupKey: key, groupName: null, openNow: 0, openUnassigned: 0, oldestOpenSeconds: null, finished: 0, averageWaitingSeconds: null, averageWorkingSeconds: null, slaMet: 0, slaMissed: 0, slaAttainment: null, reassignments: 0 }
      g.openNow++
      if (!task.assigneeId) g.openUnassigned++
      g.oldestOpenSeconds = Math.max(g.oldestOpenSeconds ?? 0, Math.round((now - Date.parse(task.createdAt)) / 1000))
      groups.set(key, g)
    }
  }
  return { range: range(url), steps: [], groups: [...groups.values()], truncated: false, freshness: freshness() }
}

function emptyTargets(workflowId: string): S['AnalyticsKpiTargets'] {
  return { workflowId, cycleP50Seconds: null, cycleP95Seconds: null, automationRate: null, slaAttainment: null, delayCostPerHour: null, currency: null, version: 0, updatedAt: null }
}

export function createAnalyticsHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/analytics/heatmap`, ({ params, request }) => {
      const denied = access(String(params.ws), 'run.read')
      if (denied) return denied
      const result = heatmap(String(params.ws), new URL(request.url))
      return result instanceof Response ? result : HttpResponse.json(result)
    }),
    http.get(`${B}/analytics/human-work`, ({ params, request }) => access(String(params.ws), 'run.read') ?? HttpResponse.json(humanWork(String(params.ws), new URL(request.url)))),
    http.get(`${B}/analytics/kpis`, ({ params, request }) => {
      const ws = String(params.ws)
      const denied = access(ws, 'run.read')
      if (denied) return denied
      const items: S['AnalyticsKpiWorkflow'][] = []
      for (const t of targets.values()) {
        const wf = db.workflows.get(t.workflowId)
        if (!wf || wf.wsId !== ws) continue
        const measures: S['AnalyticsKpiMeasure'][] = []
        if (t.cycleP95Seconds !== null) measures.push({ measure: 'cycleP95', target: t.cycleP95Seconds, actual: null, samples: 0, status: 'insufficientData', gap: null })
        if (t.cycleP50Seconds !== null) measures.push({ measure: 'cycleP50', target: t.cycleP50Seconds, actual: null, samples: 0, status: 'insufficientData', gap: null })
        if (t.automationRate !== null) measures.push({ measure: 'automationRate', target: t.automationRate, actual: null, samples: 0, status: 'insufficientData', gap: null })
        if (t.slaAttainment !== null) measures.push({ measure: 'slaAttainment', target: t.slaAttainment, actual: null, samples: 0, status: 'insufficientData', gap: null })
        items.push({ workflow: { id: wf.id, key: wf.key, name: wf.name }, targets: t, measures, offTarget: 0, completedRuns: 0, excessHours: 0, delayCost: t.delayCostPerHour === null ? null : 0, currency: t.currency })
      }
      return HttpResponse.json({ range: range(new URL(request.url)), items, freshness: freshness() } satisfies S['AnalyticsKpiOverview'])
    }),
    http.get(`${B}/analytics/status`, ({ params }) => access(String(params.ws), 'run.read')
      ?? HttpResponse.json({ pending: 0, failed: 0, oldestPendingAt: null, lastProjectedAt: new Date().toISOString(), projectedRuns: db.runs.size } satisfies S['AnalyticsStatus'])),
    http.get(`${B}/workflows/:wf/kpi-targets`, ({ params }) => access(String(params.ws), 'workflow.read')
      ?? HttpResponse.json(targets.get(String(params.wf)) ?? emptyTargets(String(params.wf)))),
    http.put(`${B}/workflows/:wf/kpi-targets`, async ({ params, request }) => {
      const denied = access(String(params.ws), 'workflow.edit')
      if (denied) return denied
      const wfId = String(params.wf)
      const body = (await request.json()) as S['AnalyticsKpiTargetsUpdate']
      const current = targets.get(wfId) ?? emptyTargets(wfId)
      if (body.expectedVersion !== current.version) return problem(409, 'CONFIGURATION_VERSION_CONFLICT')
      const next: S['AnalyticsKpiTargets'] = {
        workflowId: wfId, cycleP50Seconds: body.cycleP50Seconds ?? null, cycleP95Seconds: body.cycleP95Seconds ?? null,
        automationRate: body.automationRate ?? null, slaAttainment: body.slaAttainment ?? null, delayCostPerHour: body.delayCostPerHour ?? null,
        currency: body.delayCostPerHour == null ? null : (body.currency ?? null), version: current.version + 1, updatedAt: new Date().toISOString(),
      }
      const empty = [next.cycleP50Seconds, next.cycleP95Seconds, next.automationRate, next.slaAttainment, next.delayCostPerHour].every((v) => v === null)
      if (empty) targets.delete(wfId)
      else targets.set(wfId, next)
      return HttpResponse.json(empty ? emptyTargets(wfId) : next)
    }),
  ]
}
