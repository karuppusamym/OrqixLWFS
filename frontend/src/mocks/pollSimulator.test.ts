import { describe, expect, it } from 'vitest'
import purchase from '../../../examples/purchase-approval.json'
import orderStatus from '../../../examples/connections/order-status.connection.json'
import procurement from '../../../examples/connections/procurement.connection.json'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { checkAnswers, toScenario, stepControls, emptySettings } from '@/features/studio/test/scenarioModel'
import { simulateDefinition, type SimContext, type SimulationScenario } from './simulator'

/** purchase ... procure -> track (keeps checking order-status) -> notify; failed and timedOut -> notifyRejected. */
function polling(): Definition {
  const def = normalizeDefinition(structuredClone(purchase))
  def.dependencies.connections.push({ key: 'order-status', definitionVersion: 1 })
  def.nodes.push({
    id: 'track', type: 'http', label: 'Track the order', config: {
      connectionRef: 'order-status', operation: 'getStatus', inputMapping: { orderId: '/steps/procure/procurementId' },
      resultMapping: { state: '/state' }, timeoutSeconds: 10,
      poll: {
        every: 'PT5M', backoff: { multiplier: 2, maxInterval: 'PT1H' }, giveUpAfter: 'P2D', onCheckError: { maxConsecutive: 3 },
        until: { fieldRef: '/steps/track/state', operator: 'eq', value: 'shipped' },
        failWhen: { fieldRef: '/steps/track/state', operator: 'eq', value: 'cancelled' },
      },
    },
  })
  for (const t of def.transitions) if (t.id === 't9') t.to = 'track'
  def.transitions.push({ id: 'p1', from: 'track', to: 'notify', on: 'next' })
  def.transitions.push({ id: 'p2', from: 'track', to: 'notifyRejected', on: 'failed' })
  def.transitions.push({ id: 'p3', from: 'track', to: 'notifyRejected', on: 'timedOut' })
  return def
}

const context: SimContext = {
  groups: { managers: 'Managers', 'finance-approvers': 'Finance approvers' },
  connection: (key) => (key === 'order-status' ? orderStatus : key === 'procurement' ? procurement : undefined) as never,
  childDefinition: () => undefined,
  today: '2026-01-15',
  issues: [],
  validateInput: () => [],
}
const input = { title: 'Laptop', purpose: 'Replacement', costCenter: 'IT', amount: 100 }

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function track(scenario: SimulationScenario): any {
  const result = simulateDefinition(polling(), input, scenario, context) as { status: string; body?: { trace: Array<{ stepId: string }> } }
  if (result.status !== 'ok' || !result.body) throw new Error(JSON.stringify(result))
  return result.body.trace.find((e) => e.stepId === 'track')
}

describe('Keep checking until... in the browser simulator (twin of SimulationRun, docs/build/S129)', () => {
  it('plays a sequence of answers with their simulated times', () => {
    const t = track({ calls: { track: { checks: [{ result: { state: 'pending' } }, { result: { state: 'pending' } }, { result: { state: 'pending' } }, { result: { state: 'shipped' } }] } } })
    expect(t.nextStepId).toBe('notify')
    expect(t.details.checksMade).toBe(4)
    expect(t.details.checks[3].atSeconds).toBe(2100)
    expect(t.summary).toContain('4 times')
    expect(t.summary).toContain('0:35')
  })

  it('takes the failed path, the timed-out path, and stops after failing checks in a row', () => {
    const failed = track({ calls: { track: { checks: [{ result: { state: 'pending' } }, { result: { state: 'cancelled' } }] } } })
    expect(failed.transitionId).toBe('p2')
    expect(failed.wrote.failureKind).toBe('remoteFailed')
    const never = track({ calls: { track: { result: { state: 'pending' } } } })
    expect(never.transitionId).toBe('p3')
    expect(never.details.checksMade).toBe(52)
    const errors = track({ calls: { track: { checks: [{ failure: 'unavailable' }] } } })
    expect(errors.details.attention.reason).toBe('POLL_CHECKS_FAILING')
    expect(errors.details.checksMade).toBe(3)
  })

  it('ends a message wait by its safety-net check', () => {
    const def = normalizeDefinition(structuredClone(purchase))
    def.dependencies.connections.push({ key: 'order-status', definitionVersion: 1 })
    def.nodes.push({ id: 'shipped', type: 'waitForMessage', label: 'Wait for shipping', config: {
      message: 'order-shipped', correlationField: '/steps/procure/procurementId', timeoutAfter: 'P5D',
      payload: [{ name: 'state', type: 'string', required: true }],
      fallbackCheck: { connectionRef: 'order-status', operation: 'getStatus', inputMapping: { orderId: '/steps/procure/procurementId' },
        resultMapping: { state: '/state' }, timeoutSeconds: 10, every: 'PT2H', until: { fieldRef: '/steps/shipped/state', operator: 'eq', value: 'shipped' } },
    } })
    for (const t of def.transitions) if (t.id === 't9') t.to = 'shipped'
    def.transitions.push({ id: 'm1', from: 'shipped', to: 'notify', on: 'next' }, { id: 'm2', from: 'shipped', to: 'notifyRejected', on: 'timedOut' })
    const result = simulateDefinition(def, input, { messages: { shipped: { outcome: 'fallback', payload: { state: 'shipped' } } } }, context) as unknown as { body: { trace: Array<{ stepId: string; summary: string; details: Record<string, unknown> }> } }
    const wait = result.body.trace.find((e) => e.stepId === 'shipped')!
    expect(wait.details.resumedBy).toBe('fallbackCheck')
    expect(wait.summary).toContain('safety-net check every 2 hours')
  })

  it('builds the check answers of the Test tab, one per line', () => {
    const def = polling()
    const control = stepControls(def).calls.find((c) => c.id === 'track')!
    expect(control.polls).toBe(true)
    expect(checkAnswers(control, 'pending\n\nunavailable\n{"state":"shipped"}')).toEqual([
      { result: { state: 'pending' } }, { failure: 'unavailable' }, { result: { state: 'shipped' } },
    ])
    const settings = emptySettings()
    settings.calls.track = { result: 'works', answers: {}, checks: 'pending\nshipped' }
    expect(toScenario(def, settings).calls?.track).toEqual({ checks: [{ result: { state: 'pending' } }, { result: { state: 'shipped' } }] })
  })
})
