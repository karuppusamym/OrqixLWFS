import type { StageView } from '@/api/types'
import { durationMinutes } from '@/lib/duration'
import type { StageDef } from '@/features/studio/model/types'
import { addEvent, currentRelease, notify } from './engine'
import { USERS, clock, db, permissionsOf, userById, type MockRun } from './state'

/**
 * Case stages in mock mode (docs/build/S17 section 4), mirroring the server: the state is derived from the current
 * steps after every change. A stage is entered when its first step becomes current, completed when no step of it is
 * current any more, and overdue once its time limit has passed. Overdue is shown at read time and recorded once (event
 * and notifications) by {@link detectOverdueStages}.
 */

export interface MockStageRow {
  cycle: number
  enteredAt: string
  dueAt: string | null
  completedAt: string | null
  overdueAt: string | null
}

export function stageDefs(run: MockRun): StageDef[] {
  return currentRelease(run).definition.stages ?? []
}

/** Nodes the run is at right now (open approvals, waits, child runs, messages and a pending system call). */
function currentNodeIds(run: MockRun): string[] {
  const ids = new Set<string>()
  for (const t of run.tasks) if (t.status === 'open') ids.add(t.nodeId)
  for (const id of Object.keys(run.waits ?? {})) ids.add(id)
  for (const id of Object.keys(run.childWaits ?? {})) ids.add(id)
  for (const id of Object.keys(run.messageWaits ?? {})) ids.add(id)
  if (run.pendingHttpNode) ids.add(run.pendingHttpNode)
  return [...ids]
}

export function reconcileStages(run: MockRun): void {
  const defs = stageDefs(run)
  if (defs.length === 0) return
  const rows = (run.stages ??= {})
  const activeRun = run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention'
  const active = new Set<string>()
  if (activeRun) for (const id of currentNodeIds(run)) for (const d of defs) if (d.nodes.includes(id)) active.add(d.id)
  const now = clock.iso()
  defs.forEach((d, i) => {
    const row = rows[d.id]
    if (row && !row.completedAt && !active.has(d.id)) {
      row.completedAt = now
      if (run.lifecycle !== 'cancelled') {
        addEvent(run, 'STAGE_COMPLETED', { data: { stageId: d.id, label: d.label, index: i + 1, total: defs.length, cycle: row.cycle, enteredAt: row.enteredAt, dueAt: row.dueAt, completedAt: now, late: !!row.dueAt && now > row.dueAt } })
      }
    }
  })
  defs.forEach((d, i) => {
    const row = rows[d.id]
    if (!active.has(d.id) || (row && !row.completedAt)) return
    const minutes = d.sla ? durationMinutes(d.sla) : null
    const due = minutes ? new Date(clock.now().getTime() + minutes * 60_000).toISOString() : null
    rows[d.id] = { cycle: (row?.cycle ?? 0) + 1, enteredAt: now, dueAt: due, completedAt: null, overdueAt: null }
    addEvent(run, 'STAGE_ENTERED', { data: { stageId: d.id, label: d.label, index: i + 1, total: defs.length, cycle: rows[d.id]!.cycle, enteredAt: now, dueAt: due } })
  })
}

function view(def: StageDef, index: number, total: number, row: MockStageRow | undefined): StageView {
  const state = !row ? 'notStarted' : row.completedAt ? 'completed' : 'active'
  const overdue = !!row && !row.completedAt && !!row.dueAt && clock.now().getTime() > Date.parse(row.dueAt)
  return {
    id: def.id,
    label: def.label,
    index: index + 1,
    total,
    state,
    enteredAt: row?.enteredAt ?? null,
    dueAt: row?.dueAt ?? null,
    completedAt: row?.completedAt ?? null,
    overdue,
    sla: def.sla ?? null,
  }
}

/** All stages of a run with their state, and the current one (the first active, else the latest entered). */
export function stageProgress(run: MockRun): { current: StageView | null; stages: StageView[] } {
  const defs = stageDefs(run)
  const stages = defs.map((d, i) => view(d, i, defs.length, run.stages?.[d.id]))
  let current: StageView | null = stages.find((s) => s.state === 'active') ?? null
  if (!current) for (const s of stages) if (s.enteredAt && (!current || s.enteredAt > (current.enteredAt ?? ''))) current = s
  return { current, stages }
}

export function stageOfNode(run: MockRun, nodeId: string): StageView | null {
  const defs = stageDefs(run)
  const index = defs.findIndex((d) => d.nodes.includes(nodeId))
  return index < 0 ? null : view(defs[index]!, index, defs.length, run.stages?.[defs[index]!.id])
}

/** Records stages past their time limit, once, and tells the initiator and the operators. Returns how many. */
export function detectOverdueStages(): number {
  let recorded = 0
  for (const run of db.runs.values()) {
    const defs = stageDefs(run)
    if (defs.length === 0 || !run.stages) continue
    defs.forEach((d, i) => {
      const row = run.stages?.[d.id]
      if (!row || row.completedAt || row.overdueAt || !row.dueAt || clock.now().getTime() <= Date.parse(row.dueAt)) return
      if (!(run.lifecycle === 'running' || run.lifecycle === 'waiting' || run.lifecycle === 'needsAttention')) return
      row.overdueAt = clock.iso()
      addEvent(run, 'STAGE_OVERDUE', { data: { stageId: d.id, label: d.label, index: i + 1, total: defs.length, cycle: row.cycle, enteredAt: row.enteredAt, dueAt: row.dueAt, sla: d.sla ?? null } })
      const recipients = new Set<string>([run.initiatorId])
      for (const u of USERS) if (permissionsOf(u, run.wsId).includes('run.operate')) recipients.add(u.id)
      for (const id of recipients) if (userById(id)) notify(run, id, 'workflowMessage', `Request ${run.reference ?? run.id} is overdue at the stage "${d.label}".`)
      recorded++
    })
  }
  return recorded
}

/** Mock-only control: make a stage of a run late now (used by demos and tests), then record it. */
export function expireStage(runId: string, stageId: string): void {
  const run = db.runs.get(runId)
  const row = run?.stages?.[stageId]
  if (!run || !row) throw new Error(`Run ${runId} has not entered the stage ${stageId}`)
  row.dueAt = new Date(clock.now().getTime() - 60_000).toISOString()
  detectOverdueStages()
}
