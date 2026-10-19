import { describe, expect, it } from 'vitest'
import { typedValues } from './CaseDataFields'

describe('case data numeric transport', () => {
  const fields = [{ key: 'amount', label: 'Amount', type: 'number' as const, required: false }]
  it('preserves zero, signed decimals and exponent input through JSON', () => {
    expect(typedValues(fields, { amount: '-0.1250' })).toEqual({ amount: -0.125 })
    expect(typedValues(fields, { amount: '1e-7' })).toEqual({ amount: 0.0000001 })
    expect(typedValues(fields, { amount: '0' })).toEqual({ amount: 0 })
    expect(typedValues([{ key: 'confirmed', label: 'Confirmed', type: 'boolean', required: false }], { confirmed: 'false' })).toEqual({ confirmed: false })
  })
  it('rejects silent precision loss and non-finite numbers before sending data', () => {
    expect(() => typedValues(fields, { amount: '0.10000000000000001' })).toThrow(/precision/)
    expect(() => typedValues(fields, { amount: '9007199254740993' })).toThrow(/precision/)
    expect(() => typedValues(fields, { amount: '1e999' })).toThrow(/valid/)
  })
})
