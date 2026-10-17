import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { addNode, addNodeAfter, moveNodes } from './ops'
import { collides, computeLayout, findFreePosition, GAP_X, needsAutoLayout, nodeSize, placementFor, structureKey } from './layout'
import { normalizeDefinition, type Definition, type Position } from './types'

const load = (): Definition => normalizeDefinition(purchase)

function overlapping(def: Definition, positions: Record<string, Position>): string[] {
  const ids = def.nodes.map((n) => n.id)
  const clashes: string[] = []
  for (let i = 0; i < ids.length; i++) {
    for (let j = i + 1; j < ids.length; j++) {
      const a = positions[ids[i] as string] as Position
      const b = positions[ids[j] as string] as Position
      const sa = nodeSize(def.nodes[i]!.type)
      const sb = nodeSize(def.nodes[j]!.type)
      if (a.x < b.x + sb.width && b.x < a.x + sa.width && a.y < b.y + sb.height && b.y < a.y + sa.height) clashes.push(`${ids[i]} / ${ids[j]}`)
    }
  }
  return clashes
}

describe('placing new steps', () => {
  it('puts a new step to the right of the selected step', () => {
    const def = load()
    const at = def.uiLayout!.positions.manager as Position
    // The stored example layout is tighter than the card size, so lay it out first.
    const laid = moveNodes(def, spread(def))
    const anchor = laid.uiLayout!.positions.manager as Position
    const pos = placementFor(laid, 'notification', 'manager')
    expect(at).toBeDefined()
    expect(pos.x).toBeGreaterThanOrEqual(anchor.x + nodeSize('approval').width + GAP_X - 1)
    expect(collides(laid, pos, 'notification')).toBe(false)
  })

  it('goes below the anchor, or further down, when the spot to its right is taken', () => {
    let def = moveNodes(load(), spread(load()))
    const anchor = def.uiLayout!.positions.start as Position
    const right = placementFor(def, 'end', 'start')
    // Occupy the spot to the right of the start step.
    def = addNode(def, 'notification', { position: right }).def
    const next = placementFor(def, 'end', 'start')
    expect(next).not.toEqual(right)
    expect(collides(def, next, 'end')).toBe(false)
    expect(next.x === anchor.x || next.x >= right.x).toBe(true)
  })

  it('never lands on another step, however many steps are added in a row', () => {
    let def = moveNodes(load(), spread(load()))
    let selected = 'manager'
    for (let i = 0; i < 12; i++) {
      const added = addNode(def, i % 3 === 0 ? 'approval' : 'notification', { anchorId: selected })
      def = added.def
      selected = added.id
    }
    expect(overlapping(def, def.uiLayout!.positions)).toEqual([])
  })

  it('inserting a step after another keeps it clear of the steps already there', () => {
    const def = moveNodes(load(), spread(load()))
    const result = addNodeAfter(def, 'manager', 'decision', { on: 'rejected', transitionId: 't3' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(overlapping(result.def, result.def.uiLayout!.positions)).toEqual([])
  })

  it('finds a free spot in the next column when a whole column is taken', () => {
    let def = moveNodes(load(), spread(load()))
    const origin = { x: 5000, y: 0 }
    for (let row = 0; row < 16; row++) def = addNode(def, 'notification', { position: { x: origin.x, y: origin.y + row * (nodeSize('notification').height + 48) } }).def
    const free = findFreePosition(def, origin, 'notification')
    expect(free.x).toBeGreaterThan(origin.x)
    expect(collides(def, free, 'notification')).toBe(false)
  })
})

describe('when a layout is needed', () => {
  it('detects steps drawn on top of each other and steps without a position', () => {
    const def = load()
    expect(needsAutoLayout(def)).toBe(true) // the stored example is tighter than the card size
    const noLayout = { ...def, uiLayout: { positions: {} } }
    expect(needsAutoLayout(noLayout)).toBe(true)
  })

  it('changes the structure key for steps and connections, not for moves', () => {
    const def = load()
    expect(structureKey(moveNodes(def, { start: { x: 500, y: 500 } }))).toBe(structureKey(def))
    expect(structureKey(addNode(def, 'end').def)).not.toBe(structureKey(def))
  })
})

describe('auto-layout (ELK, layered, left to right)', () => {
  it('lays the purchase workflow out without overlaps, left to right, Approved above Rejected', async () => {
    const def = load()
    const positions = await computeLayout(def)
    expect(Object.keys(positions).sort()).toEqual(def.nodes.map((n) => n.id).sort())
    expect(overlapping(def, positions)).toEqual([])

    // Every connection points forward (the workflow is acyclic): the target sits to the right of the source.
    for (const t of def.transitions) expect((positions[t.to] as Position).x, `${t.from} -> ${t.to}`).toBeGreaterThan((positions[t.from] as Position).x)

    // The approval's Approved route leaves above its Rejected route (the order of the handles on the card).
    const approved = def.transitions.find((t) => t.from === 'manager' && t.on === 'approved')!
    const rejected = def.transitions.find((t) => t.from === 'manager' && t.on === 'rejected')!
    expect((positions[approved.to] as Position).y).toBeLessThan((positions[rejected.to] as Position).y)

    // The request form starts the flow.
    const xs = Object.values(positions).map((p) => p.x)
    expect((positions.start as Position).x).toBe(Math.min(...xs))
    // Whole numbers only: positions are stored in the draft.
    for (const p of Object.values(positions)) {
      expect(Number.isInteger(p.x)).toBe(true)
      expect(Number.isInteger(p.y)).toBe(true)
    }
  })

  it('is stable: laying out an already arranged workflow changes nothing', async () => {
    const def = load()
    const first = await computeLayout(def)
    const arranged = moveNodes(def, first)
    expect(needsAutoLayout(arranged)).toBe(false)
    const second = await computeLayout(arranged)
    expect(second).toEqual(first)
    expect(moveNodes(arranged, second)).toBe(arranged)
  })

  it('handles a workflow with only the request form', async () => {
    const def = normalizeDefinition({ ...purchase, nodes: [(purchase as { nodes: unknown[] }).nodes[0]], transitions: [], decisions: [], uiLayout: { positions: {} } })
    const positions = await computeLayout(def)
    expect(Object.keys(positions)).toEqual(['start'])
  })
})

/** A generous manual layout: one column per step in document order, so tests start from a non-overlapping state. */
function spread(def: Definition): Record<string, Position> {
  const out: Record<string, Position> = {}
  def.nodes.forEach((n, i) => {
    out[n.id] = { x: 80 + (i % 5) * 400, y: 80 + Math.floor(i / 5) * 260 }
  })
  return out
}
