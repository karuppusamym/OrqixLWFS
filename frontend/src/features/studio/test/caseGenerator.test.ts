import { describe, expect, it } from 'vitest'
import purchase from '../../../../../examples/purchase-approval.json'
import type { SimulationResult } from '@/api/types'
import { buildFields, parseFormSchema, parseFormUi, type FieldDef, type FormFieldSchema } from '@/lib/form/schema'
import { validateForm } from '@/lib/form/values'
import { validateJsonForm } from '@/lib/form/validate'
import { evaluateAssertions } from '@/mocks/assertions'
import { simulateDefinition, type SimContext, type SimulationScenario } from '@/mocks/simulator'
import { normalizeDefinition, type AtomicCondition, type Definition } from '../model/types'
import { answerFor, assignmentsFor, candidateInputs, generateCases, inputConditions, MAX_GENERATED, tidyValues, type DryRun } from './caseGenerator'
import { toAssertions, toScenario } from './scenarioModel'

const TODAY = new Date('2026-10-29T00:00:00Z')

/** The IT equipment request of tests/e2e/specs/t-forms-02: routed by cost, with three form rules. */
function itEquipment(extra: { transitions?: Definition['transitions'] } = {}): Definition {
  return normalizeDefinition({
    schemaVersion: '0.1',
    workflowKey: 'it-equipment',
    name: 'IT equipment request',
    metadata: { description: '' },
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      required: ['title', 'item', 'estimatedCost'],
      properties: {
        title: { type: 'string', title: 'Short title', minLength: 1, maxLength: 120 },
        item: { type: 'string', title: 'What do you need?', enum: ['Laptop', 'Monitor', 'Phone', 'Headset', 'Other'] },
        otherItem: { type: 'string', title: 'Describe the item', maxLength: 200 },
        estimatedCost: { type: 'number', title: 'Estimated cost (USD)', minimum: 0.01, maximum: 1000000, multipleOf: 0.01 },
        justification: { type: 'string', title: 'Business justification', maxLength: 2000 },
        replacement: { type: 'boolean', title: 'Replacing a broken device?' },
        assetTag: { type: 'string', title: 'Asset tag of the old device', pattern: '^IT-\\d{5}$', patternMessage: 'Enter the tag on the device, for example IT-12345.' },
      },
    },
    inputUi: {
      widgets: { estimatedCost: 'money', justification: 'textarea', item: 'select' },
      currency: 'USD',
      visibleWhen: {
        otherItem: { fieldRef: '/input/item', operator: 'eq', value: 'Other' },
        justification: { fieldRef: '/input/estimatedCost', operator: 'gte', value: 1000 },
        assetTag: { fieldRef: '/input/replacement', operator: 'eq', value: true },
      },
      requiredWhen: {
        otherItem: { fieldRef: '/input/item', operator: 'eq', value: 'Other' },
        justification: { fieldRef: '/input/estimatedCost', operator: 'gte', value: 1000 },
        assetTag: { fieldRef: '/input/replacement', operator: 'eq', value: true },
      },
      layout: {
        sections: [
          { id: 'need', title: 'What you need', fields: ['title', 'item', 'otherItem'] },
          { id: 'cost', title: 'Cost', fields: ['estimatedCost', 'justification'] },
          { id: 'replacement', title: 'Replacement', fields: ['replacement', 'assetTag'] },
        ],
      },
    },
    outputSchema: { type: 'object', additionalProperties: false, properties: {} },
    triggers: ['manual'],
    nodes: [
      { id: 'start', type: 'start', label: 'Equipment requested', config: {} },
      { id: 'manager', type: 'approval', label: 'Manager review', config: { candidateGroupRef: 'managers', rejectReasonRequired: true } },
      { id: 'costCheck', type: 'branch', label: 'Finance sign-off needed?', config: {} },
      { id: 'finance', type: 'approval', label: 'Finance sign-off', config: { candidateGroupRef: 'finance-approvers', rejectReasonRequired: true } },
      { id: 'notifyApproved', type: 'notification', label: 'Tell the requester it is approved', config: { channel: 'inApp', recipient: 'initiator', message: 'Approved.' } },
      { id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } },
      { id: 'notifyRejected', type: 'notification', label: 'Tell the requester it was rejected', config: { channel: 'inApp', recipient: 'initiator', message: 'Rejected.' } },
      { id: 'rejected', type: 'end', label: 'Rejected', config: { businessOutcome: 'Rejected', outcomeKind: 'rejected' } },
    ],
    transitions: extra.transitions ?? [
      { id: 't1', from: 'start', to: 'manager', on: 'next' },
      { id: 't2', from: 'manager', to: 'costCheck', on: 'approved' },
      { id: 't3', from: 'manager', to: 'notifyRejected', on: 'rejected' },
      { id: 't4', from: 'costCheck', to: 'finance', on: 'condition', label: 'USD 2,000 or more', condition: { fieldRef: '/input/estimatedCost', operator: 'gte', value: 2000 } },
      { id: 't5', from: 'costCheck', to: 'notifyApproved', on: 'default', label: 'Under USD 2,000' },
      { id: 't6', from: 'finance', to: 'notifyApproved', on: 'approved' },
      { id: 't7', from: 'finance', to: 'notifyRejected', on: 'rejected' },
      { id: 't8', from: 'notifyApproved', to: 'approved', on: 'next' },
      { id: 't9', from: 'notifyRejected', to: 'rejected', on: 'next' },
    ],
    decisions: [],
    execution: { maxConcurrentRuns: 10, maxAttempts: 3, retryDelaySeconds: 30 },
    security: { selfApproval: 'deny' },
    dependencies: { connections: [] },
    outputMapping: {},
  })
}

