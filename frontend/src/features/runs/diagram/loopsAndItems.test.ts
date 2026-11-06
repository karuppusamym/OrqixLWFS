import { describe, expect, it } from 'vitest'
import type { RunPath } from './api'
import { buildStepViews, describeStep } from './pathModel'

const at = '2026-11-06T10:00:00Z'

/** A request that finance sent back once, then the limit was reached; the order call ran for each of three lines. */
const path: RunPath = {
  runId: '00000000-0000-7000-8000-000000000001',
  lifecycle: 'completed',
  diagram: {
    nodes: [
      { id: 'manager', kind: 'approval', label: 'Review request', outcomeKind: null, outcome: null, hasTimeout: false, position: null },
      { id: 'finance', kind: 'approval', label: 'Finance review', outcomeKind: null, outcome: null, hasTimeout: false, position: null },
      { id: 'procure', kind: 'http', label: 'Create purchase order', outcomeKind: null, outcome: null, hasTimeout: false, position: null },
    ],
    transitions: [
      { id: 't8', from: 'finance', to: 'manager', on: 'rejected', label: null, maxTraversals: 2 },
      { id: 't12', from: 'finance', to: 'procure', on: 'limitReached', label: null },
    ],
  },
  visited: [
    { nodeId: 'manager', occurrence: 1, enteredAt: at, leftAt: at, outcome: 'approved' },
    { nodeId: 'finance', occurrence: 1, enteredAt: at, leftAt: at, outcome: 'rejected' },
    { nodeId: 'manager', occurrence: 2, enteredAt: at, leftAt: at, outcome: 'approved' },
    { nodeId: 'finance', occurrence: 2, enteredAt: at, leftAt: at, outcome: 'rejected' },
  ],
  current: [],
  taken: ['t8', 't12'],
  loops: [{ transitionId: 't8', from: 'finance', to: 'manager', maxTraversals: 2, traversals: 1, limitReached: true }],
  forEach: [{ nodeId: 'procure', occurrence: 1, items: 3, completed: 1, succeeded: 1, parallel: false, done: true }],
} as RunPath

const ctx = { run: { tasks: [], children: [], effects: [] } as never, operator: true }

describe('run diagram: bounded loops and for each (S138)', () => {
  it('says how often a step sent the request back and that the limit was reached', () => {
    const finance = buildStepViews(path).get('finance')!
    expect(finance.visits).toHaveLength(2)
    const lines = describeStep(finance, ctx).lines
    expect(lines).toContainEqual({ label: 'Times at this step', value: '2' })
    expect(lines).toContainEqual({ label: 'Sent back', value: '1 of at most 2 times (limit reached)' })
  })

  it('says how many items of a for-each step finished and whether it stopped early', () => {
    const procure = buildStepViews(path).get('procure')!
    expect(describeStep(procure, ctx).lines).toContainEqual({ label: 'Items', value: '1 of 3 done, 1 succeeded, stopped early' })
  })
})
