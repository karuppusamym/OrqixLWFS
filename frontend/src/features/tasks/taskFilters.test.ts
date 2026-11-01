import { describe, expect, it } from 'vitest'
import type { TaskSummary } from '@/api/types'
import { filterLoadedTasks, parseDueFilter, prioritizeTasks } from './taskFilters'

const now = Date.parse('2026-10-30T12:00:00Z')
const task = (id: string, overrides: Partial<TaskSummary> = {}): TaskSummary => ({
  id, runId: 'run', workflowName: 'Purchases', nodeId: 'review', label: 'Manager review',
  requestTitle: 'Replacement laptop', runReference: 'ORQ-1001',
  requester: { id: 'person', displayName: 'Rhea Requester' },
  candidateGroup: { key: 'managers', name: 'Managers' }, assignee: null,
  status: 'open', createdAt: '2026-10-29T12:00:00Z', version: 1, priority: 0, ...overrides,
})

describe('loaded task triage', () => {
  it('combines words across authorized display fields with queue filtering', () => {
    const tasks = [task('one'), task('two', { candidateGroup: { key: 'finance', name: 'Finance' } })]
    expect(filterLoadedTasks(tasks, ' RHEA  orq-1001 laptop ', 'managers', 'all', now).map(t => t.id)).toEqual(['one'])
    expect(filterLoadedTasks(tasks, 'missing', '', 'all', now)).toEqual([])
  })
  it('separates overdue, next 24 hours, missing dates and completed work at exact boundaries', () => {
    const tasks = [task('late', { dueAt: '2026-10-30T11:59:59Z' }), task('now', { dueAt: '2026-10-30T12:00:00Z' }),
      task('tomorrow', { dueAt: '2026-10-31T12:00:00Z' }), task('later', { dueAt: '2026-10-31T12:00:01Z' }),
      task('none'), task('done', { status: 'completed', dueAt: '2026-10-29T12:00:00Z' })]
    expect(filterLoadedTasks(tasks, '', '', 'overdue', now).map(t => t.id)).toEqual(['late'])
    expect(filterLoadedTasks(tasks, '', '', 'soon', now).map(t => t.id)).toEqual(['now', 'tomorrow'])
    expect(filterLoadedTasks(tasks, '', '', 'none', now).map(t => t.id)).toEqual(['none'])
    expect(parseDueFilter('invalid')).toBe('all')
  })
  it('puts overdue claimable work before non-overdue assigned work and deduplicates without mutating input', () => {
    const tasks = [task('assigned', { priority: 90, assignee: { id: 'me', displayName: 'Me' } }),
      task('claimable', { dueAt: '2026-10-29T12:00:00Z' }), task('assigned', { priority: 90 })]
    expect(prioritizeTasks(tasks, now).map(t => t.id)).toEqual(['claimable', 'assigned'])
    expect(tasks.map(t => t.id)).toEqual(['assigned', 'claimable', 'assigned'])
  })
})