const formOf = (def: Definition) => {
  const schema = parseFormSchema(def.inputSchema)
  const ui = parseFormUi(def.inputUi)
  return { schema, ui, fields: buildFields(schema, ui) }
}

/** A dry run through the mock-side port of the simulator, as mock mode answers /simulate. */
function mockDryRun(def: Definition, calls: Array<{ input: Record<string, unknown>; scenario: SimulationScenario }> = []): DryRun {
  const ctx: SimContext = {
    groups: { managers: 'Managers', 'finance-approvers': 'Finance approvers' },
    connection: () => undefined,
    childDefinition: () => undefined,
    today: '2026-10-29',
    issues: [],
    validateInput: (d, input) => validateJsonForm(parseFormSchema(d.inputSchema), parseFormUi(d.inputUi), input).map((i) => ({ pointer: `/input${i.pointer}`, code: i.keyword, message: i.keyword })),
  }
  return async (input, settings) => {
    const scenario = toScenario(def, settings) as SimulationScenario
    calls.push({ input, scenario })
    const r = simulateDefinition(def, input, scenario, ctx)
    return r.status === 'ok' ? (r.body as unknown as SimulationResult) : null
  }
}

const field = (key: string, schema: Partial<FormFieldSchema> & { type: FormFieldSchema['type'] }, isMoney = false): FieldDef => ({ key, label: key, description: undefined, required: false, schema: { title: key, ...schema }, widget: isMoney ? 'money' : 'text', isMoney })
const term = (operator: AtomicCondition['operator'], value: AtomicCondition['value']): AtomicCondition => ({ fieldRef: '/input/x', operator, value })

