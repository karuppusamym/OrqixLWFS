import { describe, expect, it } from 'vitest'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { parseFormSchema } from '@/lib/form/schema'
import { validateJsonInput } from '@/lib/form/validate'
import { ScenarioError, simulateDefinition, text, type SimContext, type SimulationScenario } from './simulator'

// The repository's examples and golden traces are the shared fixtures of the Java and the mock simulator.
const files = import.meta.glob('../../../examples/**/*.json', { eager: true, import: 'default' }) as Record<string, unknown>
const json = (path: string) => structuredClone(files[`../../../${path}`]) as Record<string, unknown>
const DIR = 'examples/fixtures/simulation/'

const fixture = json(`${DIR}context.json`) as {
  today: string
  groups: Array<{ key: string; name: string }>
  connections: Array<{ key: string; definition: string }>
  workflows: Record<string, string>
}

function context(extra: Partial<SimContext> = {}): SimContext {
  const connections = Object.fromEntries(fixture.connections.map((c) => [c.key, json(c.definition) as { name: string; kind?: string; operations: SimContext extends never ? never : Record<string, never> }]))
  return {
    groups: Object.fromEntries(fixture.groups.map((g) => [g.key, g.name])),
    connection: (key) => connections[key] as never,
    childDefinition: (key) => (fixture.workflows[key] ? normalizeDefinition(json(fixture.workflows[key] as string)) : undefined),
    today: fixture.today,
    issues: [],
    validateInput: (def, input) =>
      validateJsonInput(parseFormSchema(def.inputSchema), input).map((i) => ({ pointer: i.pointer, code: i.keyword, message: `${i.pointer.slice(1)} is not valid (${i.keyword}).` })),
    ...extra,
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = any

const definition = (path: string): Definition => normalizeDefinition(json(path))

function run(path: string, input: Record<string, unknown>, scenario: SimulationScenario = {}, extra: Partial<SimContext> = {}) {
  const r = simulateDefinition(definition(path), input, scenario, context(extra))
  if (r.status !== 'ok') throw new Error(`No trace: ${JSON.stringify(r)}`)
  // Through JSON, as on the wire: undefined members disappear, numbers are plain.
  return JSON.parse(JSON.stringify(r.body)) as Record<string, Loose>
}

const cases = (json(`${DIR}cases.json`) as { cases: Array<{ id: string; definition: string; input: Record<string, unknown>; scenario: SimulationScenario }> }).cases

describe('mock dry run: golden traces shared with the Java simulator', () => {
  it.each(cases.map((c) => [c.id, c] as const))('%s', (_id, c) => {
    const golden = json(`${DIR}${c.id}.trace.json`)
    expect(run(c.definition, c.input, c.scenario)).toEqual(golden)
  })
})

describe('mock dry run behaviour', () => {
  const purchase = 'examples/purchase-approval.json'
  const base = { title: 'Laptop', purpose: 'p', costCenter: 'IT' }

  it.each([
    [4999.99, 'standard', false],
    [5000, 'financeReview', true],
    [5000.01, 'financeReview', true],
  ])('routes %s to %s at the decision boundary', (amount, route, finance) => {
    const r = run(purchase, { ...base, amount })
    const decision = r.trace.find((e: Loose) => e.stepId === 'amountPolicy')
    expect(decision.details.outputs.route).toBe(route)
    expect(r.trace.some((e: Loose) => e.stepId === 'finance')).toBe(finance)
  })

  it('refuses a request that does not fit the form and a workflow with blockers', () => {
    const bad = simulateDefinition(definition(purchase), { ...base, amount: 0 }, {}, context())
    expect(bad.status).toBe('invalidInput')
    const blocked = simulateDefinition(definition(purchase), { ...base, amount: 5 }, {}, context({ issues: [{ code: 'WF_DEAD_END', severity: 'blocker', message: 'x', path: '/nodes/1' }] }))
    expect(blocked.status).toBe('cannotRun')
    const tolerated = run(purchase, { ...base, amount: 5 }, {}, { issues: [{ code: 'WF_GROUP_UNBOUND', severity: 'blocker', message: 'No group', path: '/nodes/1', nodeId: 'manager' }] })
    expect(tolerated.warnings[0].code).toBe('WF_GROUP_UNBOUND')
  })

  it('stops for attention with the engine reason of each failure kind', () => {
    for (const [failure, reason] of [['rejected', 'EFFECT_REJECTED'], ['unavailable', 'RETRIES_EXHAUSTED'], ['unknownOutcome', 'EFFECT_UNKNOWN']] as const) {
      const r = run(purchase, { ...base, amount: 10 }, { calls: { procure: { failure } } })
      expect(r.outcome.status).toBe('needsAttention')
      expect(r.outcome.attention.reason).toBe(reason)
    }
  })

  it('rejects a malformed scenario value and ignores unknown steps with a warning', () => {
    expect(() => run(purchase, { ...base, amount: 10 }, { approvals: { manager: { outcome: 'maybe' as never } } })).toThrow(ScenarioError)
    const r = run(purchase, { ...base, amount: 10 }, { approvals: { nope: { outcome: 'reject' } } })
    expect(r.warnings.map((w: Loose) => w.code)).toContain('SCENARIO_UNKNOWN_STEP')
  })

  it('formats numbers and money for people', () => {
    expect(text.number(5000)).toBe('5,000')
    expect(text.number(1800.5)).toBe('1,800.5')
    expect(text.money(1800.5)).toBe('USD 1,800.50')
    expect(text.money(5000)).toBe('USD 5,000')
    expect(text.fieldLabel('Amount (USD)', true)).toBe('Amount')
  })
})

describe('nested simulator resource bounds', () => {
  it('shares one 25-child budget across nested repeats', () => {
    const root = definition('examples/medium-bounded-rework.json')
    const mid = definition('examples/medium-bounded-rework.json')
    const leaf = definition('examples/simple-review.json')
    root.workflowKey = 'root-review'
    mid.workflowKey = 'mid-review'
    const rootChild = root.nodes.find((n) => n.id === 'child')!
    const midChild = mid.nodes.find((n) => n.id === 'child')!
    rootChild.config.workflowRef = 'mid-review'
    midChild.config.repeat = { maxIterations: 25, untilOutcome: 'Accepted' }
    const result = simulateDefinition(root, { title: 'T', purpose: 'p', costCenter: 'IT', amount: 10 },
      { simulateChildren: true, childScenarios: { child: { simulateChildren: true, childScenarios: { child: { approvals: { review: { outcome: 'reject' } } } } } } },
      context({ childDefinition: (key) => key === 'mid-review' ? mid : leaf }))
    expect(JSON.stringify(result)).toContain('25 child-run budget')
    expect(JSON.stringify(result)).toContain('needsAttention')
  })
  it('rejects malformed and oversized scenario groups', () => {
    const def = definition('examples/purchase-approval.json')
    expect(() => simulateDefinition(def, {}, { approvals: [] } as never, context())).toThrow(ScenarioError)
    expect(() => simulateDefinition(def, {}, { note: 'x'.repeat(65536) } as never, context())).toThrow(ScenarioError)
  })
})
