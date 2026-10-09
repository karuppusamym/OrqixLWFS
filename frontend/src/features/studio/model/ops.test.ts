import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { availableFields, chipFor } from './fieldRefs'
import { dominators, topoOrder } from './graph'
import {
  addField,
  addNode,
  addNodeAfter,
  applyFields,
  canConnect,
  connect,
  fieldsFromDefinition,
  moveNodes,
  planNodeDeletion,
  removeNode,
  renameFieldKey,
  renameHttpResult,
  renameNodeId,
  setRoute,
  syncConnectionDependencies,
} from './ops'
import { normalizeDefinition, type Definition } from './types'

const template = (): Definition => normalizeDefinition(purchase)

describe('workflow pattern editing', () => {
  it('adds parallel branches without replacing an existing branch and updates join references on rename', () => {
    let def = addNode(template(), 'parallelSplit').def
    def = addNode(def, 'parallelJoin').def
    const split = def.nodes.find((n) => n.type === 'parallelSplit')!
    const join = def.nodes.find((n) => n.type === 'parallelJoin')!
    split.config.joinRef = join.id
    const first = setRoute(def, split.id, 'next', 'manager')
    expect(first.ok).toBe(true)
    if (!first.ok) return
    const second = setRoute(first.def, split.id, 'next', 'finance')
    expect(second.ok).toBe(true)
    if (!second.ok) return
    expect(second.def.transitions.filter((t) => t.from === split.id).map((t) => t.to)).toEqual(['manager', 'finance'])
    const added = addNodeAfter(second.def, split.id, 'wait', { on: 'next' })
    expect(added.ok).toBe(true)
    if (!added.ok) return
    expect(added.def.transitions.filter((t) => t.from === split.id)).toHaveLength(3)
    const renamed = renameNodeId(added.def, join.id, 'allDone')
    expect(renamed.ok).toBe(true)
    if (renamed.ok) expect(renamed.def.nodes.find((n) => n.id === split.id)!.config.joinRef).toBe('allDone')
  })

  it('permits a timeout route only when the approval has a time limit', () => {
    const def = template()
    expect(canConnect(def, { from: 'manager', to: 'rejected', on: 'timedOut' }).ok).toBe(false)
    def.nodes.find((n) => n.id === 'manager')!.config.timeoutAfter = 'PT2H'
    expect(canConnect(def, { from: 'manager', to: 'rejected', on: 'timedOut' }).ok).toBe(true)
  })

  it('rewrites child input mappings when request fields or producing steps are renamed', () => {
    const def = addNode(template(), 'startWorkflow').def
    const child = def.nodes.find((n) => n.type === 'startWorkflow')!
    child.config.inputMapping = { total: '/input/amount', order: '/steps/procure/procurementId' }
    const field = renameFieldKey(def, 'amount', 'totalAmount')
    expect(field.ok).toBe(true)
    if (!field.ok) return
    const step = renameNodeId(field.def, 'procure', 'order')
    expect(step.ok).toBe(true)
    if (step.ok) expect(step.def.nodes.find((n) => n.id === child.id)!.config.inputMapping).toEqual({ total: '/input/totalAmount', order: '/steps/order/procurementId' })
  })
})

describe('connector result rename', () => {
  it('preserves all consumers and leaves the original document intact', () => {
    const def = template()
    const from = '/steps/procure/procurementId'
    def.transitions[0]!.condition = { fieldRef: from, operator: 'eq', value: 'PO-1' }
    def.decisions[0]!.rows[0]!.when.fieldRef = from
    def.nodes.find((n) => n.id === 'procure')!.config.inputMapping = { title: from }
    def.outputMapping.order = { fieldRef: from, required: false }
    const result = renameHttpResult(def, 'procure', 'procurementId', 'orderId')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.def.transitions[0]!.condition!.fieldRef).toBe('/steps/procure/orderId')
    expect(result.def.decisions[0]!.rows[0]!.when.fieldRef).toBe('/steps/procure/orderId')
    expect(result.def.nodes.find((n) => n.id === 'procure')!.config.inputMapping).toEqual({ title: '/steps/procure/orderId' })
    expect(result.def.outputMapping.order!.fieldRef).toBe('/steps/procure/orderId')
    expect(def.outputMapping.order!.fieldRef).toBe(from)
    expect(renameHttpResult(def, 'procure', 'procurementId', 'invalid-name').ok).toBe(false)
  })
})

