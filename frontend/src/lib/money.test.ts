import { describe, expect, it } from 'vitest'
import scenarios from '../../../examples/fixtures/purchase-scenarios.json'
import { MONEY_PATTERN, formatNumber, formatUsd, parseMoney } from './money'

describe('money parsing (typed string -> JSON number only at submit)', () => {
  it('accepts up to 9 integer digits and 2 decimals', () => {
    for (const ok of ['0', '1', '1800', '1800.7', '1800.07', '999999999', '999999999.99', '0.01']) {
      expect(MONEY_PATTERN.test(ok), ok).toBe(true)
      expect(parseMoney(ok)).toEqual({ ok: true, value: Number(ok) })
    }
  })

  it('rejects everything else with a reason', () => {
    for (const bad of ['1800.071', '1,800', '$100', '1e3', '-5', '.5', '1.', '1000000000', '12 34', 'abc']) {
      expect(parseMoney(bad), bad).toEqual({ ok: false, reason: 'format' })
    }
    expect(parseMoney('')).toEqual({ ok: false, reason: 'empty' })
    expect(parseMoney('   ')).toEqual({ ok: false, reason: 'empty' })
  })

  it('keeps exactly what was typed for cents (no arithmetic, no rounding)', () => {
    expect(parseMoney('1800.07')).toEqual({ ok: true, value: 1800.07 })
    expect(JSON.stringify({ amount: (parseMoney('0.10') as { value: number }).value })).toBe('{"amount":0.1}')
  })
})

describe('money formatting', () => {
  it('formats USD with Intl (en-US)', () => {
    expect(formatUsd(1800)).toBe('$1,800.00')
    expect(formatUsd(1800.07)).toBe('$1,800.07')
    expect(formatUsd('5000')).toBe('$5,000.00')
    expect(formatUsd(0.01)).toBe('$0.01')
    expect(formatUsd(1000000)).toBe('$1,000,000.00')
  })

  it('does not invent a value for missing or non-numeric input', () => {
    expect(formatUsd(null)).toBe('')
    expect(formatUsd(undefined)).toBe('')
    expect(formatUsd('')).toBe('')
    expect(formatUsd('n/a')).toBe('n/a')
  })

  it('formats every decision boundary of the shared fixture without drifting', () => {
    for (const b of scenarios.decisionBoundaries) expect(formatUsd(b.amount)).toMatch(/^\$\d{1,3}(,\d{3})*\.\d{2}$/)
    expect(formatUsd(4999.99)).toBe('$4,999.99')
    expect(formatUsd(5000.01)).toBe('$5,000.01')
  })

  it('formats plain numbers with grouping', () => {
    expect(formatNumber(1234567.5)).toBe('1,234,567.5')
    expect(formatNumber('x')).toBe('x')
  })
})