describe('value derivation', () => {
  const money = field('x', { type: 'number', minimum: 0.01, maximum: 1000000, multipleOf: 0.01 }, true)

  it('gives the boundary and the next cent on the other side for each operator', () => {
    const both = (op: AtomicCondition['operator']) => [answerFor(money, term(op, 2000), true), answerFor(money, term(op, 2000), false)]
    expect(both('gte')).toEqual(['2000', '1999.99'])
    expect(both('gt')).toEqual(['2000.01', '2000'])
    expect(both('lte')).toEqual(['2000', '2000.01'])
    expect(both('lt')).toEqual(['1999.99', '2000'])
    expect(both('eq')).toEqual(['2000', '2000.01'])
    expect(both('ne')).toEqual(['2000.01', '2000'])
  })

  it('steps whole numbers by one and plain numbers by their multipleOf', () => {
    const count = field('x', { type: 'integer', minimum: 0 })
    expect([answerFor(count, term('gte', 5), true), answerFor(count, term('gte', 5), false)]).toEqual(['5', '4'])
    const half = field('x', { type: 'number', multipleOf: 0.5 })
    expect(answerFor(half, term('lt', 3), true)).toBe('2.5')
    // A literal with more decimals than the step keeps them, without floating-point noise.
    expect(answerFor(field('x', { type: 'number' }), term('gt', 0.105), true)).toBe('0.115')
  })

  it('respects the minimum and maximum: an impossible side gives nothing, eq falls back to the lower neighbour', () => {
    expect(answerFor(money, term('lt', 0.01), true)).toBeUndefined()
    expect(answerFor(money, term('gte', 0.01), false)).toBeUndefined()
    expect(answerFor(money, term('eq', 1000000), false)).toBe('999999.99')
    expect(answerFor(field('x', { type: 'integer', maximum: 10 }), term('gt', 10), true)).toBeUndefined()
    // Money takes cents only, so a rule on a fraction of a cent cannot be reached.
    expect(answerFor(field('x', { type: 'number' }, true), term('eq', 10.005), true)).toBeUndefined()
  })

  it('names each choice a rule names, and another choice for the other side', () => {
    const item = field('x', { type: 'string', enum: ['Laptop', 'Monitor', 'Other'] })
    expect([answerFor(item, term('eq', 'Other'), true), answerFor(item, term('eq', 'Other'), false)]).toEqual(['Other', 'Laptop'])
    expect([answerFor(item, term('ne', 'Laptop'), true), answerFor(item, term('ne', 'Laptop'), false)]).toEqual(['Monitor', 'Laptop'])
    expect(answerFor(item, term('eq', 'Tablet'), true)).toBeUndefined()
    expect(answerFor(item, term('gte', 'Other'), true)).toBeUndefined()
  })

  it('answers Yes and No for a controlling yes/no question', () => {
    const yes = field('x', { type: 'boolean' })
    expect([answerFor(yes, term('eq', true), true), answerFor(yes, term('eq', true), false)]).toEqual([true, false])
    expect([answerFor(yes, term('ne', true), true), answerFor(yes, term('ne', true), false)]).toEqual([false, true])
    expect(answerFor(yes, term('eq', 'true'), true)).toBeUndefined()
    expect(answerFor(yes, term('eq', 1), true)).toBeUndefined()
  })

  it('uses a named answer only when it matches the pattern and length, and the sample answer for the other side', () => {
    const tag = field('x', { type: 'string', pattern: '^IT-\\d{5}$', maxLength: 8 })
    expect(answerFor(tag, term('eq', 'IT-12345'), true)).toBe('IT-12345')
    expect(answerFor(tag, term('eq', 'IT-1'), true)).toBeUndefined()
    expect(answerFor(tag, term('eq', 'IT-12345'), false, 'IT-00000')).toBe('IT-00000')
    expect(answerFor(tag, term('eq', 'IT-00000'), false, 'IT-00000')).toBeUndefined()
    expect(answerFor(field('x', { type: 'string', minLength: 3 }), term('eq', 'ab'), true)).toBeUndefined()
  })

  it('makes "all of" hold with every term and fail one term at a time; "any of" the other way round', () => {
    const fields = [field('amount', { type: 'number' }, true), field('urgent', { type: 'boolean' })]
    const all = { all: [{ fieldRef: '/input/amount', operator: 'gte' as const, value: 500 }, { fieldRef: '/input/urgent', operator: 'eq' as const, value: true }] }
    expect(assignmentsFor(all, true, fields, {})).toEqual([[{ key: 'amount', value: '500' }, { key: 'urgent', value: true }]])
    expect(assignmentsFor(all, false, fields, {})).toEqual([[{ key: 'amount', value: '499.99' }], [{ key: 'urgent', value: false }]])
    const any = { any: all.all }
    expect(assignmentsFor(any, true, fields, {})).toEqual([[{ key: 'amount', value: '500' }], [{ key: 'urgent', value: true }]])
    expect(assignmentsFor(any, false, fields, {})).toEqual([[{ key: 'amount', value: '499.99' }, { key: 'urgent', value: false }]])
  })
})

