import { formatCalendarDate, isCalendarDate } from '@/lib/dates'
import { DEFAULT_WAIT_TIME, WAIT_HORIZON_DAYS } from '@/features/studio/model/summary'
import type { Definition } from '@/features/studio/model/types'

/**
 * "Wait until a date" for mock mode (S12 section 10): the due moment is the date at the step's time of day in UTC,
 * and a date more than 366 days after today (UTC) is refused when the request starts, as the server does.
 */

/** The moment a date wait ends: `date` (YYYY-MM-DD) at `time` (HH:mm) UTC. */
export function dateWaitDue(date: string, time: string | undefined): Date {
  return new Date(`${date}T${time || DEFAULT_WAIT_TIME}:00Z`)
}

/** The latest date a request may wait for, as YYYY-MM-DD. */
export function latestWaitDate(now: Date): string {
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + WAIT_HORIZON_DAYS))
  return d.toISOString().slice(0, 10)
}

export interface MockFieldError {
  pointer: string
  code: string
  message: string
}

/** Start-time check: a date taken from the request form that a wait step would refuse as too far away. */
export function waitHorizonErrors(def: Definition, input: Record<string, unknown>, now: Date): MockFieldError[] {
  const latest = latestWaitDate(now)
  const errors: MockFieldError[] = []
  for (const node of def.nodes) {
    const ref = node.type === 'wait' ? (node.config.untilField as string | undefined) : undefined
    const key = ref && /^\/input\/(.+)$/.exec(ref)?.[1]
    const value = key ? input[key] : undefined
    if (key && isCalendarDate(value) && value > latest && !errors.some((e) => e.pointer === `/input/${key}`)) {
      errors.push({ pointer: `/input/${key}`, code: 'formatMaximum', message: `Choose a date no later than ${formatCalendarDate(latest)}. Requests can wait for at most ${WAIT_HORIZON_DAYS} days.` })
    }
  }
  return errors
}
