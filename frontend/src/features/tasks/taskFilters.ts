import type { TaskSummary } from '@/api/types'
import { requestReference, taskTitle } from '@/lib/runText'

export type TaskDueFilter = 'all' | 'overdue' | 'soon' | 'none'

export function parseDueFilter(value: string | null): TaskDueFilter {
  return value === 'overdue' || value === 'soon' || value === 'none' ? value : 'all'
}

/** Deliberately filters only fetched, authorized task summaries; never implies a workspace-wide search. */
export function filterLoadedTasks(tasks: TaskSummary[], query: string, queue: string, due: TaskDueFilter, now = Date.now()): TaskSummary[] {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return tasks.filter(task => {
    if (queue && task.candidateGroup.key !== queue) return false
    const text = [taskTitle(task), requestReference(task), task.label, task.requester.displayName, task.candidateGroup.name].join(' ').toLocaleLowerCase()
    if (!words.every(word => text.includes(word))) return false
    const deadline = task.dueAt ? Date.parse(task.dueAt) : NaN
    if (due === 'none') return !Number.isFinite(deadline)
    if (due === 'overdue') return task.status === 'open' && deadline < now
    if (due === 'soon') return task.status === 'open' && deadline >= now && deadline <= now + 24 * 60 * 60 * 1000
    return true
  })
}

/** Rank the loaded Home preview, without claiming to rank tasks on unfetched pages. */
export function prioritizeTasks(tasks: TaskSummary[], now = Date.now()): TaskSummary[] {
  const deadline = (task: TaskSummary) => task.dueAt && Number.isFinite(Date.parse(task.dueAt)) ? Date.parse(task.dueAt) : Infinity
  return [...new Map(tasks.map(task => [task.id, task])).values()].sort((a, b) =>
    Number(deadline(b) < now) - Number(deadline(a) < now)
    || (b.priority ?? 0) - (a.priority ?? 0)
    || deadline(a) - deadline(b)
    || Date.parse(a.createdAt) - Date.parse(b.createdAt)
    || a.id.localeCompare(b.id),
  )
}
