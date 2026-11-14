import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { AI_STATUS, aiResult } from './AiDecisionsPanel'

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
})
