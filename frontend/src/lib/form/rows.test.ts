import { describe, expect, it } from 'vitest'
import { buildFields, parseFormSchema, parseFormUi } from './schema'
import { orderedRequestRows } from './rows'

const schema = parseFormSchema({
  type: 'object',
  required: ['title'],
  properties: {
    title: { type: 'string', title: 'What are you buying?' },
    purpose: { type: 'string', title: 'Business purpose' },
    costCenter: { type: 'string', title: 'Cost center', enum: ['OPS', 'IT'] },
    amount: { type: 'number', title: 'Amount (USD)' },
  },
})

describe('request data in form order', () => {
  // jsonb returns keys in its own order; none of these tests may depend on the order of the input object.
  const input = { amount: 1800, costCenter: 'OPS', title: 'Laptop', purpose: 'Replacement' }

  it('follows inputUi.order when the form is known', () => {
    const fields = buildFields(schema, parseFormUi({ order: ['amount', 'title', 'costCenter', 'purpose'], widgets: { amount: 'money' } }))
    const rows = orderedRequestRows(input, fields, {})
    expect(rows.map((r) => r.key)).toEqual(['amount', 'title', 'costCenter', 'purpose'])
    expect(rows[0]).toEqual({ key: 'amount', label: 'Amount (USD)', value: '$1,800.00' })
  })

  it('falls back to schema order for fields that inputUi.order does not list', () => {
    const fields = buildFields(schema, parseFormUi({ order: ['costCenter'] }))
    expect(orderedRequestRows(input, fields, {}).map((r) => r.key)).toEqual(['costCenter', 'title', 'purpose', 'amount'])
  })

  it('uses schema order when there is no inputUi at all', () => {
    const fields = buildFields(schema, parseFormUi(undefined))
    expect(orderedRequestRows(input, fields, {}).map((r) => r.key)).toEqual(['title', 'purpose', 'costCenter', 'amount'])
  })

  it('without the form, takes the order of the labels the server sends, then any other keys', () => {
    const labels = { title: 'What are you buying?', purpose: 'Business purpose', costCenter: 'Cost center', amount: 'Amount (USD)' }
    const rows = orderedRequestRows({ ...input, extra: 'x' }, [], labels)
    expect(rows.map((r) => r.key)).toEqual(['title', 'purpose', 'costCenter', 'amount', 'extra'])
    expect(rows[3]?.value).toBe('$1,800.00') // money recognised from the label
    expect(rows[4]?.label).toBe('Extra')
  })

  it('shows a form field the requester left empty as an em dash', () => {
    const fields = buildFields(schema, parseFormUi({ order: ['title', 'purpose', 'costCenter', 'amount'] }))
    const rows = orderedRequestRows({ title: 'Laptop' }, fields, {})
    expect(rows.map((r) => r.value)).toEqual(['Laptop', '—', '—', '—'])
  })
})
