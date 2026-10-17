import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import example from '../../../examples/order-payment-wait.json'
import { createRun, cancelRun, engineConfig, toRunDetail, resumeTimers } from './engine'
import { sendMessage, payloadErrors, payloadShapeErrors } from './messages'
import { makeRelease, seedDb } from './seed'
import { clock, db, newId, snapshotDb, restoreDb, userByName, WS_PURCHASING, type MockRun, type MockWorkflow } from './state'
import { validateDefinition } from './validate'

const pending: Array<() => void> = []
const original = engineConfig.schedule

function definition(): Definition {
  return normalizeDefinition(structuredClone(example))
}

function workflow(): MockWorkflow {
  const designer = userByName('dana.designer')
  const wf: MockWorkflow = {
    id: 'cccccccc-0000-4000-8000-0000000000e1', wsId: WS_PURCHASING, key: 'order-payment-wait', name: 'Order payment',
    description: null, ownerId: designer.id, drafts: [], releases: [], activeReleaseId: null, version: 1,
    createdAt: clock.iso(), updatedAt: clock.iso(),
  }
  const release = makeRelease(wf.id, 1, 1, definition(), designer.id)
  wf.releases.push(release)
  wf.activeReleaseId = release.id
  db.workflows.set(wf.id, wf)
  return wf
}

function start(orderNumber: string): MockRun {
  const wf = db.workflows.get('cccccccc-0000-4000-8000-0000000000e1') ?? workflow()
  return createRun({ wsId: WS_PURCHASING, workflow: wf, release: wf.releases[0]!, initiator: userByName('rhea.requester'), businessKey: null, input: { orderNumber, customerName: 'Acme' } })
}

const send = (key: string, payload?: Record<string, unknown>) => sendMessage(WS_PURCHASING, 'payment-received', key, payload, userByName('olga.operator').id, newId)

