import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import onboarding from '../../../../../examples/new-hire-onboarding.json'
import { normalizeDefinition } from '../model/types'
import { answerBody, emptyAssertions, emptySettings, finishOutcomes, meetsExpectation, NEEDS_ATTENTION, stepControls, toAssertions, toScenario } from './scenarioModel'

const def = normalizeDefinition(purchase)

describe('test settings', () => {
  it('lists the steps where something is decided, in document order', () => {
    const c = stepControls(def)
    expect(c.approvals.map((a) => a.id)).toEqual(['manager', 'finance'])
    expect(c.calls).toEqual([{ id: 'procure', label: 'Create purchase order', connection: 'procurement', connectionVersion: 1, timeoutSeconds: 20, operation: 'createOrder', results: [{ key: 'procurementId', pointer: '/id' }] }])
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

describe('mocks and assertions (STU03)', () => {
  it('sends a full call mock only with what parses, and keeps legacy partial answers', () => {
    const settings = emptySettings()
    settings.calls.procure = { result: 'works', answers: {}, mock: { status: '201', body: '{"id":"PO-1","status":"CREATED"}', delaySeconds: '4' } }
    expect(toScenario(def, settings).calls).toEqual({ procure: { status: 201, body: { id: 'PO-1', status: 'CREATED' }, delaySeconds: 4 } })
    settings.calls.procure = { result: 'works', answers: { procurementId: 'PO-2' }, mock: { status: '503', body: '{ broken', delaySeconds: 'x' } }
    expect(toScenario(def, settings).calls).toEqual({ procure: { status: 503, result: { id: 'PO-2' } } })
    settings.calls.procure = { result: 'timeout', answers: {} }
    expect(toScenario(def, settings).calls).toEqual({ procure: { failure: 'timeout' } })
    settings.calls.procure = { result: 'works', answers: { procurementId: 'PO-3' } }
    expect(toScenario(def, settings).calls).toEqual({ procure: { result: { id: 'PO-3' } } })
  })

  it('turns the typed assertions into the API shape and leaves out half-filled rows', () => {
    const a = emptyAssertions()
    a.outputs = [
      { field: 'procurementId', op: 'contains', value: 'PO', min: '', max: '' },
      { field: 'total', op: 'range', min: '1', max: '', value: '' },
      { field: '', op: 'equals', value: 'x', min: '', max: '' },
      { field: 'amount', op: 'equals', value: '5000', min: '', max: '' },
    ]
    a.path = { steps: ['start', 'manager'], match: 'inOrder' }
    a.rules = [{ stepId: 'amountPolicy', rule: '1' }, { stepId: 'amountPolicy', rule: 'otherwise' }]
    a.approvals = [{ stepId: 'manager', decision: 'rejected' }]
    expect(toAssertions(a, 'Fulfilled')).toEqual({
      outcome: 'Fulfilled',
      outputs: [{ field: 'procurementId', op: 'contains', value: 'PO' }, { field: 'total', op: 'range', min: 1 }, { field: 'amount', op: 'equals', value: 5000 }],
      path: { steps: ['start', 'manager'], match: 'inOrder' },
      rules: [{ stepId: 'amountPolicy', rule: 1 }, { stepId: 'amountPolicy', rule: 'otherwise' }],
      approvals: [{ stepId: 'manager', decision: 'rejected' }],
    })
    expect(toAssertions(emptyAssertions(), null)).toBeUndefined()
  })

  it('lists decision steps for rule checks', () => {
    expect(stepControls(def).decisions).toEqual([{ id: 'amountPolicy', label: 'Check purchase amount', decisionRef: 'amount-policy', rows: 1 }])
  })
})