describe('connection rules (isValidConnection)', () => {
  it('allows no incoming connection to Start', () => {
    expect(canConnect(template(), { from: 'procure', to: 'start', on: 'next' })).toMatchObject({ ok: false })
  })

  it('allows no outgoing connection from Finish', () => {
    expect(canConnect(template(), { from: 'fulfilled', to: 'notify', on: 'next' })).toMatchObject({ ok: false })
  })

  it('refuses self loops and loops back through other steps', () => {
    const def = template()
    expect(canConnect(def, { from: 'procure', to: 'procure', on: 'next' })).toMatchObject({ ok: false })
    // finance -> procure exists; procure -> finance would close a loop (a different transition id is irrelevant)
    expect(canConnect(def, { from: 'notify', to: 'manager', on: 'next', transitionId: 't10' })).toMatchObject({ ok: false, reason: expect.stringContaining('loop') })
  })

  it('allows one outgoing route from start, decision, call-a-system and notify', () => {
    const def = template()
    for (const from of ['start', 'amountPolicy', 'procure', 'notify']) {
      expect(canConnect(def, { from, to: 'rejected', on: 'next' })).toMatchObject({ ok: false, reason: expect.stringContaining('already leads') })
    }
  })

  it('gives an approval one Approved and one Rejected route, each used once', () => {
    const { def, id } = addNode(template(), 'approval')
    expect(canConnect(def, { from: id, to: 'fulfilled', on: 'approved' })).toEqual({ ok: true })
    expect(canConnect(def, { from: id, to: 'rejected', on: 'rejected' })).toEqual({ ok: true })
    expect(canConnect(def, { from: id, to: 'fulfilled', on: 'next' })).toMatchObject({ ok: false })
    const connected = connect(def, { from: id, to: 'fulfilled', on: 'approved' })
    expect(connected.ok).toBe(true)
    if (connected.ok) expect(canConnect(connected.def, { from: id, to: 'rejected', on: 'approved' })).toMatchObject({ ok: false })
  })

  it('allows exactly one Otherwise route on a choice and no duplicate target', () => {
    const def = template()
    expect(canConnect(def, { from: 'route', to: 'notify', on: 'default' })).toMatchObject({ ok: false, reason: expect.stringContaining('exactly one Otherwise') })
    expect(canConnect(def, { from: 'route', to: 'finance', on: 'condition' })).toMatchObject({ ok: false, reason: expect.stringContaining('already a path') })
    expect(canConnect(def, { from: 'route', to: 'notifyRejected', on: 'condition' })).toEqual({ ok: true })
  })
})

describe('adding, inserting and removing steps', () => {
  it('generates unique ids and records a layout position', () => {
    let def = template()
    const a = addNode(def, 'approval')
    def = a.def
    const b = addNode(def, 'approval')
    expect(a.id).toBe('approval')
    expect(b.id).toBe('approval1')
    expect(b.def.uiLayout?.positions[b.id]).toBeDefined()
  })

  it('creates a rule table with a new decision step', () => {
    const { def, id } = addNode(template(), 'decision')
    const node = def.nodes.find((n) => n.id === id)!
    expect(def.decisions.some((d) => d.key === node.config.decisionRef)).toBe(true)
    expect(def.decisions).toHaveLength(2)
  })

  it('inserts a step between two connected steps so the flow keeps working', () => {
    const def = template()
    const result = addNodeAfter(def, 'manager', 'notification', { on: 'approved' })
    expect(result.ok).toBe(true)
    if (!result.ok || !result.id) return
    const out = (id: string) => result.def.transitions.filter((t) => t.from === id).map((t) => `${t.on}:${t.to}`)
    expect(out('manager')).toContain(`approved:${result.id}`)
    expect(out(result.id)).toEqual(['next:amountPolicy'])
    expect(out('manager')).toContain('rejected:notifyRejected')
  })

  it('deleting a step removes its transitions, its layout position and a rule table only it used', () => {
    const def = template()
    const plan = planNodeDeletion(def, 'amountPolicy')!
    expect(plan.transitions.map((t) => t.id).sort()).toEqual(['t2', 't4'])
    expect(plan.decisions).toEqual(['amount-policy'])
    const next = removeNode(def, 'amountPolicy')
    expect(next.nodes.some((n) => n.id === 'amountPolicy')).toBe(false)
    expect(next.transitions.some((t) => t.from === 'amountPolicy' || t.to === 'amountPolicy')).toBe(false)
    expect(next.uiLayout?.positions.amountPolicy).toBeUndefined()
    expect(next.decisions).toHaveLength(0)
    // The input is never mutated.
    expect(def.nodes.some((n) => n.id === 'amountPolicy')).toBe(true)
  })

  it('keeps a rule table that another step still uses', () => {
    const { def, id } = addNode(template(), 'decision')
    const shared = { ...def, nodes: def.nodes.map((n) => (n.id === id ? { ...n, config: { decisionRef: 'amount-policy' } } : n)) }
    expect(planNodeDeletion(shared, 'amountPolicy')!.decisions).toEqual([])
  })

  it('never deletes the start step', () => {
    expect(planNodeDeletion(template(), 'start')).toBeNull()
    const def = template()
    expect(removeNode(def, 'start')).toBe(def)
  })

  it('drops a connection dependency when its last step is deleted', () => {
    const next = removeNode(template(), 'procure')
    expect(next.dependencies.connections).toEqual([])
    expect(Object.keys(next.outputMapping)).toEqual([])
  })

  it('renames a step id everywhere, including field references', () => {
    const result = renameNodeId(template(), 'amountPolicy', 'policy')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.def.transitions.find((t) => t.id === 't5')?.condition?.fieldRef).toBe('/steps/policy/route')
    expect(result.def.uiLayout?.positions.policy).toBeDefined()
    expect(renameNodeId(template(), 'amountPolicy', 'manager')).toMatchObject({ ok: false })
    expect(renameNodeId(template(), 'amountPolicy', 'a__b')).toMatchObject({ ok: false })
  })

  it('moves nodes and ignores moves that change nothing', () => {
    const def = template()
    expect(moveNodes(def, { start: { x: 80, y: 200 } })).toBe(def)
    const moved = moveNodes(def, { start: { x: 100.4, y: 210 } })
    expect(moved.uiLayout?.positions.start).toEqual({ x: 100, y: 210 })
  })
})

