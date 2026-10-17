import type { Definition, Transition, WorkflowNode } from './types'

export function nodeById(def: Definition, id: string): WorkflowNode | undefined {
  return def.nodes.find((n) => n.id === id)
}

export function outgoing(def: Definition, id: string): Transition[] {
  return def.transitions.filter((t) => t.from === id)
}

export function incoming(def: Definition, id: string): Transition[] {
  return def.transitions.filter((t) => t.to === id)
}

export function startNode(def: Definition): WorkflowNode | undefined {
  return def.nodes.find((n) => n.type === 'start')
}

/** Nodes reachable from `from` following outgoing transitions (excluding `from` itself unless it is on a cycle). */
export function reachableFrom(def: Definition, from: string): Set<string> {
  const seen = new Set<string>()
  const stack = [from]
  while (stack.length > 0) {
    const current = stack.pop() as string
    for (const t of outgoing(def, current)) {
      if (!seen.has(t.to)) {
        seen.add(t.to)
        stack.push(t.to)
      }
    }
  }
  return seen
}

export function reaches(def: Definition, from: string, to: string): boolean {
  return reachableFrom(def, from).has(to)
}

/** Connecting `from` -> `to` would close a loop (workflows are acyclic in v0). */
export function wouldCreateCycle(def: Definition, from: string, to: string): boolean {
  return from === to || reaches(def, to, from)
}

export function hasCycle(def: Definition): boolean {
  return def.nodes.some((n) => reaches(def, n.id, n.id))
}

/** Steps in topological order from the start step; unreachable steps follow in document order. */
export function topoOrder(def: Definition): WorkflowNode[] {
  const indegree = new Map<string, number>()
  for (const n of def.nodes) indegree.set(n.id, 0)
  for (const t of def.transitions) if (indegree.has(t.to) && indegree.has(t.from)) indegree.set(t.to, (indegree.get(t.to) ?? 0) + 1)
  const order: WorkflowNode[] = []
  const placed = new Set<string>()
  const queue = def.nodes.filter((n) => (indegree.get(n.id) ?? 0) === 0)
  // Start first when several roots exist.
  queue.sort((a, b) => (a.type === 'start' ? -1 : 0) - (b.type === 'start' ? -1 : 0))
  while (queue.length > 0) {
    const n = queue.shift() as WorkflowNode
    if (placed.has(n.id)) continue
    placed.add(n.id)
    order.push(n)
    for (const t of outgoing(def, n.id)) {
      if (!indegree.has(t.to)) continue
      indegree.set(t.to, (indegree.get(t.to) ?? 1) - 1)
      if ((indegree.get(t.to) ?? 0) <= 0) {
        const next = nodeById(def, t.to)
        if (next && !placed.has(next.id)) queue.push(next)
      }
    }
  }
  for (const n of def.nodes) if (!placed.has(n.id)) order.push(n)
  return order
}

/**
 * Dominator sets: X dominates N when every path from start to N passes through X (S4 section 4).
 * Iterative algorithm; workflows have at most 50 nodes. Unreachable nodes dominate only themselves.
 */
export function dominators(def: Definition): Map<string, Set<string>> {
  const start = startNode(def)
  const ids = def.nodes.map((n) => n.id)
  const all = new Set(ids)
  const dom = new Map<string, Set<string>>()
  for (const id of ids) dom.set(id, id === start?.id ? new Set([id]) : new Set(all))
  let changed = true
  while (changed) {
    changed = false
    for (const id of ids) {
      if (id === start?.id) continue
      const preds = incoming(def, id)
        .map((t) => t.from)
        .filter((p) => dom.has(p))
      let next: Set<string>
      if (preds.length === 0) {
        next = new Set([id])
      } else {
        next = new Set(dom.get(preds[0] as string))
        for (const p of preds.slice(1)) {
          const pd = dom.get(p) as Set<string>
          for (const x of [...next]) if (!pd.has(x)) next.delete(x)
        }
        next.add(id)
      }
      const current = dom.get(id) as Set<string>
      if (next.size !== current.size || [...next].some((x) => !current.has(x))) {
        dom.set(id, next)
        changed = true
      }
    }
  }
  return dom
}

/** Steps whose outputs are guaranteed available at `nodeId` (strict dominators). */
export function guaranteedBefore(def: Definition, nodeId: string): Set<string> {
  const d = dominators(def).get(nodeId) ?? new Set<string>()
  const result = new Set(d)
  result.delete(nodeId)
  return result
}
