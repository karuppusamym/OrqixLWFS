import { describe, expect, it } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'
import schema from '../../../../contracts/workflow-v0.schema.json'
import { fieldKeys, quickGuideDefinition, stepProblem, type QuickAnswers } from './quickGuide'

const validate = new Ajv2020({ strict: false, validateFormats: false }).compile(schema)

const answers = (): QuickAnswers => ({
  name: 'Laptop request',
  purpose: 'Staff ask for a laptop.',
  questions: [
    { label: 'Request title', type: 'text', required: true },
    { label: 'Amount in USD', type: 'number', required: true },
    { label: 'Needed by', type: 'date', required: false },
    { label: 'Urgent?', type: 'yesNo', required: false },
  ],
  approvals: [
    { label: 'Manager approval', group: 'managers' },
    { label: 'Finance review', group: 'finance-approvers', whenQuestion: 1, atLeast: 5000 },
  ],
})

describe('quick guide (deterministic creation)', () => {
  it('turns the answers into a contract-valid draft with ordered approvals and an optional step', () => {
    const d = quickGuideDefinition(answers(), 'laptop-request')
    expect(validate(d), JSON.stringify(validate.errors)).toBe(true)
    expect(d.acceptanceProfile).toBeUndefined()
    expect(Object.keys(d.inputSchema.properties)).toEqual(['requestTitle', 'amountInUsd', 'neededBy', 'urgent'])
    expect(d.inputSchema.required).toEqual(['requestTitle', 'amountInUsd'])
    expect(d.inputSchema.properties.neededBy).toMatchObject({ type: 'string', format: 'date' })
    expect(d.nodes.map((n) => n.id)).toEqual(['start', 'approval1', 'check2', 'approval2', 'approved', 'rejected'])
    expect(d.transitions.find((t) => t.from === 'approval1' && t.on === 'approved')?.to).toBe('check2')
    expect(d.transitions.find((t) => t.from === 'check2' && t.on === 'condition')).toMatchObject({
      to: 'approval2', condition: { fieldRef: '/input/amountInUsd', operator: 'gte', value: 5000 },
    })
    expect(d.transitions.find((t) => t.from === 'check2' && t.on === 'default')?.to).toBe('approved')
    expect(d.transitions.filter((t) => t.on === 'rejected').map((t) => t.to)).toEqual(['rejected', 'rejected'])
  })

  it('a request with no approvals is simply recorded and finishes', () => {
    const d = quickGuideDefinition({ ...answers(), approvals: [] }, 'laptop-request')
    expect(validate(d), JSON.stringify(validate.errors)).toBe(true)
    expect(d.nodes.map((n) => n.id)).toEqual(['start', 'finished'])
    expect(d.transitions).toEqual([{ id: 't1', from: 'start', to: 'finished', on: 'next' }])
  })

  it('derives unique, schema-valid field keys from any label', () => {
    const keys = fieldKeys([
      { label: 'Cost center', type: 'text', required: true },
      { label: 'cost-center', type: 'text', required: true },
      { label: '2nd approver', type: 'text', required: true },
      { label: 'Größe', type: 'text', required: true },
      { label: '費用', type: 'number', required: true },
    ])
    expect(keys).toEqual(['costCenter', 'costCenter2', 'field2ndApprover', 'grosse', 'field5'])
    for (const key of keys) expect(key).toMatch(/^[a-z][A-Za-z0-9]{0,63}$/)
  })

  it('explains what is missing on each step before anything is created', () => {
    const a = answers()
    expect(stepProblem(1, { ...a, name: ' ' })).toBe('Give the workflow a name.')
    expect(stepProblem(2, { ...a, questions: [] })).toMatch(/at least one question/)
    expect(stepProblem(2, { ...a, questions: [a.questions[0]!, { ...a.questions[0]!, label: 'request title ' }] })).toMatch(/same label/)
    expect(stepProblem(3, { ...a, approvals: [{ label: 'Review', group: '' }] })).toBe('Choose who approves each step.')
    const optionalAmount = { ...a, questions: a.questions.map((q, i) => (i === 1 ? { ...q, required: false } : q)) }
    expect(stepProblem(3, optionalAmount)).toMatch(/required number question/)
    expect(() => quickGuideDefinition(optionalAmount, 'x-y')).toThrow(/required number question/)
    expect(stepProblem(3, a)).toBeNull()
  })
})
