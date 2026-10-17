/** Date helpers. Timestamps from the API are RFC 3339 UTC strings. */

const dateOnly = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' })
const dateYear = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
const timeOnly = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' })
const dateTime = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

const utcDateTime = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
const utcDate = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', day: 'numeric', month: 'short', year: 'numeric' })

function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  const d = value instanceof Date ? value : new Date(value)
  return Number.isNaN(d.getTime()) ? null : d
}

/** "14 Oct" */
export function formatDay(value: string | Date | null | undefined): string {
  const d = toDate(value)
  return d ? dateOnly.format(d) : ''
}

/** "14 Oct 2026" */
export function formatDayYear(value: string | Date | null | undefined): string {
  const d = toDate(value)
  return d ? dateYear.format(d) : ''
}

/** "14:35" */
export function formatTime(value: string | Date | null | undefined): string {
  const d = toDate(value)
  return d ? timeOnly.format(d) : ''
}

/** "14 Oct 2026, 14:35" */
export function formatDateTime(value: string | Date | null | undefined): string {
  const d = toDate(value)
  return d ? dateTime.format(d) : ''
}

/** "14 Oct 2026, 09:00 UTC": a moment shown in UTC, as "wait until a date" steps end at a UTC time (S12 section 10). */
export function formatUtcDateTime(value: string | Date | null | undefined): string {
  const d = toDate(value)
  return d ? `${utcDateTime.format(d).replace(' at ', ', ')} UTC` : ''
}

const CALENDAR_DATE = /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/

/** True for a real calendar date written YYYY-MM-DD (no 30 February). */
export function isCalendarDate(text: unknown): text is string {
  if (typeof text !== 'string' || !CALENDAR_DATE.test(text)) return false
  const d = new Date(`${text}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === text
}

/** "14 Oct 2026" for a calendar date YYYY-MM-DD (no time zone involved); anything else is returned as typed. */
export function formatCalendarDate(text: string | null | undefined): string {
  if (!isCalendarDate(text)) return text ?? ''
  return utcDate.format(new Date(`${text}T00:00:00Z`))
}

/** Short relative text: "just now", "5 minutes ago", "3 hours ago", "2 days ago", otherwise the date. */
export function formatRelative(value: string | Date | null | undefined, now: Date = new Date()): string {
  const d = toDate(value)
  if (!d) return ''
  const seconds = Math.round((now.getTime() - d.getTime()) / 1000)
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  if (days < 7) return `${days} day${days === 1 ? '' : 's'} ago`
  return formatDayYear(d)
}

export interface DueInfo {
  /** "Due 14 Oct" or "Overdue since 14 Oct" - always an explicit date, never an implied reminder. */
  label: string
  overdue: boolean
}

/** Due dates are labels only in R0: no reminders or escalations are sent. */
export function dueInfo(dueAt: string | null | undefined, now: Date = new Date()): DueInfo | null {
  const d = toDate(dueAt)
  if (!d) return null
  const overdue = d.getTime() < now.getTime()
  return { label: `${overdue ? 'Overdue since' : 'Due'} ${formatDay(d)}`, overdue }
}
