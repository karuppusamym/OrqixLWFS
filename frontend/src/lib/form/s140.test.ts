import { describe, expect, it } from 'vitest'
import { buildFields, parseFormSchema, parseFormUi, widgetFor } from './schema'
import { validateJsonInput } from './validate'
import { validateForm } from './values'
import { fromZoned, toZoned } from './zoned'
import { applyFields, fieldsFromDefinition } from '@/features/studio/model/ops'
import type { Definition } from '@/features/studio/model/types'

const issues = (field: Record<string, unknown>, value: unknown) =>
  validateJsonInput(parseFormSchema({ properties: { f: field } }), { f: value }).map((i) => `${i.keyword} ${i.pointer}`)

describe('S140 field types', () => {
  it('validates times of day like the server', () => {
    const field = { type: 'string', title: 'Start', format: 'time', formatMinimum: '08:00', formatMaximum: '17:30' }
    expect(issues(field, '09:15')).toEqual([])
    expect(issues(field, '09:15:30')).toEqual([])
    expect(issues(field, '9:15')).toEqual(['format /f'])
    expect(issues(field, '07:59')).toEqual(['formatMinimum /f'])
    expect(issues(field, '17:30:01')).toEqual(['formatMaximum /f'])
  })

  it('needs an offset on date-times and compares instants', () => {
    const field = { type: 'string', title: 'Delivery', format: 'date-time', formatMinimum: '2026-11-06T09:00:00Z', timeZone: 'Europe/Berlin' }
    expect(issues(field, '2026-11-06T10:00:00+01:00')).toEqual([])
    expect(issues(field, '2026-11-06T09:59:00+01:00')).toEqual(['formatMinimum /f'])
    expect(issues(field, '2026-11-06T10:00:00')).toEqual(['format /f'])
    expect(issues(field, '2026-02-30T10:00:00Z')).toEqual(['format /f'])
    expect(parseFormSchema({ properties: { f: field } }).properties.f?.timeZone).toBe('Europe/Berlin')
  })

  it('keeps checklist and tag values unique', () => {
    const checklist = { type: 'array', title: 'Checks', minItems: 1, maxItems: 3, uniqueItems: true, items: { type: 'string', title: 'Check', enum: ['id', 'bank'] } }
    expect(issues(checklist, ['id', 'bank'])).toEqual([])
    expect(issues(checklist, ['id', 'id'])).toEqual(['uniqueItems /f/1'])
    const schema = parseFormSchema({ properties: { checks: checklist, tags: { type: 'array', title: 'Tags', minItems: 1, maxItems: 5, uniqueItems: true, items: { type: 'string', title: 'Tag' } } } })
    const ui = parseFormUi({ widgets: { checks: 'checklist', tags: 'tags' } })
    expect(buildFields(schema, ui).map((f) => f.widget)).toEqual(['checklist', 'tags'])
    // The wrong widget for the shape falls back to the plain collection editor.
    expect(widgetFor(schema.properties.tags!, 'checklist')).toBe('collection')
    expect(validateForm(schema, ui, { checks: ['id', 'id'], tags: [] }).errors.checks).toBe('Item 2: Choose each value only once.')
  })

  it('reads lookup questions defensively', () => {
    const schema = parseFormSchema({ properties: {
      buyer: { type: 'string', title: 'Buyer', lookup: { connection: 'servicenow-users', operation: 'searchUsers', searchInput: 'text', rows: '/result/[*]', valueColumn: 'email', labelColumn: 'name', minSearchLength: 9 } },
      broken: { type: 'string', title: 'Broken', lookup: { connection: 'x' } },
    } })
    expect(schema.properties.buyer?.lookup?.valueColumn).toBe('email')
    expect(schema.properties.buyer?.lookup?.minSearchLength).toBeUndefined()
    expect(schema.properties.broken?.lookup).toBeUndefined()
    expect(buildFields(schema, {})[0]?.widget).toBe('lookup')
  })

  it('stores a typed local time with the offset of its zone, across daylight saving', () => {
    expect(toZoned('2026-11-06T14:30', 'Europe/Berlin')).toBe('2026-11-06T14:30:00+01:00')
    expect(toZoned('2026-07-06T14:30', 'Europe/Berlin')).toBe('2026-07-06T14:30:00+02:00')
    expect(toZoned('2026-07-06T14:30', 'UTC')).toBe('2026-07-06T14:30:00Z')
    expect(toZoned('2026-07-06T14:30', 'America/New_York')).toBe('2026-07-06T14:30:00-04:00')
    expect(toZoned('2026-07-06', 'UTC')).toBe('')
    expect(fromZoned('2026-11-06T13:30:00Z', 'Europe/Berlin')).toBe('2026-11-06T14:30')
    expect(fromZoned('not a time', 'UTC')).toBe('')
  })

  it('round-trips the new question kinds through the Studio field model without losing keywords', () => {
    const def = {
      inputSchema: { type: 'object', additionalProperties: false, required: [], properties: {
        start: { type: 'string', title: 'Start', format: 'time', formatMinimum: '08:00' },
        deliverAt: { type: 'string', title: 'Delivery', format: 'date-time', timeZone: 'Europe/Berlin' },
        checks: { type: 'array', title: 'Checks', minItems: 1, maxItems: 2, uniqueItems: true, items: { type: 'string', title: 'Choice', enum: ['id', 'bank'] } },
        tags: { type: 'array', title: 'Tags', minItems: 1, maxItems: 10, uniqueItems: true, items: { type: 'string', title: 'Tag', minLength: 1, maxLength: 50 } },
        buyer: { type: 'string', title: 'Buyer', maxLength: 254, lookup: { connection: 'servicenow-users', operation: 'searchUsers', searchInput: 'text', rows: '/result/[*]', valueColumn: 'email', labelColumn: 'name' } },
      } },
      inputUi: { order: ['start', 'deliverAt', 'checks', 'tags', 'buyer'], widgets: { checks: 'checklist', tags: 'tags' }, currency: 'USD' },
      nodes: [], transitions: [],
    } as unknown as Definition
    const fields = fieldsFromDefinition(def)
    expect(fields.map((f) => f.kind)).toEqual(['time', 'dateTime', 'checklist', 'tags', 'lookup'])
    const next = applyFields(def, fields)
    const p = next.inputSchema.properties
    expect(p.start).toMatchObject({ format: 'time', formatMinimum: '08:00' })
    expect(p.deliverAt).toMatchObject({ format: 'date-time', timeZone: 'Europe/Berlin' })
    expect(p.checks).toMatchObject({ type: 'array', uniqueItems: true, items: { enum: ['id', 'bank'] } })
    expect(p.tags).toMatchObject({ type: 'array', uniqueItems: true })
    expect(p.buyer?.lookup?.connection).toBe('servicenow-users')
    expect(next.inputUi?.widgets).toMatchObject({ checks: 'checklist', tags: 'tags' })
  })
})
