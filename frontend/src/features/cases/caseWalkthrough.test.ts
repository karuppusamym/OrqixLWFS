import { describe, expect, it } from 'vitest'
import type { CaseModelItem } from './caseModel'
import { act, actionsFor, caseCanClose, startWalk } from './caseWalkthrough'

const plan: CaseModelItem[] = [
  { id: 'collect', kind: 'humanTask', label: 'Collect evidence', required: true, manual: true, group: 'managers' },
  { id: 'review', kind: 'humanTask', label: 'Review evidence', required: true, group: 'managers', after: 'collect' },
  { id: 'done', kind: 'milestone', label: 'Evidence complete', after: 'review' },
  { id: 'escalate', kind: 'humanTask', label: 'Escalate', group: 'managers', discretionary: true },
  { id: 'cancel', kind: 'userEventListener', label: 'Customer withdrew', group: 'managers' },
  { id: 'chase', kind: 'humanTask', label: 'Chase customer', group: 'managers', exitAfter: 'collect' },
]

describe('case plan walkthrough', () => {
  it('follows manual start, entry rules, milestones and exit rules until the case can close', () => {
    let state = startWalk(plan)
    expect(state.status).toMatchObject({ collect: 'enabled', review: 'waiting', done: 'waiting', escalate: 'offered', cancel: 'available', chase: 'active' })
    expect(caseCanClose(plan, state)).toBe(false)
    state = act(plan, state, 'collect', 'start')
    state = act(plan, state, 'collect', 'complete')
    expect(state.status).toMatchObject({ collect: 'completed', review: 'active', chase: 'ended' })
    state = act(plan, state, 'review', 'complete')
    expect(state.status.done).toBe('completed')
    expect(state.log).toContain('Milestone Evidence complete reached.')
    expect(caseCanClose(plan, state)).toBe(true)
  })

  it('offers discretionary work until planned and ignores actions that do not apply', () => {
    let state = startWalk(plan)
    expect(actionsFor(plan[3]!, state)).toEqual(['plan'])
    expect(act(plan, state, 'review', 'complete')).toBe(state)
    state = act(plan, state, 'escalate', 'plan')
    expect(state.status.escalate).toBe('active')
  })

  it('waits for the designer to declare a data condition met, and runs a stage until its required work is done', () => {
    const staged: CaseModelItem[] = [
      { id: 'stage1', kind: 'stage', label: 'Assessment', children: [
        { id: 'assess', kind: 'humanTask', label: 'Assess', required: true, group: 'managers' },
        { id: 'large', kind: 'humanTask', label: 'Large claim review', group: 'managers', when: { join: 'all', clauses: [{ field: 'amount', op: '>', value: 1000 }] } },
      ] },
      { id: 'pay', kind: 'humanTask', label: 'Pay', required: true, group: 'managers', after: 'stage1' },
    ]
    let state = startWalk(staged)
    expect(state.status).toMatchObject({ stage1: 'active', assess: 'active', large: 'waiting', pay: 'waiting' })
    expect(actionsFor(staged[0]!.children![1]!, state)).toEqual(['data'])
    state = act(staged, state, 'large', 'data')
    expect(state.status.large).toBe('active')
    state = act(staged, state, 'assess', 'complete')
    expect(state.status.stage1).toBe('active')
    state = act(staged, state, 'large', 'complete')
    expect(state.status).toMatchObject({ stage1: 'completed', pay: 'active' })
  })
})
