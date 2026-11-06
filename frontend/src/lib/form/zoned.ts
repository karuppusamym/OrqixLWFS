/**
 * Date-time questions (docs/build/S140): people type a local date and time in the question's time zone (or their
 * browser's), and the form stores an RFC 3339 value with the offset of that moment, for example
 * 2026-11-06T14:30:00+01:00. No library: Intl gives the wall time of an instant in a zone, and the offset follows.
 */

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/

function wallParts(at: number, timeZone: string | undefined): number[] {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(at))
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0)
  return [get('year'), get('month'), get('day'), get('hour'), get('minute'), get('second')]
}

/** Minutes the zone is ahead of UTC at this instant. */
export function zoneOffsetMinutes(at: number, timeZone?: string): number {
  const [y, mo, d, h, mi, s] = wallParts(at, timeZone)
  const wall = Date.UTC(y!, mo! - 1, d!, h!, mi!, s!)
  return Math.round((wall - Math.floor(at / 1000) * 1000) / 60000)
}

function offsetText(minutes: number): string {
  if (minutes === 0) return 'Z'
  const sign = minutes > 0 ? '+' : '-'
  const abs = Math.abs(minutes)
  return `${sign}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`
}

/** A datetime-local value ("2026-11-06T14:30") read in the zone, as RFC 3339 with its offset; '' when incomplete. */
export function toZoned(local: string, timeZone?: string): string {
  const m = LOCAL.exec(local)
  if (!m) return ''
  const [, y, mo, d, h, mi, s] = m
  const wall = Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s ?? 0))
  // Two passes settle the offset around a daylight-saving change.
  let offset = zoneOffsetMinutes(wall, timeZone)
  offset = zoneOffsetMinutes(wall - offset * 60000, timeZone)
  return `${y}-${mo}-${d}T${h}:${mi}:${s ?? '00'}${offsetText(offset)}`
}

/** An RFC 3339 value shown as datetime-local text in the zone ("2026-11-06T14:30"); '' when not a date-time. */
export function fromZoned(value: string, timeZone?: string): string {
  const at = Date.parse(value)
  if (!value || Number.isNaN(at)) return ''
  const [y, mo, d, h, mi] = wallParts(at, timeZone)
  const two = (n: number) => String(n).padStart(2, '0')
  return `${y}-${two(mo!)}-${two(d!)}T${two(h!)}:${two(mi!)}`
}
