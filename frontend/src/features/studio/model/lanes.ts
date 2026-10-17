import { topoOrder } from './graph'
import { cfg, type Definition, type NodeKind, type WorkflowNode } from './types'

/**
 * Swimlanes (UI03, docs/build/S53): a read-only view that groups the steps by who or what is responsible for them.
 * Lanes are derived from the definition (the approval group, the requester, external workers, automation); nothing
 * is stored, so a lane can never disagree with the steps and the compiled workflow is unaffected.
 */
export interface LaneStep {
  id: string
  label: string
  type: NodeKind
  /** 0-based position in the flow order, used as the column. */
  column: number
}

export interface Lane {
  id: string
  label: string
  kind: 'requester' | 'group' | 'unassigned' | 'external' | 'system'
  steps: LaneStep[]
}

export interface Handoff {
  transitionId: string
  from: string
  to: string
  fromLane: string
  toLane: string
  /** Route kind, for example approved or next. */
  on: string
}

export interface LaneModel {
  lanes: Lane[]
  handoffs: Handoff[]
  columns: number
}

const REQUESTER = 'requester'
const SYSTEM = 'system'
const EXTERNAL = 'external'
const UNASSIGNED = 'unassigned'

export function laneIdOf(node: WorkflowNode): string {
  switch (node.type) {
    case 'start':
      return REQUESTER
    case 'approval': {
      const group = cfg<string>(node, 'candidateGroupRef')
      return group ? `group:${group}` : UNASSIGNED
    }
    case 'externalTask':
      return EXTERNAL
    default:
      return SYSTEM
  }
}

export function deriveLanes(def: Definition, groupNames: Record<string, string> = {}): LaneModel {
  const ordered = topoOrder(def)
  const seen = new Set(ordered.map((n) => n.id))
  // Steps that are not in the order (a draft with a loop) still appear, after the ordered ones.
  const all = [...ordered, ...def.nodes.filter((n) => !seen.has(n.id))]
  const column = new Map(all.map((n, i) => [n.id, i]))
  const laneOf = new Map(all.map((n) => [n.id, laneIdOf(n)]))
  const lanes = new Map<string, Lane>()
  const labelOf = (id: string): Pick<Lane, 'label' | 'kind'> => {
    if (id === REQUESTER) return { label: 'Requester', kind: 'requester' }
    if (id === EXTERNAL) return { label: 'External workers', kind: 'external' }
    if (id === UNASSIGNED) return { label: 'No group chosen', kind: 'unassigned' }
    if (id === SYSTEM) return { label: 'Automation', kind: 'system' }
    const key = id.slice('group:'.length)
    return { label: groupNames[key] ?? key, kind: 'group' }
  }
  for (const node of all) {
    const id = laneOf.get(node.id)!
    let lane = lanes.get(id)
    if (!lane) {
      lane = { id, ...labelOf(id), steps: [] }
      lanes.set(id, lane)
    }
    lane.steps.push({ id: node.id, label: node.label, type: node.type, column: column.get(node.id)! })
  }
  const rank = (lane: Lane) => (lane.kind === 'requester' ? 0 : lane.kind === 'group' || lane.kind === 'unassigned' ? 1 : lane.kind === 'external' ? 2 : 3)
  const sorted = [...lanes.values()].sort((a, b) => rank(a) - rank(b))
  const handoffs: Handoff[] = []
  for (const t of def.transitions) {
    const fromLane = laneOf.get(t.from)
    const toLane = laneOf.get(t.to)
    if (fromLane && toLane && fromLane !== toLane) handoffs.push({ transitionId: t.id, from: t.from, to: t.to, fromLane, toLane, on: t.on })
  }
  return { lanes: sorted, handoffs, columns: all.length }
}
