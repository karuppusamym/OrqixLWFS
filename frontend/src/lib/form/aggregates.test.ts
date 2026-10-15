import { describe, expect, it } from 'vitest'
import { resolveInputAggregate } from './aggregates'
import purchase from '../../../../examples/purchase-approval.json'
import { normalizeDefinition } from '@/features/studio/model/types'
import { availableFields } from '@/features/studio/model/fieldRefs'
import { renameFieldKey } from '@/features/studio/model/ops'

describe('canonical collection aggregates', () => {
  it('counts lists and treats missing lists and absent numeric cells as zero', () => {
    expect(resolveInputAggregate({}, '/input/lines/count')).toBe(0)
    expect(resolveInputAggregate({}, '/input/lines/sum/price')).toBe(0)
    expect(resolveInputAggregate({ codes: ['a','b'] }, '/input/codes/count')).toBe(2)
    expect(resolveInputAggregate({ lines: [{ price: 2 }, {}] }, '/input/lines/sum/price')).toBe(2)
  })
  it('sums decimal coefficients exactly before converting once to a JSON number', () => {
    expect(resolveInputAggregate({ lines: [{ price: 0.1 }, { price: 0.2 }] }, '/input/lines/sum/price')).toBe(0.3)
    expect(resolveInputAggregate({ lines: [{ price: 1e-7 }, { price: 2e-7 }, { price: -1e-7 }] }, '/input/lines/sum/price')).toBe(2e-7)
    expect(resolveInputAggregate({ lines: [{ price: 1e21 }, { price: -1e21 }, { price: 0.3 }] }, '/input/lines/sum/price')).toBe(0.3)
    expect(() => resolveInputAggregate({ lines: [{ price: '0.1' }] }, '/input/lines/sum/price')).toThrow()
    expect(() => resolveInputAggregate({ lines: ['bad row'] }, '/input/lines/sum/price')).toThrow()
    expect(() => resolveInputAggregate({ lines: null }, '/input/lines/count')).toThrow()
  })
  it('offers only supported counts and numeric sums and renames aggregate consumers', () => {
    const def = normalizeDefinition(purchase)
    def.inputSchema.properties.lines = { type: 'array', title: 'Lines', minItems: 1, maxItems: 3, items: { type: 'object', title: 'Row', additionalProperties: false, properties: { price: { type: 'number', title: 'Price' }, quantity: { type: 'integer', title: 'Quantity' }, name: { type: 'string', title: 'Name' } } } }
    const fields = availableFields(def, 'amountPolicy')
    expect(fields).toEqual(expect.arrayContaining([expect.objectContaining({ ref: '/input/lines/count', type: 'integer' }), expect.objectContaining({ ref: '/input/lines/sum/price', type: 'number' }), expect.objectContaining({ ref: '/input/lines/sum/quantity', type: 'integer' })]))
    expect(fields.some(field => field.ref === '/input/lines/sum/name')).toBe(false)
    def.decisions[0]!.rows[0]!.when = { fieldRef: '/input/lines/sum/price', operator: 'gte', value: 100 }
    const renamed = renameFieldKey(def, 'lines', 'items')
    expect(renamed.ok).toBe(true)
    if (renamed.ok) expect(renamed.def.decisions[0]!.rows[0]!.when.fieldRef).toBe('/input/items/sum/price')
  })
})
