import type { CurrentStep, Run, TaskSummary } from '@/api/types'
import { dueInfo } from './dates'
import { humanize, shortId } from './text'

/**
 * The request reference people see and quote: the server-generated reference (ORQ-1043, `reference` on a run,
 * `runReference` on a task or notification). Older servers send none, so fall back to the person's own reference
 * (business key) and finally to a short run ID. A raw UUID is never shown.
 */
export function requestReference(source: { id?: string | null; runId?: string | null; reference?: string | null; runReference?: string | null; businessKey?: string | null }): string {
  const own = source.reference || source.runReference || source.businessKey
  if (own) return own
  const id = source.id ?? source.runId
  return id ? shortId(id) : ''
}

/** Same as requestReference, for a run. */
export function requestId(run: Pick<Run, 'id' | 'businessKey' | 'reference'>): string {
  return requestReference(run)
}

/**
 * What the request is about (the first text field of the form, such as "Annual design software licences"). Task lists carry
 * it already; run lists show it when the server sends `requestTitle`, and fall back to the workflow name when it does not.
 */
export function runTitle(run: Run): string | null {
  const title = (run as Run & { requestTitle?: unknown }).requestTitle
  return typeof title === 'string' && title.trim() !== '' ? title.trim() : null
}

/** The person's own reference, shown separately when it differs from the generated request reference. */
export function ownReference(run: Pick<Run, 'businessKey' | 'reference'>): string | null {
  return run.businessKey && run.businessKey !== run.reference ? run.businessKey : null
}

/** "Waiting for Managers: Review request" or "Mani Manager is reviewing: Review request". */
export function stepOwnerText(step: CurrentStep, lifecycle?: Run['lifecycle']): string {
  if (step.kind === 'http') return lifecycle === 'needsAttention' ? `${step.label} is delayed` : `${step.label} is in progress`
  if (step.kind === 'wait') return `Waiting until the timer finishes: ${step.label}`
  if (step.kind === 'child') return `Waiting for the child request: ${step.label}`
  if (step.assignee) return `${step.assignee.displayName} is working on: ${step.label}`
  const group = step.candidateGroup ? humanize(step.candidateGroup) : 'an approver'
  return `Waiting for ${group}: ${step.label}`
}

/** One-line description of where a run is. */
export function runProgressText(run: Pick<Run, 'lifecycle' | 'currentSteps' | 'outcome' | 'attention'>): string {
  if (run.lifecycle === 'completed') return run.outcome ? `Finished: ${run.outcome}` : 'Finished'
  if (run.lifecycle === 'cancelled') return 'Cancelled'
  const first = run.currentSteps[0]
  if (!first) return run.lifecycle === 'needsAttention' ? 'Needs attention' : 'In progress'
  return stepOwnerText(first, run.lifecycle)
}

/** Task title for lists: the request title when known, otherwise the request reference, otherwise the step. */
export function taskTitle(task: Pick<TaskSummary, 'requestTitle' | 'businessKey' | 'runReference' | 'label'>): string {
  return task.requestTitle || task.runReference || task.businessKey || task.label
}

export function taskDueText(task: Pick<TaskSummary, 'dueAt'>): { label: string; overdue: boolean } | null {
  return dueInfo(task.dueAt)
}
