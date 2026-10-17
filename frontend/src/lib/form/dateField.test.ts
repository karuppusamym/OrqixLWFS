import { describe, expect, it } from 'vitest'
import { formatCalendarDate, formatUtcDateTime, isCalendarDate } from '../dates'
import { displayJsonValue } from './display'
import { buildFields, parseFormSchema, widgetFor } from './schema'
import { validateForm } from './values'
import { validateJsonInput } from './validate'

const schema = parseFormSchema({
  required: ['startDate'],
  properties: {
    startDate: { type: 'string', format: 'date', title: 'Start date', formatMinimum: '2026-10-01', formatMaximum: '2026-12-31' },
    other: { type: 'string', format: 'date', title: 'Other day' },
  },
})
const fields = buildFields(schema, {})

describe('calendar dates', () => {
  it('knows real dates', () => {
    for (const ok of ['2026-10-14', '2028-02-29', '1999-12-31']) expect(isCalendarDate(ok)).toBe(true)
    for (const bad of ['2026-02-30', '2027-02-29', '2026-13-01', '2026-1-5', '14/10/2026', '2026-10-14T09:00:00Z', '', 20261014, null]) expect(isCalendarDate(bad)).toBe(false)
  })

  it('writes dates without a time zone and moments in UTC', () => {
    expect(formatCalendarDate('2026-10-14')).toBe('14 Oct 2026')
    expect(formatCalendarDate('not a date')).toBe('not a date')
    expect(formatUtcDateTime('2026-10-14T09:00:00Z')).toBe('14 Oct 2026, 09:00 UTC')
    expect(formatUtcDateTime('2026-10-14T23:30:00+02:00')).toBe('14 Oct 2026, 21:30 UTC')
    expect(formatUtcDateTime(null)).toBe('')
  })
})

describe('date field in the form schema', () => {
  it('is a string with format date and gets the date widget', () => {
    expect(schema.properties.startDate).toEqual({ type: 'string', format: 'date', title: 'Start date', formatMinimum: '2026-10-01', formatMaximum: '2026-12-31' })
    expect(fields.map((f) => [f.key, f.widget, f.required])).toEqual([['startDate', 'date', true], ['other', 'date', false]])
    expect(widgetFor({ type: 'string', title: 'x' }, undefined)).toBe('text')
  })

  it('ignores bounds that are not real dates and format on other types', () => {
    const parsed = parseFormSchema({ properties: { a: { type: 'string', format: 'date', title: 'A', formatMinimum: '2026-02-30' }, b: { type: 'number', format: 'date', title: 'B' } } })
    expect(parsed.properties.a).toEqual({ type: 'string', format: 'date', title: 'A' })
    expect(parsed.properties.b).toEqual({ type: 'number', title: 'B' })
  })

  it('validates type, format and bounds like the server', () => {
    const issues = (value: unknown) => validateJsonInput(schema, { startDate: value }).map((i) => `${i.pointer} ${i.keyword}`)
    expect(issues('2026-10-14')).toEqual([])
    expect(issues('2026-10-01')).toEqual([])
    expect(issues('2026-12-31')).toEqual([])
    expect(issues('2026-09-30')).toEqual(['/startDate formatMinimum'])
    expect(issues('2027-01-01')).toEqual(['/startDate formatMaximum'])
    expect(issues('2026-02-30')).toEqual(['/startDate format'])
    expect(issues('tomorrow')).toEqual(['/startDate format'])
    expect(issues(20261014)).toEqual(['/startDate type'])
  })

  it('turns form values into the payload and business-language messages', () => {
    expect(validateForm(schema, {}, { startDate: '2026-10-14', other: '' })).toMatchObject({ valid: true, payload: { startDate: '2026-10-14' } })
    expect(validateForm(schema, {}, { startDate: '', other: '' }).errors).toEqual({ startDate: 'This field is required.' })
    expect(validateForm(schema, {}, { startDate: '2026-09-01', other: '' }).errors).toEqual({ startDate: 'Choose a date on or after 1 Oct 2026.' })
    expect(validateForm(schema, {}, { startDate: '2027-03-01', other: '' }).errors).toEqual({ startDate: 'Choose a date on or before 31 Dec 2026.' })
    expect(validateForm(schema, {}, { startDate: '2026-02-30', other: '' }).errors).toEqual({ startDate: 'Enter a valid date, for example 14 Oct 2026.' })
  })

  it('shows a date as a date', () => {
    expect(displayJsonValue(fields[0], '2026-10-14')).toBe('14 Oct 2026')
    expect(displayJsonValue(undefined, '2026-10-14')).toBe('2026-10-14')
  })
})
