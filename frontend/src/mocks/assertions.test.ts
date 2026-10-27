import { describe, expect, it } from 'vitest'
import { AssertionError, evaluateAssertions, validateAssertions, type Assertions } from './assertions'

// Shared with the Java evaluator (SimulationAssertionsTest): same assertions over the same golden traces, same results.
const files = import.meta.glob('../../../examples/fixtures/simulation/*.json', { eager: true, import: 'default' }) as Record<string, unknown>
const json = (name: string) => structuredClone(files[`../../../examples/fixtures/simulation/${name}`]) as never
const fixture = json('assertions.json') as { cases: Array<{ id: string; trace: string; assertions: Assertions; expected: unknown }> }

describe('mock assertions: shared fixture with the Java evaluator', () => {
  it.each(fixture.cases.map((c) => [c.id, c] as const))('%s', (_id, c) => {
    expect(evaluateAssertions(json(`${c.trace}.trace.json`), c.assertions)).toEqual(c.expected)
  })

  it('refuses malformed assertions like the server', () => {
    for (const bad of [[], { unknown: 1 }, { outputs: [{ field: 'a', op: 'like' }] }, { outputs: [{ field: 'a', op: 'range' }] }, { path: { steps: [] } }, { rules: [{ stepId: 'x', rule: 0 }] }, { approvals: [{ stepId: 'x', decision: 'maybe' }] }, { outcome: 5 }]) {
      expect(() => validateAssertions(bad), JSON.stringify(bad)).toThrow(AssertionError)
    }
  })
})
