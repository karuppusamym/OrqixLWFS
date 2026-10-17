import { describe, expect, it } from 'vitest'
import type { StageView } from '@/api/types'
import { describeSla, stageSentence } from './stages'

const base: StageView = { id: 'finance', label: 'Finance review', index: 2, total: 4, state: 'active', overdue: false, dueAt: '2026-10-14T09:00:00Z' }

describe('stage text', () => {
  it('reads as one plain sentence', () => {
    expect(stageSentence(base)).toMatch(/^Stage 2 of 4: Finance review · due 14 Oct$/)
    expect(stageSentence({ ...base, overdue: true })).toMatch(/due 14 Oct · overdue$/)
    expect(stageSentence({ ...base, state: 'completed' })).toBe('Stage 2 of 4: Finance review · done')
  })
  it('describes a time limit in days and hours', () => {
    expect(describeSla('P2D')).toBe('2 days')
    expect(describeSla('PT36H')).toBe('1 day 12 hours')
    expect(describeSla('soon')).toBeNull()
  })
})
