import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import { applyFields, fieldsFromDefinition } from './ops'
import { normalizeDefinition } from './types'

describe('searchable request questions (CG-O04, S142)', () => {
  it('round-trips searchable on supported kinds and drops it on files', () => {
    const def = normalizeDefinition(structuredClone(purchase))
    const fields = fieldsFromDefinition(def).map((f) => (f.key === 'costCenter' || f.key === 'amount' ? { ...f, searchable: true } : f))
    fields.push({ ...fields[0]!, key: 'receipt', label: 'Receipt', kind: 'file', searchable: true })
    const next = applyFields(def, fields)
    expect(next.inputSchema.properties.costCenter?.searchable).toBe(true)
    expect(next.inputSchema.properties.amount?.searchable).toBe(true)
    expect(next.inputSchema.properties.title?.searchable).toBeUndefined()
    expect(next.inputSchema.properties.receipt?.searchable).toBeUndefined()
    expect(fieldsFromDefinition(next).find((f) => f.key === 'costCenter')?.searchable).toBe(true)
  })
})
