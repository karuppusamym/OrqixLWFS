import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import onboarding from '../../../../../examples/new-hire-onboarding.json'
import { normalizeDefinition } from '../model/types'
import { answerBody, emptySettings, finishOutcomes, meetsExpectation, NEEDS_ATTENTION, stepControls, toScenario } from './scenarioModel'

const def = normalizeDefinition(purchase)

describe('test settings', () => {
  it('lists the steps where something is decided, in document order', () => {
    const c = stepControls(def)
    expect(c.approvals.map((a) => a.id)).toEqual(['manager', 'finance'])
    expect(c.calls).toEqual([{ id: 'procure', label: 'Create purchase order', connection: 'procurement', operation: 'createOrder', results: [{ key: 'procurementId', pointer: '/id' }] }])
    expect(stepControls(normalizeDefinition(onboarding)).approvals.find((a) => a.id === 'it')?.hasTimeout).toBe(true)
    expect(stepControls(normalizeDefinition(onboarding)).children[0]).toMatchObject({ id: 'orderEquipment', mode: 'once', max: 1 })
  })

  it('builds a scenario with only what differs from the defaults', () => {
    expect(toScenario(def, emptySettings())).toEqual({})
    const settings = emptySettings()
    settings.approvals.manager = { outcome: 'approve', reason: '' }
    settings.approvals.finance = { outcome: 'reject', reason: '  Over budget ' }
    settings.calls.procure = { result: 'unavailable', answers: {} }
    expect(toScenario(def, settings)).toEqual({ approvals: { finance: { outcome: 'reject', reason: 'Over budget' } }, calls: { procure: { failure: 'unavailable' } } })
  })

  it('ignores a time-limit choice on a step without a time limit and settings of steps that no longer exist', () => {
    const settings = emptySettings()
    settings.approvals.manager = { outcome: 'timeout', reason: '' }
    settings.approvals.gone = { outcome: 'reject', reason: '' }
    expect(toScenario(def, settings)).toEqual({})
  })

  it('turns answers into the body of the answer of the system', () => {
    expect(answerBody([{ key: 'procurementId', pointer: '/id' }], { procurementId: 'PO-7' })).toEqual({ id: 'PO-7' })
    expect(
      answerBody(
        [
          { key: 'band', pointer: '/risk/band' },
          { key: 'score', pointer: '/risk/score' },
        ],
        { band: 'high', score: '82' },
      ),
    ).toEqual({ risk: { band: 'high', score: 82 } })
    expect(answerBody([{ key: 'a', pointer: '/a' }], { a: '  ' })).toBeNull()
  })

  it('keeps outcomes of a child workflow in order and drops empty trailing ones', () => {
    const withChild = normalizeDefinition({
      ...purchase,
      nodes: [...purchase.nodes, { id: 'child', type: 'startWorkflow', label: 'Review', config: { workflowRef: 'review', inputMapping: {}, resultMapping: {}, repeat: { maxIterations: 3, untilOutcome: 'Accepted' } } }],
    })
    const settings = emptySettings()
    settings.children.child = { outcomes: ['Rejected', '', 'Accepted', 'Rejected', ''] }
    expect(toScenario(withChild, settings).children).toEqual({ child: { outcomes: ['Rejected', '', 'Accepted'] } })
    settings.simulateChildren = true
    expect(toScenario(withChild, settings).simulateChildren).toBe(true)
  })

  it('judges an outcome against the expectation', () => {
    expect(meetsExpectation({ status: 'finished', businessOutcome: 'Fulfilled' }, 'Fulfilled')).toBe(true)
    expect(meetsExpectation({ status: 'finished', businessOutcome: 'Rejected' }, 'Fulfilled')).toBe(false)
    expect(meetsExpectation({ status: 'needsAttention', businessOutcome: null }, NEEDS_ATTENTION)).toBe(true)
    expect(meetsExpectation({ status: 'needsAttention', businessOutcome: null }, 'Fulfilled')).toBe(false)
    expect(meetsExpectation({ status: 'finished', businessOutcome: 'x' }, null)).toBe(true)
    expect(finishOutcomes(def)).toEqual(['Fulfilled', 'Rejected'])
  })
})
