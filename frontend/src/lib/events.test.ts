import { describe, expect, it } from 'vitest'
import type { RunEvent } from '@/api/types'
import { attentionNextStep, classifyFailure, describeEvent, eventTone, outputsInWords, timelineEvents, type EventContext } from './events'

const ctx: EventContext = {
  initiatorName: 'Rhea Requester',
  labels: { manager: 'Review request', procure: 'Create purchase order', amountPolicy: 'Check purchase amount' },
  groupNames: { managers: 'Managers', 'finance-approvers': 'Finance approvers' },
  now: new Date('2026-10-08T10:00:00Z'),
}

const event = (type: string, extra: Partial<RunEvent> = {}): RunEvent => ({ seq: 1, type, nodeId: null, actor: null, occurredAt: '2026-10-08T09:00:00Z', data: {}, ...extra })

describe('timeline text in business language (docs/build/S7-frontend.md section 4 table)', () => {
  it('RUN_STARTED', () => {
    expect(describeEvent(event('RUN_STARTED', { data: { releaseNumber: 2 } }), ctx)).toBe('Rhea Requester submitted the request (version 2)')
  })

  it('TASK_CREATED names the group and the step', () => {
    expect(describeEvent(event('TASK_CREATED', { nodeId: 'manager', data: { candidateGroup: 'managers' } }), ctx)).toBe('Waiting for Managers: Review request')
  })

  it('task events use the actor and the step label', () => {
    const actor = { id: 'u', displayName: 'Mani Manager' }
    expect(describeEvent(event('TASK_CLAIMED', { nodeId: 'manager', actor }), ctx)).toBe('Mani Manager picked up Review request')
    expect(describeEvent(event('TASK_RELEASED', { nodeId: 'manager', actor, data: { candidateGroup: 'managers' } }), ctx)).toBe('Mani Manager returned Review request to Managers')
    expect(describeEvent(event('TASK_REASSIGNED', { nodeId: 'manager', actor, data: { to: 'Max Manager' } }), ctx)).toBe('Mani Manager reassigned Review request to Max Manager')
    expect(describeEvent(event('TASK_DECIDED', { nodeId: 'manager', actor, data: { action: 'approve' } }), ctx)).toBe('Mani Manager approved Review request')
    expect(describeEvent(event('TASK_DECIDED', { nodeId: 'manager', actor, data: { action: 'reject' } }), ctx)).toBe('Mani Manager rejected Review request')
  })

  it('DECISION_EVALUATED puts outputs in words', () => {
    expect(describeEvent(event('DECISION_EVALUATED', { nodeId: 'amountPolicy', data: { outputs: { route: 'financeReview' } } }), ctx)).toBe('Check purchase amount: Route is finance review')
    expect(outputsInWords({ a: 1, bigNumber: 2 })).toBe('A is 1, Big number is 2')
  })

  it('effects: success, failure with attempts, unknown outcome', () => {
    expect(describeEvent(event('EFFECT_SUCCEEDED', { nodeId: 'procure', data: { remoteReference: 'PO-1001' } }), ctx)).toBe('Create purchase order completed (reference PO-1001)')
    expect(
      describeEvent(event('EFFECT_SUCCEEDED', { nodeId: 'procure', data: { summary: "Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)" } }), ctx),
    ).toBe("Vendor risk assistant checked 'Acme Ltd' using Vendor registry: risk 72 (high)")
    expect(describeEvent(event('EFFECT_FAILED', { nodeId: 'procure', data: { attempt: 2, maxAttempts: 3, nextRetryAt: '2026-10-08T14:35:00Z' } }), ctx)).toMatch(/^Create purchase order did not respond\. Attempt 2 of 3\. Next retry at .*\.$/)
    expect(describeEvent(event('EFFECT_FAILED', { nodeId: 'procure', data: { attempt: 1 } }), ctx)).toBe('Create purchase order did not respond. Attempt 1.')
    for (const type of ['EFFECT_UNKNOWN', 'RUN_NEEDS_ATTENTION']) {
      expect(describeEvent(event(type, { nodeId: 'procure' }), ctx)).toBe('Create purchase order needs attention — an operator has been alerted.')
    }
  })

  describe('EFFECT_FAILED says what actually went wrong', () => {
    const failed = (data: Record<string, unknown>) => describeEvent(event('EFFECT_FAILED', { nodeId: 'procure', data: { attempt: 2, maxAttempts: 3, ...data } }), ctx)

    it('not sent: the other system could not be reached (DNS, refused, connect timeout, no egress)', () => {
      for (const errorCode of ['CONNECT_FAILED', 'CONNECT_TIMEOUT', 'CONNECTION_UNAVAILABLE', 'EGRESS_NOT_ALLOWED']) {
        expect(failed({ classification: 'retryable', errorCode })).toBe('Create purchase order could not be sent. The other system could not be reached. Attempt 2 of 3.')
      }
    })

    it('timed out: no answer in time, or the connection dropped', () => {
      expect(failed({ classification: 'retryable', errorCode: 'TIMEOUT' })).toBe('Create purchase order timed out. The other system did not answer in time. Attempt 2 of 3.')
      expect(failed({ classification: 'retryable', errorCode: 'CONNECTION_RESET' })).toContain('timed out')
      expect(failed({ classification: 'retryable', httpStatus: 408, errorCode: 'HTTP_408' })).toContain('timed out')
    })

    it('rejected: a 4xx answer names the status', () => {
      expect(failed({ classification: 'permanent', httpStatus: 403, errorCode: 'HTTP_403' })).toBe('Create purchase order was rejected by the other system (HTTP 403). Attempt 2 of 3.')
      expect(failed({ classification: 'permanent', httpStatus: 422 })).toContain('rejected by the other system (HTTP 422)')
    })

    it('rate limited: 429', () => {
      expect(failed({ classification: 'retryable', httpStatus: 429, errorCode: 'RATE_LIMITED' })).toBe('Create purchase order was rate limited: the other system asked us to slow down. Attempt 2 of 3.')
    })

    it('server error: 5xx, whatever code the server used for it', () => {
      expect(failed({ classification: 'retryable', httpStatus: 503, errorCode: 'HTTP_503' })).toBe('Create purchase order hit an error in the other system (HTTP 503). Attempt 2 of 3.')
      expect(failed({ classification: 'retryable', httpStatus: 500, errorCode: 'UNEXPECTED_STATUS', nextRetryAt: '2026-10-08T14:35:00Z' })).toMatch(/hit an error in the other system \(HTTP 500\)\. Attempt 2 of 3\. Next retry at .*\.$/)
    })

    it('outcome unknown: the other system may have done it, so it is never called a plain failure', () => {
      const text = failed({ classification: 'unknown', errorCode: 'RESPONSE_INVALID' })
      expect(text).toBe('The outcome of Create purchase order is unknown. The other system may have completed it. Attempt 2 of 3.')
      expect(text).not.toMatch(/failed|did not respond/i)
      expect(failed({ errorCode: 'UNEXPECTED_STATUS' })).toContain('outcome of Create purchase order is unknown')
    })

    it('keeps the old wording only when the server gave no reason at all', () => {
      expect(failed({})).toBe('Create purchase order did not respond. Attempt 2 of 3.')
      expect(classifyFailure({ errorCode: 'SOMETHING_NEW' })).toBe('unspecified')
      expect(classifyFailure({ classification: 'retryable', errorCode: 'INTERNAL_ERROR' })).toBe('unspecified')
    })

    it('the HTTP status wins over the error code', () => {
      expect(classifyFailure({ httpStatus: 503, errorCode: 'CONNECT_FAILED' })).toBe('server-error')
      expect(classifyFailure({ httpStatus: 429, classification: 'permanent' })).toBe('rate-limited')
    })
  })

  it('timeline markers are neutral for ordinary events, status hues for outcomes, danger only for problems', () => {
    expect(eventTone(event('RUN_STARTED'))).toBe('neutral')
    expect(eventTone(event('TASK_CLAIMED'))).toBe('neutral')
    expect(eventTone(event('DECISION_EVALUATED'))).toBe('neutral')
    expect(eventTone(event('NOTIFICATION_CREATED'))).toBe('neutral')
    expect(eventTone(event('TASK_CREATED'))).toBe('waiting')
    expect(eventTone(event('TASK_DECIDED', { data: { action: 'approve' } }))).toBe('success')
    expect(eventTone(event('TASK_DECIDED', { data: { action: 'reject' } }))).toBe('rejected')
    expect(eventTone(event('RUN_COMPLETED', { data: { outcomeKind: 'success' } }))).toBe('success')
    expect(eventTone(event('RUN_COMPLETED', { data: { outcomeKind: 'rejected' } }))).toBe('rejected')
    expect(eventTone(event('RUN_CANCELLED'))).toBe('cancelled')
    for (const type of ['EFFECT_FAILED', 'EFFECT_UNKNOWN', 'RUN_NEEDS_ATTENTION']) expect(eventTone(event(type))).toBe('danger')
  })

  it('operator actions, completion and cancellation', () => {
    const actor = { id: 'o', displayName: 'Olga Operator' }
    expect(describeEvent(event('OPERATOR_RETRY', { nodeId: 'procure', actor }), ctx)).toBe('Olga Operator retried Create purchase order')
    expect(describeEvent(event('EFFECT_RECONCILED', { nodeId: 'procure', actor }), ctx)).toBe('Olga Operator confirmed the outcome of Create purchase order')
    expect(describeEvent(event('RUN_COMPLETED', { data: { outcome: 'Fulfilled' } }), ctx)).toBe('Finished: Fulfilled')
    expect(describeEvent(event('RUN_CANCELLED', { actor, data: { reason: 'Duplicate request' } }), ctx)).toBe('Olga Operator cancelled the request: Duplicate request')
    expect(describeEvent(event('RUN_SUSPENDED', { actor, data: { reason: 'Audit hold' } }), ctx)).toBe('Olga Operator suspended the request: Audit hold')
    expect(describeEvent(event('RUN_RESUMED', { actor, data: { reason: 'Audit closed' } }), ctx)).toBe('Olga Operator resumed the request: Audit closed')
  })

  it('prefers the server-provided node label, then data, then known labels, then a humanized id', () => {
    expect(describeEvent(event('TASK_CLAIMED', { nodeId: 'procure', nodeLabel: 'Create purchase order' }), ctx)).toContain('Create purchase order')
    expect(describeEvent(event('TASK_CLAIMED', { nodeId: 'manager', nodeLabel: 'Server label' }), ctx)).toContain('Server label')
    expect(describeEvent(event('TASK_CLAIMED', { nodeId: 'manager', data: { label: 'Data label' } }), ctx)).toContain('Data label')
    expect(describeEvent(event('TASK_CLAIMED', { nodeId: 'unknownStep' }), ctx)).toContain('Unknown step')
  })

  it('shows unknown event types readably instead of failing', () => {
    expect(describeEvent(event('SOMETHING_NEW'), ctx)).toBe('Something new')
  })

  it('never calls an unknown outcome a plain failure', () => {
    for (const reason of ['RETRIES_EXHAUSTED', 'EFFECT_UNKNOWN', 'EFFECT_REJECTED', 'RESPONSE_INVALID', 'CONNECTION_UNAVAILABLE', 'POLICY_DENIED', 'INTERNAL_ERROR']) {
      expect(attentionNextStep(reason)).not.toMatch(/^Failed\b/)
    }
    expect(attentionNextStep('EFFECT_UNKNOWN')).toMatch(/Check the other system/)
    expect(attentionNextStep('RUN_WAITING_ON_NOTHING')).toMatch(/Inspect the request and engine history/)
    expect(attentionNextStep('RUN_WAITING_ON_NOTHING')).not.toMatch(/retry/i)
  })

  it('folds an attention event into the unknown-outcome event before it', () => {
    const list = [
      event('EFFECT_UNKNOWN', { seq: 5, nodeId: 'procure' }),
      event('RUN_NEEDS_ATTENTION', { seq: 6, nodeId: 'procure' }),
      event('RUN_NEEDS_ATTENTION', { seq: 7, nodeId: 'other' }),
    ]
    expect(timelineEvents(list).map((e) => e.seq)).toEqual([5, 7])
  })
})