describe('candidate answers', () => {
  it('reads route conditions, rules of used decision tables and form rules, once each', () => {
    const def = itEquipment()
    const { fields } = formOf(def)
    expect(inputConditions(def, fields).map((s) => [s.kind, JSON.stringify(s.condition)])).toEqual([
      ['route', '{"fieldRef":"/input/estimatedCost","operator":"gte","value":2000}'],
      ['form', '{"fieldRef":"/input/item","operator":"eq","value":"Other"}'],
      ['form', '{"fieldRef":"/input/estimatedCost","operator":"gte","value":1000}'],
      ['form', '{"fieldRef":"/input/replacement","operator":"eq","value":true}'],
    ])
    const p = normalizeDefinition(purchase)
    expect(inputConditions(p, formOf(p).fields)).toEqual([{ kind: 'rule', condition: { fieldRef: '/input/amount', operator: 'gte', value: 5000 } }])
  })

  it('tries both sides of each condition; every set passes the form and leaves hidden questions empty', () => {
    const def = itEquipment()
    const { schema, ui, fields } = formOf(def)
    const candidates = candidateInputs(def, fields, schema, ui, TODAY)
    expect(candidates.map((c) => c.targets.map((t) => `${t.key}=${String(t.value)}`).join(' '))).toEqual(['estimatedCost=2000', 'estimatedCost=1999.99', 'item=Other', 'item=Laptop', 'estimatedCost=999.99', 'replacement=true'])
    for (const c of candidates) {
      expect(validateForm(schema, ui, c.values).valid).toBe(true)
      if (c.values.item !== 'Other') expect(c.values.otherItem).toBe('')
      if (Number(c.values.estimatedCost) < 1000) expect(c.values.justification).toBe('')
      if (c.values.replacement !== true) expect(c.values.assetTag).toBe('')
    }
    // The shown asset tag gets the shortest answer of its pattern.
    expect(candidates.find((c) => c.values.replacement === true)?.values.assetTag).toBe('IT-00000')
  })

  it('shows a hidden question before answering it: a route on the asset tag also answers Yes to replacement', () => {
    const transitions = itEquipment().transitions.map((t) => (t.id === 't4' ? { ...t, condition: { fieldRef: '/input/assetTag', operator: 'eq' as const, value: 'IT-12345' } } : t))
    const def = itEquipment({ transitions })
    const { schema, ui, fields } = formOf(def)
    const tagged = candidateInputs(def, fields, schema, ui, TODAY).find((c) => c.targets.some((t) => t.key === 'assetTag' && t.value === 'IT-12345'))
    expect(tagged?.values).toMatchObject({ replacement: true, assetTag: 'IT-12345' })
    expect(validateForm(schema, ui, tagged?.values ?? {}).payload).toMatchObject({ replacement: true, assetTag: 'IT-12345' })
  })

  it('clears answers of questions that rules hide, including through a hidden controlling question', () => {
    const fields = [
      { ...field('a', { type: 'boolean' }) },
      { ...field('b', { type: 'boolean' }), visibleWhen: [{ fieldRef: '/input/a', operator: 'eq' as const, value: true }] },
      { ...field('c', { type: 'string' }), visibleWhen: [{ fieldRef: '/input/b', operator: 'eq' as const, value: true }] },
    ]
    expect(tidyValues(fields, { a: false, b: true, c: 'kept?' })).toEqual({ a: false, b: false, c: '' })
    expect(tidyValues(fields, { a: true, b: true, c: 'kept' })).toEqual({ a: true, b: true, c: 'kept' })
  })
})

