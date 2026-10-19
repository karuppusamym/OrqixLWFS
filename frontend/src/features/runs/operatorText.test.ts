import { describe, expect, it } from 'vitest'
import type { Effect } from '@/api/types'
import { candidateEffects, isAgentWrite } from './operatorText'

const effect = (operation: string, state: Effect['state']): Effect => ({
  id: `${operation}-${state}`, nodeId: 'assess', connectionKey: 'vendor-writer', operation, effectClass: 'nonIdempotent',
  state, attempts: 1, updatedAt: '2026-10-18T10:00:00Z',
})

describe('operator effect candidates (IN04)', () => {
  it('never offers an agent write to the step reconcile or retry dialogs', () => {
    const write = effect('agentWrite.email', 'unknown')
    const call = effect('createOrder', 'unknown')
    expect(isAgentWrite(write)).toBe(true)
    expect(isAgentWrite(call)).toBe(false)
    expect(candidateEffects([write, call, effect('createOrder', 'succeeded')])).toEqual([call])
  })
})
