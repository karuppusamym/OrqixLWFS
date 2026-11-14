import { describe, expect, it } from 'vitest'
import { conditionHolds, shortfalls, suiteCoverage, type CoverageMinimum, type CoverageRun } from './suiteCoverage'

const files = import.meta.glob('../../../examples/fixtures/simulation/suite-coverage.json', { eager: true, import: 'default' }) as Record<string, unknown>
const fixture = structuredClone(files['../../../examples/fixtures/simulation/suite-coverage.json']) as { cases: unknown[] }

// Shared with the Java aggregator (SuiteCoverageTest): same runs, same coverage, same shortfalls.
type FixtureCase = { name: string; definition: Record<string, unknown>; runs: CoverageRun[]; expected: unknown; minimum: CoverageMinimum; shortfalls: unknown }

describe('suite coverage: shared fixture with the Java aggregator', () => {
  it.each((fixture.cases as unknown as FixtureCase[]).map((c) => [c.name, c] as const))('%s', (_name, c) => {
    const actual = suiteCoverage(c.definition, c.runs)
    expect(actual).toEqual(c.expected)
    expect(shortfalls(actual, c.minimum)).toEqual(c.shortfalls)
  })

  it('compares form rule conditions like the server', () => {
    expect(conditionHolds({ fieldRef: '/input/amount', operator: 'gte', value: 10 }, { amount: 10 })).toBe(true)
    expect(conditionHolds({ fieldRef: '/input/amount', operator: 'gte', value: 10 }, { amount: '10' })).toBe(false)
    expect(conditionHolds({ all: [] }, {})).toBe(false)
    expect(conditionHolds({ any: [{ fieldRef: '/input/a', operator: 'eq', value: true }] }, { a: true })).toBe(true)
    expect(conditionHolds({ fieldRef: '/input/kind', operator: 'ne', value: 'Other' }, {})).toBe(false)
  })
})
