import { describe, expect, it } from 'vitest'
import purchase from '../../../examples/purchase-approval.json'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { validateDefinition } from './validate'

/** start -> check (worker) -> manager ... : the purchase example with a worker step in front. */
function withWorker(config: Record<string, unknown>): Definition {
  const def = normalizeDefinition(structuredClone(purchase))
  def.nodes.push({ id: 'check', type: 'externalTask', label: 'Check the address', config })
  for (const t of def.transitions) if (t.from === 'start' && t.to === 'manager') t.to = 'check'
  def.transitions.push({ id: 'x1', from: 'check', to: 'manager', on: 'next' })
  return def
}

const codes = (def: Definition) => validateDefinition(def, { groups: ['managers', 'finance'], connections: [], isTestWorkspace: false }).map((i) => i.code)

describe('worker step in the browser mock (EX01)', () => {
  const valid = { topic: 'address-check', inputMapping: { purpose: '/input/purpose' }, result: [{ name: 'score', type: 'number', required: true }] }

  it('accepts a complete worker step and rejects a bad topic, duplicate results and empty inputs', () => {
    expect(codes(withWorker(valid))).not.toContain('WF_SCHEMA_INVALID')
    expect(codes(withWorker({ ...valid, topic: 'Address Check' }))).toContain('WF_SCHEMA_INVALID')
    expect(codes(withWorker({ ...valid, result: [{ name: 'score', type: 'number' }, { name: 'score', type: 'string' }] }))).toContain('WF_EXTERNAL_RESULT')
    expect(codes(withWorker({ ...valid, inputMapping: { purpose: '' } }))).toContain('WF_EXTERNAL_INPUT_UNKNOWN')
  })
})
