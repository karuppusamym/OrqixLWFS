import { describe, expect, it } from 'vitest'
import { availableFields } from './fieldRefs'
import { addField, applyFields, fieldsFromDefinition } from './ops'
import { dateWaitPreview, nodeSubtitle } from './summary'
import { normalizeDefinition, type Definition } from './types'
import example from '../../../../../examples/start-date-onboarding.json'

const def = (): Definition => normalizeDefinition(structuredClone(example))

describe('date question in the studio model', () => {
  it('reads and writes a Date question including its limits', () => {
    const d = def()
    const field = fieldsFromDefinition(d).find((f) => f.key === 'startDate')!
    expect(field).toMatchObject({ kind: 'date', required: true, label: 'Start date' })
    const bounded = applyFields(d, fieldsFromDefinition(d).map((f) => (f.key === 'startDate' ? { ...f, minDate: '2026-01-01', maxDate: '2026-12-31' } : f)))
    expect(bounded.inputSchema.properties.startDate).toMatchObject({ type: 'string', format: 'date', formatMinimum: '2026-01-01', formatMaximum: '2026-12-31' })
    expect(bounded.inputSchema.properties.startDate).not.toHaveProperty('maxLength')
    expect(fieldsFromDefinition(bounded).find((f) => f.key === 'startDate')).toMatchObject({ minDate: '2026-01-01', maxDate: '2026-12-31' })
  })

  it('adds a Date question', () => {
    const { def: next, key } = addField(def(), 'Hire date', 'date')
    expect(next.inputSchema.properties[key]).toEqual({ type: 'string', title: 'Hire date', format: 'date' })
  })

  it('offers date questions as a field of type date, not as text', () => {
    const fields = availableFields(def(), 'untilStart')
    expect(fields.find((f) => f.ref === '/input/startDate')?.type).toBe('date')
    expect(fields.find((f) => f.ref === '/input/employeeName')?.type).toBe('string')
  })
})

describe('wait step wording', () => {
  it('summarises a wait until a date on the canvas and in the Steps tab', () => {
    const d = def()
    const wait = d.nodes.find((n) => n.id === 'untilStart')!
    expect(nodeSubtitle(d, wait, {})).toBe('Until Start date, 09:00 UTC')
    wait.config = { untilField: '/input/startDate', atTime: '17:45' }
    expect(nodeSubtitle(d, wait, {})).toBe('Until Start date, 17:45 UTC')
    wait.config = { duration: 'P2D' }
    expect(nodeSubtitle(d, wait, {})).toBe('P2D')
  })

  it('previews the end of the wait in plain language', () => {
    expect(dateWaitPreview('Start date', '09:00')).toBe('Waits until the date in "Start date", at 09:00 UTC.')
    expect(dateWaitPreview('Start date', '09:00', '2026-10-14')).toBe('Waits until 14 Oct 2026, 09:00 UTC.')
    expect(dateWaitPreview(undefined, '', '2026-02-30')).toBe('Waits until the date in "the chosen question", at 09:00 UTC.')
  })
})
