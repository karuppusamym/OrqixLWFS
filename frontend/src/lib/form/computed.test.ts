import { describe, expect, it } from 'vitest'
import { applyComputed, brokenRules, computeOrder } from './computed'
import { buildFields, parseFormSchema, parseFormUi } from './schema'
import { validateJsonForm } from './validate'
import { computedPreview, validateForm } from './values'

/** CG-F04 (docs/build/S137): computed fields and cross-field rules, as the server evaluates them (CalculateCompilerTest). */
const schema = parseFormSchema({
  type: 'object', additionalProperties: false, required: ['amount'],
  properties: {
    amount: { type: 'number', title: 'Amount' },
    startDate: { type: 'string', format: 'date', title: 'Start' },
    endDate: { type: 'string', format: 'date', title: 'End' },
    nights: { type: 'integer', title: 'Nights', minimum: 0, maximum: 365 },
    total: { type: 'number', title: 'Total' },
  },
})
const ui = parseFormUi({
  computed: { nights: 'daysBetween(input.startDate, input.endDate)', total: 'input.amount * 1.2' },
  rules: [
    { id: 'endAfterStart', check: 'input.endDate > input.startDate', message: 'The end date must be after the start date.', fields: ['endDate'] },
    { id: 'withinBudget', check: 'input.total <= 10000', message: 'The total is over budget.' },
  ],
  visibleWhen: { endDate: { expression: 'input.amount > 0' } },
})

describe('computed form fields and rules', () => {
  it('fills computed fields and replaces what was typed', () => {
    const out = applyComputed(schema, ui, { amount: 100.1, startDate: '2026-03-27', endDate: '2026-03-30', nights: 99 })
    expect(out.nights).toBe(3)
    expect(out.total).toBe(120.12)
  })

  it('orders computed fields that use each other and refuses a circle', () => {
    expect(computeOrder({ b: 'input.a * 2', a: 'input.x' }, k => (k === 'b' ? ['/input/a'] : ['/input/x']))).toEqual(['a', 'b'])
    expect(computeOrder({ a: 'input.b', b: 'input.a' }, k => (k === 'a' ? ['/input/b'] : ['/input/a']))).toBeNull()
  })

  it('reports broken rules at their fields, or at the first field the check reads', () => {
    const broken = brokenRules(schema, ui, { amount: 9000, startDate: '2026-03-27', endDate: '2026-03-20', total: 10800 })
    expect(broken).toEqual([
      { id: 'endAfterStart', message: 'The end date must be after the start date.', fields: ['endDate'] },
      { id: 'withinBudget', message: 'The total is over budget.', fields: ['total'] },
    ])
    expect(brokenRules(schema, ui, { amount: 1 })).toEqual([])
  })

  it('validates the typed form with computed values and rules', () => {
    const result = validateForm(schema, ui, { amount: '100', startDate: '2026-03-27', endDate: '2026-03-20', nights: '', total: '' })
    expect(result.errors.endDate).toBe('The end date must be after the start date.')
    expect(result.payload.total).toBe(120)
    const fields = buildFields(schema, ui)
    expect(fields.find(f => f.key === 'nights')?.computed).toBe('daysBetween(input.startDate, input.endDate)')
    expect(computedPreview(fields, { amount: '10', startDate: '2026-03-27', endDate: '2026-03-30' })).toEqual({ nights: '3', total: '12' })
  })

  it('gives the mock server the same rule issues and hides fields by expression', () => {
    expect(validateJsonForm(schema, ui, { amount: 1, startDate: '2026-03-27', endDate: '2026-03-20' }))
      .toContainEqual({ pointer: '/endDate', keyword: 'rule', message: 'The end date must be after the start date.' })
    expect(validateJsonForm(schema, ui, { amount: -1, endDate: '2026-03-20' })).toContainEqual({ pointer: '/endDate', keyword: 'hidden' })
  })
})
