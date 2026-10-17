import { describe, expect, it } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'
import { buildFields, parseFormSchema } from './schema'
import { validateForm, valuesFromJson } from './values'
import { toJsonSchema, validateJsonInput } from './validate'

const collection = (type = 'integer') => parseFormSchema({ required: ['items'], properties: { items: { type: 'array', title: 'Items', minItems: 1, maxItems: 3, items: { type, title: 'Item', ...(type === 'integer' ? { minimum: 1 } : {}) } } } })

describe('bounded scalar collections', () => {
  it('parses only bounded scalar arrays and rejects nested arrays', () => {
    expect(collection().properties.items?.items?.type).toBe('integer')
    expect(parseFormSchema({ properties: { values: { type: 'array', title: 'Values', minItems: 0, maxItems: 26, items: { type: 'string' } } } }).properties).toEqual({})
    expect(parseFormSchema({ properties: { values: { type: 'array', title: 'Values', minItems: 1, maxItems: 2, items: { type: 'array' } } } }).properties).toEqual({})
  })
  it('converts item text to typed values and identifies the failing item', () => {
    const schema = collection()
    expect(validateForm(schema, {}, { items: ['2', '3'] })).toMatchObject({ valid: true, payload: { items: [2, 3] } })
    expect(validateForm(schema, {}, { items: ['2', 'oops'] }).errors.items).toContain('Item 2')
    expect(validateForm(schema, {}, { items: ['0'] }).errors.items).toContain('Item 1')
    expect(validateForm(schema, {}, { items: [] }).errors.items).toContain('at least 1')
    expect(validateForm(schema, {}, { items: ['1','2','3','4'] }).errors.items).toContain('at most 3')
    expect(valuesFromJson(buildFields(schema, {}), { items: [1,2] })).toEqual({ items: ['1','2'] })
  })
  it('matches JSON-schema validation across cardinality and item errors', () => {
    const schema = collection(); const validate = new Ajv2020({ strict: false, allErrors: true }).compile(toJsonSchema(schema))
    for (const input of [{ items: [] }, { items: [1] }, { items: [0] }, { items: ['1'] }, { items: [1,2,3,4] }, { items: true }]) {
      expect(validateJsonInput(schema, input).length === 0).toBe(validate(input))
    }
  })
  it('keeps boolean items typed rather than coercing them to text', () => {
    expect(validateForm(collection('boolean'), {}, { items: [true, false] })).toMatchObject({ valid: true, payload: { items: [true, false] } })
  })
})
