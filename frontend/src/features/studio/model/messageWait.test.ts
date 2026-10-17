import { describe, expect, it } from 'vitest'
import example from '../../../../../examples/order-payment-wait.json'
import { allFields, availableFields, rewriteRefs, usagesOfRef } from './fieldRefs'
import { KINDS, PALETTE_KINDS, defaultConfig } from './kinds'
import { canConnect, routeForHandle } from './ops'
import { messageWaitPreview, nodeSubtitle } from './summary'
import { normalizeDefinition } from './types'

const def = () => normalizeDefinition(structuredClone(example))
const wait = (d = def()) => d.nodes.find((n) => n.id === 'waitPayment')!

describe('wait for a message: studio model', () => {
  it('is offered in the palette with a safe default', () => {
    expect(PALETTE_KINDS).toContain('waitForMessage')
    expect(KINDS.waitForMessage.paletteLabel).toBe('Wait for a message')
    expect(defaultConfig('waitForMessage')).toEqual({ message: 'message-received', correlationField: '', payload: [] })
  })

  it('reads in plain language', () => {
    const d = def()
    expect(messageWaitPreview(d, wait(d).config)).toBe("Waits until a message named payment-received arrives for this request's Order number. If nothing arrives in time, the request follows Timed out.")
    delete wait(d).config.timeoutAfter
    expect(messageWaitPreview(d, wait(d).config)).toBe("Waits until a message named payment-received arrives for this request's Order number.")
    expect(messageWaitPreview(d, { message: 'x-y', correlationField: '' })).toContain('choose which field identifies it')
    expect(nodeSubtitle(d, wait(d))).toBe('payment-received · Order number')
  })

  it('offers the declared values as outputs of the step', () => {
    const d = def()
    const refs = allFields(d).filter((f) => f.ref.startsWith('/steps/waitPayment/')).map((f) => [f.ref, f.type, f.label])
    expect(refs).toEqual([
      ['/steps/waitPayment/paymentReference', 'string', 'Payment reference'],
      ['/steps/waitPayment/amountPaid', 'number', 'Amount paid'],
    ])
    expect(availableFields(d, 'notifyPaid').map((f) => f.ref)).toContain('/steps/waitPayment/paymentReference')
  })

  it('routes: next, and timed out only with a time limit', () => {
    const d = def()
    expect(routeForHandle(wait(d), 'timedOut')).toBe('timedOut')
    expect(routeForHandle(wait(d), 'next')).toBe('next')
    expect(canConnect(d, { from: 'waitPayment', to: 'notifyPaid', on: 'condition' })).toMatchObject({ ok: false })
    const noLimit = def()
    delete wait(noLimit).config.timeoutAfter
    expect(canConnect(noLimit, { from: 'waitPayment', to: 'notifyCancelled', on: 'timedOut' })).toMatchObject({ ok: false })
  })

  it('follows a renamed field', () => {
    const d = def()
    expect(usagesOfRef(d, (r) => r === '/input/orderNumber')).toContain('the step "Wait for the payment"')
    rewriteRefs(d, (r) => r.replace('/input/orderNumber', '/input/orderNo'))
    expect(wait(d).config.correlationField).toBe('/input/orderNo')
  })
})
