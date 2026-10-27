import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { KINDS, PALETTE_KINDS } from './kinds'
import { computeLayout, nodeSize, needsAutoLayout, hasPoorLayout } from './layout'
import { BPMN_NOTATION, bpmnSize, labelOutside } from './notation'
import { SHAPE_SIZES } from './shapeSizes'
import { normalizeDefinition, type NodeKind } from './types'

const ALL_KINDS = Object.keys(KINDS) as NodeKind[]

describe('BPMN notation mapping (STU01)', () => {
  it('maps every step kind to a shape, and nothing else', () => {
    expect(ALL_KINDS).toHaveLength(19)
    expect(Object.keys(BPMN_NOTATION).sort()).toEqual([...ALL_KINDS].sort())
    for (const kind of [...ALL_KINDS, ...PALETTE_KINDS]) {
      const n = BPMN_NOTATION[kind]
      expect(n, kind).toBeDefined()
      expect(n.shape, kind).toBeTruthy()
      expect(n.element, kind).toBeTruthy()
      expect(n.symbol, kind).toBeTruthy()
    }
  })

  it('follows the mapping table of docs/25 section 1', () => {
    const table: Record<NodeKind, [string, string]> = {
      start: ['startEvent', 'none start event'],
      approval: ['userTask', 'user task'],
      decision: ['businessRuleTask', 'business rule task'],
      branch: ['exclusiveGateway', 'exclusive gateway'],
      parallelSplit: ['parallelGateway', 'parallel gateway (split)'],
      parallelJoin: ['parallelGateway', 'parallel gateway (join)'],
      http: ['serviceTask', 'service task'],
      notification: ['sendTask', 'send task'],
      sendAdapter: ['sendTask', 'send task'],
      generateDocument: ['documentTask', 'service task'],
      externalTask: ['externalServiceTask', 'external service task'],
      wait: ['timerCatchEvent', 'intermediate timer catch event'],
      waitForMessage: ['messageCatchEvent', 'intermediate message catch event'],
      waitForSignal: ['signalCatchEvent', 'intermediate signal catch event'],
      sendSignal: ['signalThrowEvent', 'intermediate signal throw event'],
      raiseError: ['errorEndEvent', 'error end event'],
      compensate: ['compensationThrowEvent', 'intermediate compensation throw event'],
      startWorkflow: ['callActivity', 'call activity'],
      end: ['endEvent', 'end event'],
    }
    for (const kind of ALL_KINDS) expect([BPMN_NOTATION[kind].shape, BPMN_NOTATION[kind].element], kind).toEqual(table[kind])
  })

  it('uses the shared bounding boxes: events 48x48, gateways 64x64, tasks and call activities 200x80', () => {
    expect(SHAPE_SIZES).toEqual({ event: { width: 48, height: 48 }, gateway: { width: 64, height: 64 }, task: { width: 200, height: 80 } })
    const expected = (kind: NodeKind) => (kind.endsWith('Split') || kind.endsWith('Join') || kind === 'branch' ? 64 : ['start', 'end', 'wait', 'waitForMessage', 'waitForSignal', 'sendSignal', 'raiseError', 'compensate'].includes(kind) ? 48 : 200)
    for (const kind of ALL_KINDS) {
      expect(bpmnSize(kind).width, kind).toBe(expected(kind))
      expect(nodeSize(kind, 'bpmn'), kind).toEqual(bpmnSize(kind))
      expect(labelOutside(kind), kind).toBe(BPMN_NOTATION[kind].sizeClass !== 'task')
    }
    expect(bpmnSize('startWorkflow')).toEqual({ width: 200, height: 80 })
    // The card notation is unchanged.
    expect(nodeSize('approval')).toEqual({ width: 296, height: 160 })
    expect(nodeSize('notification', 'cards')).toEqual({ width: 296, height: 128 })
  })

  it('keeps saved card layouts valid in BPMN notation (positions are top-left, shapes are smaller)', async () => {
    const def = normalizeDefinition(purchase)
    def.uiLayout = { positions: await computeLayout(def) }
    expect(needsAutoLayout(def, 'cards')).toBe(false)
    expect(needsAutoLayout(def, 'bpmn')).toBe(false)
    expect(hasPoorLayout(def, 'bpmn')).toBe(false)
  })

  it('lays out BPMN shapes without overlaps, with room for the names beneath events and gateways', async () => {
    const def = normalizeDefinition(purchase)
    const positions = await computeLayout(def, undefined, 'bpmn')
    const laid = { ...def, uiLayout: { positions } }
    expect(needsAutoLayout(laid, 'bpmn')).toBe(false)
    const rects = def.nodes.map((n) => ({ ...(positions[n.id] as { x: number; y: number }), ...nodeSize(n.type, 'bpmn'), below: labelOutside(n.type) ? 56 : 0, id: n.id }))
    for (let i = 0; i < rects.length; i++)
      for (let j = i + 1; j < rects.length; j++) {
        const a = rects[i]!
        const b = rects[j]!
        const apart = a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height + a.below <= b.y || b.y + b.height + b.below <= a.y
        expect(apart, `${a.id} and ${b.id}`).toBe(true)
      }
  })
})
