import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { AI_STATUS, aiExplanation, aiResult } from './AiDecisionsPanel'

type AiDecision = components['schemas']['AiDecision']

const base = { output: null } as unknown as AiDecision

describe('AI decisions in run detail (docs/build/S134)', () => {
  it('describes the AI result by mode', () => {
    expect(aiResult({ ...base, output: { label: 'duplicate' } })).toBe('duplicate')
    expect(aiResult({ ...base, output: { recommendation: 'approve' } })).toBe('Recommends approve')
    expect(aiResult({ ...base, output: { score: 42 } })).toBe('Score 42')
    expect(aiResult({ ...base, output: { fields: { a: 1, b: 2 } } })).toBe('2 fields extracted')
    expect(aiResult(base)).toBe('No result')
  })

  it('says when a person takes over', () => {
    expect(AI_STATUS.unavailable).toContain('a person takes over')
    expect(AI_STATUS.shadow).toContain('did not change the path')
  })

  it('shows why the AI answered: rationale and reasons (S152)', () => {
    expect(aiExplanation({ ...base, output: { recommendation: 'reject', rationale: 'Over budget for this cost center.' } }))
      .toEqual({ rationale: 'Over budget for this cost center.', reasons: [] })
    expect(aiExplanation({ ...base, output: { label: 'approve', reasons: ['Within the cost center budget.', ''] } }))
      .toEqual({ rationale: null, reasons: ['Within the cost center budget.'] })
    expect(aiExplanation(base)).toEqual({ rationale: null, reasons: [] })
  })
})
