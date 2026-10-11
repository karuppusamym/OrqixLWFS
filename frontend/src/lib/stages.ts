import type { StageView } from '@/api/types'
import { formatDay } from './dates'
import { describeMinutes, durationMinutes } from './duration'

/** "Stage 2 of 4: Finance review · due 14 Oct · overdue" (the stage of a request, docs/build/S17 section 4). */
export function stageSentence(stage: StageView): string {
  const parts = [`Stage ${stage.index} of ${stage.total}: ${stage.label}`]
  if (stage.state === 'completed') parts.push('done')
  else if (stage.dueAt) parts.push(`due ${formatDay(stage.dueAt)}`)
  if (stage.overdue) parts.push('overdue')
  return parts.join(' · ')
}

/** "2 days" for an ISO time limit, or null when it is not valid. */
export function describeSla(sla: string | undefined | null): string | null {
  if (!sla) return null
  const minutes = durationMinutes(sla)
  return minutes === null || minutes < 1 ? null : describeMinutes(minutes)
}
