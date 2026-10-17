import { describe, expect, it } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'
import schema from '../../../../contracts/workflow-v0.schema.json'
import { guidedDefinition, type GuidedAnswers } from './guidedDefinition'

const answers = (): GuidedAnswers => ({ businessOwner: 'Demo owner', suitabilityConfirmed: true, name: 'Expense review', key: 'expense-review', purpose: 'Review routine expenses',
  fields: [{ key: 'title', title: 'Title', type: 'string', required: true }, { key: 'amount', title: 'Amount', type: 'number', required: true }],
  steps: [{ label: 'Manager review', group: 'managers', minutes: 90 }, { label: 'Finance review', group: 'finance-approvers', minutes: 480, conditionField: 'amount', threshold: 5000 }],
  calendar: { zone: 'America/New_York', revision: 'demo-v1', validFrom: '2026-01-01', weekdays: [1, 2, 3, 4, 5], start: '09:00', end: '17:00', holidays: ['2026-10-19'] },
  closure: 'REQUIRE_NO_OPEN_TASKS', subject: '{{reference}}: {{stage}}', body: '{{outcome}} {{link}}' })
describe('guided authoring contract', () => {
  it('generates a schema-valid reviewable definition with a deterministic optional approval', () => {
    const d = guidedDefinition(answers())
    const validate = new Ajv2020({ strict: false, validateFormats: false }).compile(schema)
    expect(validate(d), JSON.stringify(validate.errors)).toBe(true)
    expect(d.acceptanceProfile).toBe('tier1'); expect(d.security.selfApproval).toBe('deny')
    expect(d.nodes.find(n => n.id === 'approval1')?.config.dueAfterMinutes).toBe(90)
    expect(d.transitions.filter(t => t.from === 'gate2').map(t => t.on)).toEqual(['condition', 'default'])
    expect(d.transitions.find(t => t.from === 'gate2' && t.on === 'condition')?.condition).toEqual({ fieldRef: '/input/amount', operator: 'gte', value: 5000 })
    expect(d.nodes.filter(n => n.type === 'end').map(n => n.config.businessOutcome)).toEqual(['Approved', 'Rejected'])
  })
  it('refuses designs exceeding state and role limits before creating any draft', () => {
    const a = answers(); a.steps = Array.from({ length: 7 }, () => ({ label: 'Review', group: 'managers', minutes: 60, conditionField: 'amount', threshold: 1 }))
    expect(() => guidedDefinition(a)).toThrow('10 states')
    a.steps = Array.from({ length: 6 }, (_, i) => ({ label: 'Review', group: `group${i}`, minutes: 60 }))
    expect(() => guidedDefinition(a)).toThrow('five groups')
  })
  it('requires usable typed branch inputs and immutable calendar copies', () => {
    const a = answers(); const d = guidedDefinition(a)
    a.calendar.holidays.push('2026-12-25')
    expect((d.nodes.find(n => n.id === 'approval1')?.config.calendar as typeof a.calendar).holidays).toEqual(['2026-10-19'])
    a.fields[1]!.required = false
    expect(() => guidedDefinition(a)).toThrow('required numeric field')
    a.fields[1]!.required = true; a.fields[1]!.key = 'title'
    expect(() => guidedDefinition(a)).toThrow('unique field key')
  })
})
