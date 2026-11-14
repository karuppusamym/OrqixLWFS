import { describe, expect, it } from 'vitest'
import { api, call } from '@/api/client'
import { normalizeDefinition } from '@/features/studio/model/types'
import { db } from '@/mocks/state'
import { signInAs, WS } from '@/test/utils'

/** "Test this rule" in mock mode with formulas in the rows (docs/build/S146). */
describe('mock decision evaluation with formulas', () => {
  function draftWithFormulas() {
    signInAs('dana.designer')
    const wf = [...db.workflows.values()].find(w => w.wsId === WS && w.drafts.at(-1)?.definition.decisions.some(d => d.key === 'amount-policy'))!
    const latest = wf.drafts.at(-1)!
    const definition = normalizeDefinition(structuredClone(latest.definition))
    const table = definition.decisions.find(d => d.key === 'amount-policy')!
    table.hitPolicy = 'FIRST'
    table.rows = [
      { when: { all: [{ fieldRef: '/input/amount', expression: 'input.amount >= 5000' }, { fieldRef: '/input/costCenter', operator: 'eq', value: 'IT' }] }, then: { route: { expression: 'lower(input.costCenter) + "-finance"' } } },
      { when: { fieldRef: '/input/amount', expression: 'input.amount / 0 > 1' }, then: { route: 'never' } },
    ]
    wf.drafts.push({ ...latest, revision: latest.revision + 1, definition })
    return { wf, revision: latest.revision + 1 }
  }

  const evaluate = (workflowId: string, revision: number, values: Record<string, string | number | boolean>) =>
    call(api.POST('/workspaces/{workspaceId}/workflows/{workflowId}/decisions/{decisionKey}/evaluate', { params: { path: { workspaceId: WS, workflowId, decisionKey: 'amount-policy' } }, body: { revision, values } }))

  it('evaluates formula tests and results and lists what they gave', async () => {
    const { wf, revision } = draftWithFormulas()
    const result = await evaluate(wf.id, revision, { '/input/amount': 6000, '/input/costCenter': 'IT' })
    expect(result).toMatchObject({ matchedRow: 0, usedDefault: false, output: { route: 'it-finance' } })
    expect((result as { expressions?: unknown[] }).expressions).toEqual([
      { row: 0, term: 0, kind: 'input', target: '/input/amount', expression: 'input.amount >= 5000', value: true },
      { row: 0, term: -1, kind: 'output', target: 'route', expression: 'lower(input.costCenter) + "-finance"', value: 'it-finance' },
      { row: 1, term: 0, kind: 'input', target: '/input/amount', expression: 'input.amount / 0 > 1', value: null },
    ])
    expect(await evaluate(wf.id, revision, { '/input/amount': 10, '/input/costCenter': 'IT' })).toMatchObject({ usedDefault: true, output: { route: 'standard' } })
  })

  it('answers 422 when the evaluation stops', async () => {
    const { wf, revision } = draftWithFormulas()
    // A comparison still needs its field value.
    await expect(evaluate(wf.id, revision, { '/input/amount': 6000 })).rejects.toMatchObject({ status: 422 })
  })
})
