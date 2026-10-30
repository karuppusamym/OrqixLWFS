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

describe('effective-dated rows (RL03)', () => {
  const dated = (hitPolicy: 'UNIQUE' | 'FIRST', ...windows: Array<{ from?: string; to?: string }>): Definition => {
    const def = withRows(hitPolicy, ...windows.map(() => amount('gte', 5000)))
    def.decisions[0]!.rows.forEach((row, i) => { row.effective = windows[i] as { from?: string; to?: string } })
    return def
  }

  it('allows the same condition in disjoint windows under UNIQUE and rejects shared days', () => {
    expect(codes(dated('UNIQUE', { to: '2026-06-30' }, { from: '2026-07-01' }))).not.toContain('WF_DECISION_OVERLAP')
    expect(codes(dated('UNIQUE', { to: '2026-07-01' }, { from: '2026-07-01' }))).toContain('WF_DECISION_OVERLAP')
    expect(codes(dated('UNIQUE', { from: '2026-07-01', to: '2026-01-01' }))).toContain('WF_DECISION_WINDOW_INVALID')
  })

  it('applies the row in force on the given day, else the default', () => {
    const table = dated('UNIQUE', { to: '2026-06-30' }, { from: '2026-07-01' }).decisions[0]!
    const values = { '/input/amount': 6000 }
    expect(evaluateTable(table, values, '2026-03-01').matchedRow).toBe(0)
    expect(evaluateTable(table, values, '2026-07-01').matchedRow).toBe(1)
    const bounded = dated('UNIQUE', { from: '2026-01-01', to: '2026-12-31' }).decisions[0]!
    expect(evaluateTable(bounded, values, '2025-12-31').usedDefault).toBe(true)
  })
})

describe('lists, ranges and the new hit policies (docs/build/S121)', () => {
  const inList: Condition = { fieldRef: '/input/costCenter', operator: 'in', value: ['IT', 'HR'] }
  const middle: Condition = { fieldRef: '/input/amount', operator: 'between', value: { from: 1000, to: 5000, includeTo: false } }

  it('checks lists and ranges and proves their overlaps', () => {
    expect(codes(withRows('UNIQUE', middle, amount('gte', 5000)))).not.toContain('WF_DECISION_OVERLAP')
    expect(codes(withRows('UNIQUE', { ...middle, value: { from: 1000, to: 5000 } } as Condition, amount('gte', 5000)))).toContain('WF_DECISION_OVERLAP')
    expect(codes(withRows('UNIQUE', { fieldRef: '/input/costCenter', operator: 'in', value: ['IT', 'SALES'] }))).toContain('WF_LITERAL_NOT_IN_DOMAIN')
    expect(codes(withRows('UNIQUE', { fieldRef: '/input/costCenter', operator: 'between', value: { from: 1, to: 2 } }))).toContain('WF_OPERATOR_TYPE')
    const table = withRows('UNIQUE', { all: [inList, middle] }).decisions[0]!
    expect(evaluateTable(table, { '/input/amount': 4999, '/input/costCenter': 'HR' }).matchedRow).toBe(0)
    expect(evaluateTable(table, { '/input/amount': 5000, '/input/costCenter': 'HR' }).usedDefault).toBe(true)
    expect(evaluateTable(table, { '/input/amount': 2000, '/input/costCenter': 'OPS' }).usedDefault).toBe(true)
  })

  it('refuses lists and ranges in route conditions', () => {
    const def = normalizeDefinition(structuredClone(purchase))
    const route = def.transitions.find(t => t.condition)!
    route.condition = { fieldRef: '/steps/amountPolicy/route', operator: 'in', value: ['financeReview'] }
    expect(codes(def)).toContain('WF_SCHEMA_INVALID')
  })

  it('ANY allows overlaps that give the same results', () => {
    const def = withRows('FIRST', amount('lt', 500), amount('lt', 100))
    def.decisions[0]!.hitPolicy = 'ANY'
    expect(codes(def)).not.toContain('WF_DECISION_OVERLAP')
    def.decisions[0]!.rows[1]!.then = { route: 'financeReview' }
    expect(codes(def)).toContain('WF_DECISION_OVERLAP')
  })

  it('PRIORITY picks the highest ranked match and needs a complete order', () => {
    const def = withRows('FIRST', amount('gte', 1000), amount('gte', 5000))
    const table = def.decisions[0]!
    table.hitPolicy = 'PRIORITY'
    table.rows[0]!.then = { route: 'managerReview' }
    table.rows[1]!.then = { route: 'financeReview' }
    expect(codes(def)).toContain('WF_DECISION_POLICY_OPTION')
    table.priority = { output: 'route', order: ['financeReview', 'managerReview'] }
    expect(codes(def)).toContain('WF_DECISION_PRIORITY_ORDER')
    table.priority.order.push('standard')
    expect(codes(def)).not.toContain('WF_DECISION_PRIORITY_ORDER')
    expect(evaluateTable(table, { '/input/amount': 6000 }).matchedRow).toBe(1)
    expect(evaluateTable(table, { '/input/amount': 2000 }).matchedRow).toBe(0)
  })

  it('COLLECT combines every matching row into one number', () => {
    const def = withRows('FIRST', amount('gte', 1000), amount('gte', 5000))
    const table = def.decisions[0]!
    table.hitPolicy = 'COLLECT'
    table.default = { fee: 0 }
    table.rows[0]!.then = { fee: 0.1 }
    table.rows[1]!.then = { fee: 0.2 }
    expect(codes(def)).toContain('WF_DECISION_POLICY_OPTION')
    table.aggregation = 'SUM'
    expect(codes(def)).not.toContain('WF_DECISION_COLLECT_OUTPUT')
    const sum = evaluateTable(table, { '/input/amount': 6000 })
    expect(sum.matchedRows).toEqual([0, 1])
    expect(sum.output).toEqual({ fee: 0.3 })
    expect(evaluateTable({ ...table, aggregation: 'COUNT' }, { '/input/amount': 6000 }).output).toEqual({ fee: 2 })
    expect(evaluateTable({ ...table, aggregation: 'MAX' }, { '/input/amount': 6000 }).output).toEqual({ fee: 0.2 })
    expect(evaluateTable(table, { '/input/amount': 10 }).usedDefault).toBe(true)
    table.default = { fee: 'none' }
    expect(codes(def)).toContain('WF_DECISION_COLLECT_OUTPUT')
  })
})
