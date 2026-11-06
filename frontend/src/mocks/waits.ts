import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { toRun } from './engine'
import { getCurrentUser } from './handlers'
import { db } from './state'

type S = components['schemas']

/**
 * UI double of the "Waiting for" API (docs/build/S129, CG-W08), mock mode and unit tests only. The waits are derived
 * from the mock run's current steps the same way the backend derives its projection. The browser mock runs a
 * "Keep checking until..." step as one call, so it never lists checks; the Test tab simulator shows check sequences.
 */
const B = '/api/v1/workspaces/:ws'

const quote = (t: string) => `“${t}”`

function waitsOf(runId: string): S['RunWait'][] {
  const run = db.runs.get(runId)
  if (!run) return []
  const steps = toRun(run, getCurrentUser()).currentSteps
  const seen = new Set<string>()
  const out: S['RunWait'][] = []
  for (const step of steps) {
    if (seen.has(step.nodeId)) continue
    const base = { nodeId: step.nodeId, label: step.label, since: step.since, nextCheckAt: null, dueAt: null, deadlineAt: null, attempts: 0, correlationRef: null, connectionKey: null, check: null }
    let wait: S['RunWait'] | null = null
    if (step.kind === 'approval') wait = { ...base, kind: 'approval', subject: `A decision on ${quote(step.label)}${step.candidateGroup ? ` by ${step.candidateGroup}` : ''}`, dueAt: step.dueAt ?? null }
    else if (step.kind === 'wait' && step.messageName) wait = { ...base, kind: 'message', subject: `The message ${quote(step.messageName)} for ${quote(step.label)}`, deadlineAt: step.dueAt ?? null, correlationRef: step.messageName }
    else if (step.kind === 'wait') wait = { ...base, kind: 'timer', subject: `The time set on ${quote(step.label)}`, dueAt: step.dueAt ?? null }
    else if (step.kind === 'external') wait = { ...base, kind: 'externalJob', subject: `An outside worker to finish ${quote(step.label)}` }
    else if (step.kind === 'child') wait = { ...base, kind: 'childRequest', subject: `Another request started by ${quote(step.label)} to finish` }
    else if (step.kind === 'waitForSignal') wait = { ...base, kind: 'signal', subject: `A signal for ${quote(step.label)}` }
    if (wait) {
      seen.add(step.nodeId)
      out.push(wait)
    }
  }
  return out
}

export function createWaitHandlers(): HttpHandler[] {
  const missing = () => HttpResponse.json({ type: 'about:blank', title: 'Not found', status: 404, code: 'NOT_FOUND' }, { status: 404 })
  return [
    http.get(`${B}/runs/:runId/waits`, ({ params }) => {
      if (!db.runs.has(String(params.runId))) return missing()
      return HttpResponse.json({ items: waitsOf(String(params.runId)), endedChecks: [] } satisfies S['RunWaitList'])
    }),
    http.get(`${B}/waits`, () => HttpResponse.json({ items: [] } satisfies S['WaitSearchResult'])),
    http.get(`${B}/insights/outside-waits`, ({ params, request }) => {
      // Waits on outside systems from the mock runs' steps: messages and outside workers (the mock has no checks).
      const url = new URL(request.url)
      const today = new Date().toISOString().slice(0, 10)
      const from = url.searchParams.get('from') ?? today
      const to = url.searchParams.get('to') ?? today
      const now = Date.now()
      const rows = new Map<string, S['InsightsOutsideWait']>()
      for (const run of db.runs.values()) {
        if (run.wsId !== String(params.ws)) continue
        for (const w of waitsOf(run.id)) {
          if (w.kind !== 'message' && w.kind !== 'externalJob') continue
          const source = w.correlationRef ?? w.label
          const waited = Math.max(0, Math.round((now - new Date(w.since).getTime()) / 1000))
          const old = rows.get(`${w.kind}|${source}`)
          rows.set(`${w.kind}|${source}`, {
            kind: w.kind, source, openNow: (old?.openNow ?? 0) + 1,
            longestOpenSeconds: Math.max(old?.longestOpenSeconds ?? 0, waited), totalOpenSeconds: (old?.totalOpenSeconds ?? 0) + waited,
            finished: 0, averageFinishedSeconds: null, p90FinishedSeconds: null,
          })
        }
      }
      const items = [...rows.values()]
      const days = Math.max(1, Math.round((new Date(to).getTime() - new Date(from).getTime()) / 86_400_000) + 1)
      return HttpResponse.json({ range: { from, to, days, generatedAt: new Date().toISOString() }, items,
        totalOpenSeconds: items.reduce((sum, i) => sum + (i.totalOpenSeconds ?? 0), 0) } satisfies S['InsightsOutsideWaits'])
    }),
    // No checks exist in the browser mock, so every action addresses an unknown wait.
    http.post(`${B}/runs/:runId/waits/:waitId/check-now`, missing),
    http.post(`${B}/runs/:runId/waits/:waitId/extend-deadline`, missing),
    http.post(`${B}/runs/:runId/waits/:waitId/stop`, missing),
  ]
}