describe('Steps tab route selection', () => {
  it('re-points, connects and disconnects a route', () => {
    const def = template()
    const repoint = setRoute(def, 'manager', 'rejected', 'rejected')
    expect(repoint.ok && repoint.def.transitions.find((t) => t.id === 't3')?.to).toBe('rejected')
    const cut = setRoute(def, 'manager', 'rejected', null)
    expect(cut.ok && cut.def.transitions.some((t) => t.id === 't3')).toBe(false)
    if (cut.ok) {
      const back = setRoute(cut.def, 'manager', 'rejected', 'notifyRejected')
      expect(back.ok && back.def.transitions.filter((t) => t.from === 'manager')).toHaveLength(2)
    }
  })

  it('refuses a route that would create a loop', () => {
    expect(setRoute(template(), 'procure', 'next', 'manager')).toMatchObject({ ok: false })
  })
})

describe('graph helpers', () => {
  it('orders steps topologically from the start', () => {
    const order = topoOrder(template()).map((n) => n.id)
    expect(order[0]).toBe('start')
    expect(order.indexOf('manager')).toBeLessThan(order.indexOf('amountPolicy'))
    expect(order.indexOf('finance')).toBeLessThan(order.indexOf('procure'))
    expect(order).toHaveLength(10)
  })

  it('computes dominators for field availability', () => {
    const dom = dominators(template())
    expect(dom.get('procure')?.has('manager')).toBe(true)
    expect(dom.get('procure')?.has('finance')).toBe(false)
    const fields = availableFields(template(), 'procure').map((f) => f.ref)
    expect(fields).toContain('/input/amount')
    expect(fields).toContain('/steps/manager/decision')
    expect(fields).not.toContain('/steps/finance/decision')
    expect(chipFor(template(), '/input/amount')).toBe('Request → Amount (USD)')
  })
})

describe('request form fields', () => {
  it('reads the purchase form into editor fields and writes the same schema back', () => {
    const def = template()
    const fields = fieldsFromDefinition(def)
    expect(fields.map((f) => [f.key, f.kind, f.required])).toEqual([
      ['title', 'text', true],
      ['purpose', 'longText', true],
      ['costCenter', 'choice', true],
      ['amount', 'money', true],
    ])
    expect(applyFields(def, fields)).toBe(def)
  })

  it('adds a field with a key derived from its label and renames keys with references', () => {
    const def = template()
    const added = addField(def, 'Needed by date')
    expect(added.key).toBe('neededByDate')
    expect(added.def.inputUi?.order).toContain('neededByDate')
    const renamed = renameFieldKey(def, 'amount', 'totalAmount')
    expect(renamed.ok).toBe(true)
    if (renamed.ok) {
      expect(renamed.def.inputSchema.properties.totalAmount).toBeDefined()
      expect(renamed.def.decisions[0]?.rows[0]?.when.fieldRef).toBe('/input/totalAmount')
      expect(renamed.def.nodes.find((n) => n.id === 'procure')?.config.inputMapping).toMatchObject({ amount: '/input/totalAmount' })
    }
    expect(renameFieldKey(def, 'amount', 'title')).toMatchObject({ ok: false })
  })

  it('keeps connection dependencies in step with the steps that use them', () => {
    const def = template()
    expect(syncConnectionDependencies(def, {})).toBe(def)
    const added = addNode(def, 'http')
    const withConn = { ...added.def, nodes: added.def.nodes.map((n) => (n.id === added.id ? { ...n, config: { ...n.config, connectionRef: 'billing' } } : n)) }
    expect(syncConnectionDependencies(withConn, { billing: 3 }).dependencies.connections).toContainEqual({ key: 'billing', definitionVersion: 3 })
  })
})
