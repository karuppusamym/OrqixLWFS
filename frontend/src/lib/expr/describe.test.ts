import { describe as suite, expect, it } from 'vitest'
import { runCalculation, CalculationFailure } from './calculate'
import { describe } from './describe'
import { scopeOf } from './types'

/** CG-X01 (docs/build/S137): the plain-language reading and the browser Calculate runner. */
suite('plain-language reading', () => {
  const labels = { input: (k: string) => ({ amount: 'Amount (USD)', start: 'Start date' } as Record<string, string>)[k] }

  it('reads arithmetic, rounding and precedence in business words', () => {
    expect(describe('round(input.amount * 1.2, 2)', labels)).toBe('Amount (USD) times 1.2 rounded to 2 decimal places')
    expect(describe('(input.amount + 5) * 2', labels)).toBe('(Amount (USD) plus 5) times 2')
    expect(describe('round(x, 0, "halfEven")')).toBe('X rounded to 0 decimal places, halves to even')
  })

  it('reads lists, dates and choices', () => {
    expect(describe('sum(input.lines, item.qty * item.price)')).toBe('The total of its qty times its price over Lines')
    expect(describe('count(input.lines, item.qty > 1)')).toBe('The number of Lines where its qty is more than 1')
    expect(describe('addWorkingDays(input.start, 5)', labels)).toBe('5 working days after Start date')
    expect(describe('if input.amount >= 5000 then "large" else "small"', labels)).toBe('If Amount (USD) is at least 5000 then “large”, otherwise “small”')
    expect(describe('input.region in ["EU", "UK"]')).toBe('Region is one of “EU”, “UK”')
    expect(describe('steps.check-credit.score >= 700 and not(input.vip)')).toBe('Check-credit: Score is at least 700 and not Vip')
    expect(describe('today()')).toBe('Today')
  })

  it('gives nothing for text that does not parse', () => {
    expect(describe('1 +')).toBeNull()
  })
})

suite('Calculate in the browser', () => {
  const scope = scopeOf({ amount: 'number', start: 'date' })
  const input = (k: string) => ({ amount: 100.05, start: '2026-03-27' } as Record<string, unknown>)[k]

  it('runs outputs in order with declared rounding, like the server', () => {
    const out = runCalculation({ outputs: [
      { key: 'tax', expression: 'round(input.amount * 0.2, 2)', type: 'number', scale: 2 },
      { key: 'total', expression: 'input.amount + tax', type: 'number', scale: 2 },
      { key: 'third', expression: 'total / 3', type: 'number', scale: 2, rounding: 'down' },
      { key: 'whole', expression: 'total', type: 'integer' },
      { key: 'payBy', expression: 'addWorkingDays(input.start, 2)', type: 'date' },
    ] }, scope, input, () => undefined, '2026-03-01T10:00:00Z')
    expect(out).toEqual({ tax: 20.01, total: 120.06, third: 40.02, whole: 120, payBy: '2026-03-31' })
  })

  it('stops on a required output without a value and lets an optional one be empty', () => {
    expect(() => runCalculation({ outputs: [{ key: 'x', expression: 'input.amount / 0', type: 'number' }] }, scope, input, () => undefined, '2026-03-01T10:00:00Z'))
      .toThrow(CalculationFailure)
    expect(runCalculation({ outputs: [{ key: 'x', expression: 'input.amount / 0', type: 'number', optional: true }] }, scope, input, () => undefined, '2026-03-01T10:00:00Z'))
      .toEqual({ x: null })
    try {
      runCalculation({ outputs: [{ key: 'x', expression: 'nope', type: 'number' }] }, scope, input, () => undefined, '2026-03-01T10:00:00Z')
    } catch (e) {
      expect((e as CalculationFailure).code).toBe('EXPR_UNKNOWN_FIELD')
      expect((e as CalculationFailure).output).toBe('x')
    }
  })
})
