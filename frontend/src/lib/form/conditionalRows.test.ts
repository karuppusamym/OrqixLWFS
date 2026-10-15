import { describe, expect, it } from 'vitest'
import { buildFields, parseFormSchema, parseFormUi } from './schema'
import { validateForm, valuesFromJson, carryOverValues } from './values'
import { validateJsonForm, validateJsonInput } from './validate'

const schema = parseFormSchema({ type: 'object', required: ['explanation'], properties: {
  amount: { type: 'number', title: 'Amount' },
  explanation: { type: 'string', title: 'Explanation', minLength: 1 },
  lines: { type: 'array', title: 'Lines', minItems: 1, maxItems: 2, items: { type: 'object', additionalProperties: false, required: ['name','quantity'], properties: { name: { type: 'string', title: 'Name', minLength: 1 }, quantity: { type: 'integer', title: 'Quantity', minimum: 1 } } } },
} })
const condition = { fieldRef: '/input/amount', operator: 'gte', value: 100 }
const ui = parseFormUi({ visibleWhen: { explanation: condition }, requiredWhen: { lines: condition }, layout: { sections: [{ id: 'details', title: 'Details', fields: ['explanation'], columns: 2 }] } })

describe('conditional forms and repeating object rows', () => {
  it('omits previously typed hidden values and applies requiredness only to visible fields', () => {
    expect(validateForm(schema, ui, { amount: '50', explanation: 'Previously typed', lines: [] })).toEqual({ payload: { amount: 50 }, errors: {}, valid: true })
    const high = validateForm(schema, ui, { amount: '100', explanation: '', lines: [] })
    expect(high.errors).toMatchObject({ explanation: expect.any(String), lines: expect.any(String) })
    expect(validateJsonForm(schema, ui, { amount: 50, explanation: 'Forged hidden value' })).toContainEqual({ pointer: '/explanation', keyword: 'hidden' })
  })
  it('does not coerce wrong JSON controller types into visible matches', () => {
    expect(validateJsonForm(schema, ui, { amount: '100', explanation: 'Forged' })).toEqual(expect.arrayContaining([{ pointer: '/explanation', keyword: 'hidden' }, { pointer: '/amount', keyword: 'type' }]))
  })
  it('preserves typed rows and reports exact missing cells, bounds and extra values', () => {
    const input = { amount: 100, explanation: 'Approval needed', lines: [{ name: 'Laptop', quantity: 2 }] }
    const fields = buildFields(schema, ui)
    const values = valuesFromJson(fields, input)
    expect(values.lines).toEqual([{ name: 'Laptop', quantity: '2' }])
    expect(carryOverValues(values, fields)).toEqual(values)
    expect(validateForm(schema, ui, values)).toMatchObject({ payload: input, valid: true })
    expect(validateJsonInput(schema, { explanation: 'x', lines: [{ name: 'Laptop', extra: 7 }] })).toEqual(expect.arrayContaining([{ pointer: '/lines/0/quantity', keyword: 'required' }, { pointer: '/lines/0/extra', keyword: 'additionalProperties' }]))
    expect(validateForm(schema, ui, { amount: '100', explanation: 'x', lines: [{ name: 'Laptop', quantity: '0' }] }).errors.lines).toContain('Row 1, Quantity')
  })
  it('combines field and section visibility and keeps ungrouped questions after sections', () => {
    const layout = parseFormUi({ visibleWhen: { explanation: condition }, layout: { sections: [{ id: 'details', title: 'Details', fields: ['explanation'], visibleWhen: { fieldRef: '/input/amount', operator: 'lt', value: 200 } }] } })
    expect(buildFields(schema, layout).map(field => field.key)).toEqual(['explanation','amount','lines'])
    expect(validateForm(schema, layout, { amount: '250', explanation: 'Old value' }).payload).toEqual({ amount: 250 })
  })
})