describe('generated cases', () => {
  it('covers every path of the IT equipment request, and each case passes its own checks', async () => {
    const def = itEquipment()
    const { schema, ui, fields } = formOf(def)
    const calls: Array<{ input: Record<string, unknown>; scenario: SimulationScenario }> = []
    const report = await generateCases({ def, fields, schema, ui, dryRun: mockDryRun(def, calls), today: TODAY })
    expect(report.cases.map((g) => [g.case.name, g.case.expect])).toEqual([
      ['Estimated cost (USD) is 2000', 'Approved'],
      ['Estimated cost (USD) is 1999.99', 'Approved'],
      ['What do you need? is “Other”', 'Approved'],
      ['Estimated cost (USD) is 999.99', 'Approved'],
      ['Replacing a broken device? is Yes', 'Approved'],
      ['Manager review rejects (Estimated cost (USD) is 2000)', 'Rejected'],
      ['Finance sign-off rejects (Estimated cost (USD) is 2000)', 'Rejected'],
    ])
    expect(report.cases[0]?.case.assertions.path).toEqual({ steps: ['start', 'manager', 'costCheck', 'finance', 'notifyApproved', 'approved'], match: 'exact' })
    expect(report.cases[1]?.pathLabels).toEqual(['Equipment requested', 'Manager review', 'Finance sign-off needed?', 'Tell the requester it is approved', 'Approved'])
    expect(report.cases.map((g) => g.newPath)).toEqual([true, true, false, false, false, true, true])
    expect(report.routes).toEqual({ taken: 6, total: 6, notTaken: [] })
    expect(report.tried).toBe(8)
    // Only the reject cases change the scenario, and they name the approval step.
    expect(calls.filter((c) => c.scenario.approvals).map((c) => Object.keys(c.scenario.approvals ?? {}))).toEqual([['manager'], ['finance']])
    expect(report.cases[4]?.case.values).toMatchObject({ replacement: true, assetTag: 'IT-00000' })

    const run = mockDryRun(def)
    for (const g of report.cases) {
      const body = await run(validateForm(schema, ui, g.case.values).payload, g.case.settings)
      const checks = evaluateAssertions(body as never, toAssertions(g.case.assertions, g.case.expect) as never)
      expect(checks.passed, g.case.name).toBe(true)
    }
  })

  it('reaches a decision rule of the purchase approval and lists the routes no case takes', async () => {
    const def = normalizeDefinition(purchase)
    const { schema, ui, fields } = formOf(def)
    const ctxDryRun: DryRun = async (input, settings) => {
      // The purchase order call needs a connection; this test only looks at routes, so the call's step is mocked away.
      const body = await mockDryRun(def)(input, { ...settings, calls: { procure: { result: 'works', answers: { procurementId: 'PO-1' } } } })
      return body
    }
    const report = await generateCases({ def, fields, schema, ui, dryRun: ctxDryRun, today: TODAY })
    expect(report.cases.map((g) => g.case.name)).toEqual(['Amount (USD) is 5000', 'Amount (USD) is 4999.99', 'Review request rejects (Amount (USD) is 5000)', 'Finance review rejects (Amount (USD) is 5000)'])
    expect(report.rules).toEqual({ matched: 2, total: 2 })
  })

  it('keeps at most 20 cases, new paths first, and leaves out answers the simulator refuses', async () => {
    // Twelve yes/no questions each choose a route; fifteen more each show a note. 13 paths and 15 form variants do not fit.
    const keys = Array.from({ length: 12 }, (_, i) => `q${i}`)
    const extras = Array.from({ length: 15 }, (_, i) => `f${i}`)
    const base = itEquipment()
    const def: Definition = {
      ...base,
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: [],
        properties: Object.fromEntries([...keys.map((k) => [k, { type: 'boolean' as const, title: k.toUpperCase() }]), ...extras.flatMap((k) => [[k, { type: 'boolean' as const, title: k.toUpperCase() }], [`${k}note`, { type: 'string' as const, title: `${k} note` }]])]),
      },
      inputUi: { visibleWhen: Object.fromEntries(extras.map((k) => [`${k}note`, { fieldRef: `/input/${k}`, operator: 'eq' as const, value: true }])) },
      nodes: [base.nodes[0]!, { id: 'pick', type: 'branch', label: 'Pick', config: {} }, ...keys.map((k) => ({ id: `end${k}`, type: 'end' as const, label: `End ${k}`, config: { businessOutcome: k } })), { id: 'none', type: 'end', label: 'None', config: { businessOutcome: 'none' } }],
      transitions: [{ id: 's', from: 'start', to: 'pick', on: 'next' }, ...keys.map((k, i) => ({ id: `r${i}`, from: 'pick', to: `end${k}`, on: 'condition' as const, condition: { fieldRef: `/input/${k}`, operator: 'eq' as const, value: true } })), { id: 'rd', from: 'pick', to: 'none', on: 'default' }],
    }
    const { schema, ui, fields } = formOf(def)
    const real = mockDryRun(def)
    let refused = 0
    const dryRun: DryRun = async (input, settings) => (input.q11 === true && refused++ === 0 ? null : real(input, settings))
    const report = await generateCases({ def, fields, schema, ui, dryRun, today: TODAY })
    expect(report.cases).toHaveLength(MAX_GENERATED)
    // q11's only route was refused, so 11 routes plus "none" are new paths; the other 8 show further questions.
    expect(report.cases.filter((g) => g.newPath)).toHaveLength(12)
    expect(report.cases.some((g) => g.case.expect === 'q11')).toBe(false)
    expect(new Set(report.cases.map((g) => g.case.name.toLowerCase())).size).toBe(MAX_GENERATED)
  })
})