describe('mock engine: wait for a message', () => {
  beforeEach(() => {
    seedDb({ sampleRuns: false })
    pending.length = 0
    engineConfig.schedule = (fn) => { pending.push(fn) }
  })
  afterEach(() => { engineConfig.schedule = original })

  it('waits, shows the message and its time limit, and continues when the message arrives', () => {
    const run = start('SO-1')
    const step = toRunDetail(run, userByName('olga.operator')).currentSteps[0]!
    expect(step).toMatchObject({ kind: 'wait', messageName: 'payment-received', nodeId: 'waitPayment' })
    expect(step.dueAt).not.toBeNull()
    expect(run.lifecycle).toBe('waiting')
    const result = send('SO-1', { paymentReference: 'PAY-9', amountPaid: 12.5 })
    expect(result).toMatchObject({ status: 202, body: { status: 'delivered', matched: 1 } })
    pending.splice(0).forEach((fn) => fn())
    expect(run.lifecycle).toBe('completed')
    expect(run.outcome).toBe('Order paid')
    expect(run.output).toEqual({ paymentReference: 'PAY-9' })
    const ended = run.events.find((e) => e.type === 'WAIT_ENDED')!
    expect(ended.data).toMatchObject({ message: 'payment-received', outcome: 'received', buffered: false, fields: ['paymentReference', 'amountPaid'] })
  })

  it('keeps a message for another key and does not touch the request', () => {
    const run = start('SO-2')
    const result = send('SO-OTHER', { paymentReference: 'P' })
    expect(result).toMatchObject({ body: { status: 'buffered', matched: 0 } })
    expect(run.lifecycle).toBe('waiting')
  })

  it('uses a message that arrived before the wait started, once', () => {
    expect(send('SO-3', { paymentReference: 'EARLY' })).toMatchObject({ body: { status: 'buffered' } })
    const run = start('SO-3')
    pending.splice(0).forEach((fn) => fn())
    expect(run.lifecycle).toBe('completed')
    expect(run.output).toEqual({ paymentReference: 'EARLY' })
    expect(run.events.find((e) => e.type === 'WAIT_ENDED')!.data).toMatchObject({ buffered: true })
    const second = start('SO-3')
    pending.splice(0).forEach((fn) => fn())
    expect(second.outcome).toBe('Cancelled no payment') // nothing was left to consume, so only the time limit ended it
  })

  it('preserves early delivery across reloads without relying on a queued callback', () => {
    send('SO-RELOAD', { paymentReference: 'DURABLE' })
    const beforeStart = snapshotDb()
    expect(restoreDb(beforeStart)).toBe(true)
    expect(db.messageBuffer?.[0]).toMatchObject({ status: 'buffered', payload: { paymentReference: 'DURABLE' } })
    const run = start('SO-RELOAD')
    // Simulate reload immediately after the API response, losing every scheduled callback.
    const afterStart = snapshotDb()
    pending.length = 0
    expect(restoreDb(afterStart)).toBe(true)
    resumeTimers()
    pending.splice(0).forEach((fn) => fn())
    const restored = db.runs.get(run.id)!
    expect(restored.lifecycle).toBe('completed')
    expect(restored.outcome).toBe('Order paid')
    expect(restored.output).toEqual({ paymentReference: 'DURABLE' })
    expect(restored.events.filter((e) => e.type === 'WAIT_ENDED')).toHaveLength(1)
    expect(db.messageBuffer?.[0]).toMatchObject({ status: 'delivered', payload: {} })
    const second = start('SO-RELOAD')
    expect(second.lifecycle).toBe('waiting')
  })

  it('follows the timed out route when the time limit passes', () => {
    const run = start('SO-4')
    pending.splice(0).forEach((fn) => fn())
    expect(run.lifecycle).toBe('completed')
    expect(run.outcome).toBe('Cancelled no payment')
    expect(run.events.find((e) => e.type === 'WAIT_ENDED')!.data).toMatchObject({ outcome: 'timedOut' })
    // The late message is kept, not delivered.
    expect(send('SO-4', { paymentReference: 'LATE' })).toMatchObject({ body: { status: 'buffered' } })
  })

  it('checks the payload against the declared values and delivers nothing when it does not fit', () => {
    const run = start('SO-5')
    expect(send('SO-5', { amountPaid: 1 })).toMatchObject({ errors: [{ pointer: '/payload/paymentReference', code: 'required' }] })
    expect(send('SO-5', { paymentReference: 'P', amountPaid: 'lots' })).toMatchObject({ errors: [{ pointer: '/payload/amountPaid', code: 'type' }] })
    expect(send('SO-5', { paymentReference: 'P', surprise: 1 })).toMatchObject({ errors: [{ pointer: '/payload/surprise', code: 'unknown' }] })
    expect(run.lifecycle).toBe('waiting')
    expect(payloadShapeErrors({ a: { b: 1 } })).toHaveLength(1)
    expect(payloadShapeErrors(Object.fromEntries(Array.from({ length: 11 }, (_, i) => [`v${i}`, i])))).toHaveLength(1)
    expect(payloadErrors(definition().nodes[1]!, { paymentReference: 'x' }).errors).toEqual([])
  })

  it('does not deliver to a cancelled request', () => {
    const run = start('SO-6')
    cancelRun(run, userByName('olga.operator'), 'Customer withdrew')
    expect(send('SO-6', { paymentReference: 'P' })).toMatchObject({ body: { status: 'buffered' } })
    expect(run.lifecycle).toBe('cancelled')
  })

  it('validates the step like the compiler', () => {
    const ctx = { groups: [], isTestWorkspace: true, publishedWorkflows: [] } as never
    expect(validateDefinition(definition(), ctx).filter((i) => i.severity === 'blocker')).toEqual([])
    const bad = definition()
    bad.nodes[1]!.config.message = 'Payment Received'
    bad.nodes[1]!.config.correlationField = '/input/missing'
    bad.transitions = bad.transitions.filter((t) => t.on !== 'timedOut')
    const codes = validateDefinition(bad, ctx).map((i) => i.code)
    expect(codes).toContain('WF_SCHEMA_INVALID')
    expect(codes).toContain('WF_MESSAGE_CORRELATION_UNKNOWN')
    expect(codes).toContain('WF_MESSAGE_TIMEOUT_ROUTE')
  })
})
