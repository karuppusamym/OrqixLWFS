import type { CurrentStep, Lifecycle } from '@/api/types'
import type { PathCurrent, PathVisit, RunPath } from '@/features/runs/diagram/api'
import type { Definition } from '@/features/studio/model/types'
import type { MockEvent } from './state'

/**
 * Mock of getRunPath: the same derivation as RunPathBuilder.java (backend/app/.../runtime/internal), from the mock run's
 * events and release graph. Keep the two in step: events name the step, silent steps (choices, parallel splits and
 * joins) are inferred, a route is taken when both ends were visited in order and, for approval routes, the approval
 * ended with that outcome.
 */
interface Visit {
  nodeId: string
  occurrence: number
  enteredAt: string
  leftAt: string | null
  outcome: PathVisit['outcome']
}

const SILENT = new Set(['branch', 'parallelSplit', 'parallelJoin'])

export function buildRunPath(opts: {
  runId: string
  lifecycle: Lifecycle
  definition: Definition
  events: MockEvent[]
  currentSteps: CurrentStep[]
  attentionNodeId: string | null
}): RunPath {
  const { definition, events } = opts
  const nodes = new Map(definition.nodes.map((n) => [n.id, n]))
  const start = definition.nodes.find((n) => n.type === 'start')?.id
  const transitions = definition.transitions.filter((t) => nodes.has(t.from) && nodes.has(t.to))
  const visits = new Map<string, Visit[]>()

  const list = (id: string) => {
    let l = visits.get(id)
    if (!l) visits.set(id, (l = []))
    return l
  }
  const open = (id: string, at: string) => list(id).push({ nodeId: id, occurrence: list(id).length + 1, enteredAt: at, leftAt: null, outcome: null })
  const instant = (id: string, at: string, outcome: Visit['outcome']) => list(id).push({ nodeId: id, occurrence: list(id).length + 1, enteredAt: at, leftAt: at, outcome })
  const latestOpen = (id: string) => [...(visits.get(id) ?? [])].reverse().find((v) => v.outcome === null)
  const close = (id: string, at: string, outcome: Visit['outcome']) => {
    const v = latestOpen(id)
    if (!v) return instant(id, at, outcome)
    v.leftAt = at
    v.outcome = outcome
  }
  const closeAll = (at: string, outcome: Visit['outcome']) => {
    for (const l of visits.values()) for (const v of l) {
      if (v.outcome !== null) continue
      v.leftAt = at
      v.outcome = outcome
    }
  }
  const childEnded = new Map<string, { at: string; kind: string }>()

  for (const e of events) {
    const id = e.nodeId && nodes.has(e.nodeId) ? e.nodeId : null
    switch (e.type) {
      case 'RUN_STARTED':
        if (start && !visits.has(start)) instant(start, e.occurredAt, 'completed')
        break
      case 'TASK_CREATED':
      case 'WAIT_STARTED':
        if (id) open(id, e.occurredAt)
        break
      case 'TASK_DECIDED':
        if (id) close(id, e.occurredAt, e.data.action === 'reject' ? 'rejected' : 'approved')
        break
      case 'TASK_TIMED_OUT':
        if (id) close(id, e.occurredAt, 'timedOut')
        break
      case 'WAIT_ENDED':
        if (id) close(id, e.occurredAt, e.data.outcome === 'timedOut' ? 'timedOut' : 'completed')
        break
      case 'DECISION_EVALUATED':
      case 'EFFECT_SUCCEEDED':
      case 'NOTIFICATION_CREATED':
        if (id) instant(id, e.occurredAt, 'completed')
        break
      case 'CHILD_STARTED':
        if (id && !latestOpen(id)) open(id, e.occurredAt)
        break
      case 'CHILD_ENDED':
        if (id) childEnded.set(id, { at: e.occurredAt, kind: String(e.data.outcomeKind ?? 'success') })
        break
      case 'RUN_COMPLETED':
        if (id) instant(id, e.occurredAt, 'completed')
        closeAll(e.occurredAt, 'cancelled')
        break
      case 'RUN_CANCELLED':
        closeAll(e.occurredAt, 'cancelled')
        break
    }
  }

  const current: PathCurrent[] = []
  const currentIds = new Set<string>()
  for (const s of opts.currentSteps) {
    if (!nodes.has(s.nodeId)) continue
    currentIds.add(s.nodeId)
    const attention = opts.lifecycle === 'needsAttention' && s.nodeId === opts.attentionNodeId
    current.push({ nodeId: s.nodeId, label: s.label, kind: s.kind, status: attention ? 'needsAttention' : s.kind === 'http' ? 'running' : 'waiting', since: s.since, candidateGroup: s.candidateGroup ?? null, assignee: s.assignee ?? null, dueAt: s.dueAt ?? null })
  }
  for (const [id, ended] of childEnded) {
    const v = latestOpen(id)
    if (v && !currentIds.has(id)) {
      v.leftAt = ended.at
      v.outcome = ended.kind === 'cancelled' ? 'cancelled' : 'completed'
    }
  }

  const before = new Map<string, string[]>()
  const after = new Map<string, string[]>()
  for (const t of transitions) {
    before.set(t.to, [...(before.get(t.to) ?? []), t.from])
    after.set(t.from, [...(after.get(t.from) ?? []), t.to])
  }
  for (let changed = true; changed; ) {
    changed = false
    for (const n of definition.nodes) {
      if (visits.has(n.id) || !SILENT.has(n.type)) continue
      let passedAt: string | null = null
      for (const p of before.get(n.id) ?? []) for (const v of visits.get(p) ?? []) {
        const when = v.leftAt ?? v.enteredAt
        if (passedAt === null || when > passedAt) passedAt = when
      }
      if (passedAt === null) continue
      if ((after.get(n.id) ?? []).some((s) => visits.has(s) || currentIds.has(s))) {
        instant(n.id, passedAt, 'completed')
        changed = true
      }
    }
  }

  const taken = transitions
    .filter((t) => {
      const outcomeRoute = t.on === 'approved' || t.on === 'rejected' || t.on === 'timedOut'
      return (visits.get(t.from) ?? []).some((f) => {
        if (f.leftAt === null || (outcomeRoute && f.outcome !== t.on) || (t.on === 'next' && (f.outcome === 'timedOut' || f.outcome === 'cancelled'))) return false
        if (currentIds.has(t.to)) return true
        return (visits.get(t.to) ?? []).some((v) => v.enteredAt >= f.leftAt!)
      })
    })
    .map((t) => t.id)

  const visited = [...visits.values()].flat().sort((a, b) => a.enteredAt.localeCompare(b.enteredAt))
  return {
    runId: opts.runId,
    lifecycle: opts.lifecycle,
    diagram: {
      nodes: definition.nodes.map((n) => {
        const p = definition.uiLayout?.positions[n.id]
        return {
          id: n.id,
          kind: n.type,
          label: n.label,
          outcomeKind: n.type === 'end' ? ((n.config.outcomeKind as 'success' | 'rejected' | 'failed' | undefined) ?? null) : null,
          outcome: n.type === 'end' ? ((n.config.businessOutcome as string | undefined) ?? null) : null,
          hasTimeout: !!n.config.timeoutAfter,
          position: p ? { x: p.x, y: p.y } : null,
        }
      }),
      transitions: transitions.map((t) => ({ id: t.id, from: t.from, to: t.to, on: t.on, label: t.label ?? null })),
    },
    visited,
    current,
    taken,
  }
}
