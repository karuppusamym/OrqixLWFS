import { describe, expect, it } from 'vitest'
import cases from '../../../../examples/fixtures/decision-expression-cases.json'
import type { DecisionTable } from '@/features/studio/model/types'
import { DecisionEvaluationError, decisionFields, evaluateExpressionTable, expressionReferences, type PinnedTypes } from './decision'

/** CG-D04 (docs/build/S146): the browser evaluator on the cases the Java DecisionExpressionCasesTest also runs. */
type Case = {
  name: string
  table: unknown
  types?: PinnedTypes
  values: Record<string, unknown>
  expect?: { matchedRow: number | null; matchedRows: number[]; usedDefault: boolean; output: Record<string, unknown>; expressions: Array<Record<string, unknown>> }
  error?: boolean
}

describe('decision tables with expressions', () => {
  const all = cases.cases as unknown as Case[]
  it.each(all.map(c => [c.name, c] as const))('shared case: %s', (_, c) => {
    const table = c.table as DecisionTable
    if (c.error) {
      expect(() => evaluateExpressionTable(table, c.values, c.types)).toThrow(DecisionEvaluationError)
      return
    }
    const result = evaluateExpressionTable(table, c.values, c.types)
    const want = c.expect!
    expect(result.matchedRow).toBe(want.matchedRow)
    expect(result.matchedRows).toEqual(want.matchedRows)
    expect(result.usedDefault).toBe(want.usedDefault)
    expect(result.output).toEqual(want.output)
    expect(result.expressions.map(({ row, term, kind, target, value }) => ({ row, term, kind, target, value }))).toEqual(want.expressions)
  })

  it('lists every field the table reads, including the fields named inside expressions', () => {
    const table = all[0]!.table as DecisionTable
    expect(decisionFields(table)).toEqual(['/input/amount', '/input/limit'])
    expect(expressionReferences('steps.score.value >= 700 and sum(input.lines, item.qty) > 1')).toEqual(['/steps/score/value', '/input/lines'])
  })
})
