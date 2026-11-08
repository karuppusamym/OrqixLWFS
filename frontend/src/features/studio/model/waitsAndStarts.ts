import type { AssetRef, Definition, Transition, WorkflowNode } from './types'

/**
 * Studio side of docs/build/S139: which shared working calendars a workflow uses and their pins
 * (dependencies.assets, kind calendar), the branches of "Wait for the first" and their paths.
 */

export type BranchKind = 'message' | 'signal' | 'callback' | 'timer' | 'check'

export interface FirstBranch {
  id: string
  label: string
  kind: BranchKind
  message?: string
  correlationField?: string
  payload?: Array<{ name: string; type: 'string' | 'number' | 'integer' | 'boolean' | 'date'; title?: string; required?: boolean }>
  signal?: string
  tokenValidFor?: string
  after?: string
  untilField?: string
  atTime?: string
  workingDuration?: string
  calendarRef?: string
  check?: Record<string, unknown>
}

export const BRANCH_KIND_LABEL: Record<BranchKind, string> = {
  message: 'A message arrives',
  signal: 'A signal of this request',
  callback: 'An outside system answers (callback token)',
  timer: 'A time passes or a date comes',
  check: 'A check finds the work done',
}

export const BRANCH_ID = /^[a-z][A-Za-z0-9]{0,19}$/

/** @return the branches of a "Wait for the first" step (defensive: drafts may be incomplete) */
export function branchesOf(node: WorkflowNode): FirstBranch[] {
  return Array.isArray(node.config.branches) ? (node.config.branches as FirstBranch[]) : []
}

/** Default settings of a branch of each kind; the label and id stay. */
export function branchOfKind(branch: Pick<FirstBranch, 'id' | 'label'>, kind: BranchKind): FirstBranch {
  switch (kind) {
    case 'message': return { ...branch, kind, message: 'message-received', correlationField: '', payload: [] }
    case 'signal': return { ...branch, kind, signal: 'signal-name' }
    case 'callback': return { ...branch, kind, tokenValidFor: 'P7D', payload: [] }
    case 'timer': return { ...branch, kind, after: 'P1D' }
    case 'check': return { ...branch, kind, payload: [], check: { connectionRef: '', operation: '', inputMapping: {}, resultMapping: {}, timeoutSeconds: 10, every: 'PT1H', until: { fieldRef: '', operator: 'eq', value: '' } } }
  }
}

/** A free branch id: answered, answered2, ... */
export function newBranchId(node: WorkflowNode): string {
  const used = new Set(branchesOf(node).map((b) => b.id))
  for (let i = 1; ; i++) {
    const id = i === 1 ? 'branch' : `branch${i}`
    if (!used.has(id)) return id
  }
}

/** The path of one branch, if it is drawn. */
export function branchTransition(def: Definition, nodeId: string, branchId: string): Transition | undefined {
  return def.transitions.find((t) => t.from === nodeId && t.on === 'branch' && t.branch === branchId)
}

/** Points the path of a branch at a step (or removes it when `to` is empty). */
export function setBranchRoute(def: Definition, nodeId: string, branchId: string, to: string): Definition {
  const transitions = def.transitions.filter((t) => !(t.from === nodeId && t.on === 'branch' && t.branch === branchId))
  if (to) {
    const used = new Set(def.transitions.map((t) => t.id))
    let n = def.transitions.length + 1
    while (used.has(`t${n}`)) n++
    transitions.push({ id: `t${n}`, from: nodeId, to, on: 'branch', branch: branchId })
  }
  return { ...def, transitions }
}

/** Renames a branch: its path follows. */
export function renameBranch(def: Definition, nodeId: string, from: string, to: string): Definition {
  return {
    ...def,
    nodes: def.nodes.map((n) => n.id !== nodeId ? n : { ...n, config: { ...n.config, branches: branchesOf(n).map((b) => b.id === from ? { ...b, id: to } : b) } }),
    transitions: def.transitions.map((t) => t.from === nodeId && t.on === 'branch' && t.branch === from ? { ...t, branch: to } : t),
  }
}

/** Removes a branch and its path. */
export function removeBranch(def: Definition, nodeId: string, branchId: string): Definition {
  return {
    ...def,
    nodes: def.nodes.map((n) => n.id !== nodeId ? n : { ...n, config: { ...n.config, branches: branchesOf(n).filter((b) => b.id !== branchId) } }),
    transitions: def.transitions.filter((t) => !(t.from === nodeId && t.on === 'branch' && t.branch === branchId)),
  }
}

/** @return every calendar key a step of the workflow counts working time in */
export function calendarsUsed(def: Definition): Set<string> {
  const keys = new Set<string>()
  for (const n of def.nodes) {
    const c = n.config
    if (n.type === 'wait' && typeof c.calendarRef === 'string' && c.calendarRef) keys.add(c.calendarRef)
    if (n.type === 'waitForMessage' && typeof c.timeoutCalendarRef === 'string' && c.timeoutCalendarRef) keys.add(c.timeoutCalendarRef)
    const poll = c.poll as { timeoutCalendarRef?: string } | undefined
    if (n.type === 'http' && poll?.timeoutCalendarRef) keys.add(poll.timeoutCalendarRef)
    if (n.type === 'waitForFirst') branchesOf(n).forEach((b) => { if (b.calendarRef) keys.add(b.calendarRef) })
  }
  return keys
}

/**
 * Keeps the calendar pins in step with the steps: a calendar a step starts using is pinned at its latest version, a
 * pin nobody uses any more is removed, existing pins keep their version (publishing warns when a newer one exists).
 */
export function syncCalendarPins(def: Definition, latest: Record<string, number>): Definition {
  const used = calendarsUsed(def)
  const deps = (def.dependencies ?? {}) as Definition['dependencies'] & { assets?: AssetRef[] }
  const assets = (deps.assets ?? []) as AssetRef[]
  const kept = assets.filter((a) => a.kind !== 'calendar' || used.has(a.key))
  for (const key of used) {
    if (!kept.some((a) => a.kind === 'calendar' && a.key === key) && latest[key]) {
      kept.push({ kind: 'calendar', key, version: latest[key], target: 'workflow' })
    }
  }
  if (kept.length === assets.length && kept.every((a, i) => a === assets[i])) return def
  const next = { ...deps } as typeof deps
  if (kept.length) next.assets = kept
  else delete next.assets
  return { ...def, dependencies: next }
}

/** The pinned version of a calendar, if any. */
export function pinnedCalendar(def: Definition, key: string): number | undefined {
  const assets = ((def.dependencies ?? {}) as { assets?: AssetRef[] }).assets ?? []
  return assets.find((a) => a.kind === 'calendar' && a.key === key)?.version
}
