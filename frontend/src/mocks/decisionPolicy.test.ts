import { describe, expect, it } from 'vitest'
import purchase from '../../../examples/purchase-approval.json'
import { normalizeDefinition, type Condition, type Definition } from '@/features/studio/model/types'
import { evaluateTable } from './engine'
import { validateDefinition } from './validate'

const amount = (operator: 'gte' | 'lt', value: number) => ({ fieldRef: '/input/amount', operator, value })

function withRows(hitPolicy: 'UNIQUE' | 'FIRST', ...whens: Condition[]): Definition {
  const def = normalizeDefinition(structuredClone(purchase))
  const table = def.decisions[0]!
  table.hitPolicy = hitPolicy
  table.rows = whens.map(when => ({ when, then: { ...table.default } }))
  return def
}

const codes = (def: Definition) => validateDefinition(def, { groups: ['managers', 'finance'], connections: [], isTestWorkspace: false }).map(i => i.code)

describe('decision hit policies (RL04)', () => {
  it('rejects overlapping rows under UNIQUE but allows them under FIRST', () => {
    expect(codes(withRows('UNIQUE', amount('gte', 10000), amount('gte', 5000)))).toContain('WF_DECISION_OVERLAP')
    expect(codes(withRows('FIRST', amount('gte', 10000), amount('gte', 5000)))).not.toContain('WF_DECISION_OVERLAP')
  })

  it('treats an OR row as several alternatives when checking overlap', () => {
    const outside: Condition = { any: [amount('lt', 100), amount('gte', 10000)] }
    expect(codes(withRows('UNIQUE', outside))).not.toContain('WF_DECISION_OVERLAP')
    expect(codes(withRows('UNIQUE', outside, amount('gte', 5000)))).toContain('WF_DECISION_OVERLAP')
    expect(codes(withRows('UNIQUE', outside, { all: [amount('gte', 100), { fieldRef: '/input/amount', operator: 'lt', value: 200 }] }))).not.toContain('WF_DECISION_OVERLAP')
  })

  it('evaluates OR rows with any and the first matching row wins', () => {
    const def = withRows('FIRST', { any: [amount('lt', 100), { fieldRef: '/input/costCenter', operator: 'eq', value: 'HR' }] }, amount('gte', 5000))
    const table = def.decisions[0]!
    expect(evaluateTable(table, { '/input/amount': 50, '/input/costCenter': 'IT' }).matchedRow).toBe(0)
    expect(evaluateTable(table, { '/input/amount': 9000, '/input/costCenter': 'HR' }).matchedRow).toBe(0)
    expect(evaluateTable(table, { '/input/amount': 9000, '/input/costCenter': 'IT' }).matchedRow).toBe(1)
    expect(evaluateTable(table, { '/input/amount': 500, '/input/costCenter': 'IT' }).usedDefault).toBe(true)
  })
})
