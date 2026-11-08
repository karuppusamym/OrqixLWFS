/**
 * Calendar dates and working days for the Orqix expression engine (docs/build/S137): the browser twin of
 * java.time.LocalDate and the day-level methods of the S27 BusinessCalendar. Dates are proleptic Gregorian days, years
 * 1 to 9999, computed from day numbers (never the browser's Date, so no time zone can shift them).
 */

export class ExprDate {
  readonly y: number
  readonly m: number
  readonly d: number
  /** Days since 1970-01-01. */
  readonly epoch: number
  constructor(y: number, m: number, d: number) {
    this.y = y
    this.m = m
    this.d = d
    this.epoch = daysFromCivil(y, m, d)
  }

  toString(): string {
    return `${String(this.y).padStart(4, '0')}-${String(this.m).padStart(2, '0')}-${String(this.d).padStart(2, '0')}`
  }

  /** ISO weekday: Monday 1 to Sunday 7. */
  get weekday(): number {
    return (((this.epoch + 3) % 7) + 7) % 7 + 1
  }
}

function daysFromCivil(y: number, m: number, d: number): number {
  const yy = m <= 2 ? y - 1 : y
  const era = Math.floor(yy / 400)
  const yoe = yy - era * 400
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy
  return era * 146097 + doe - 719468
}

function civilFromDays(z: number): [number, number, number] {
  const zz = z + 719468
  const era = Math.floor(zz / 146097)
  const doe = zz - era * 146097
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365)
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100))
  const mp = Math.floor((5 * doy + 2) / 153)
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1
  const m = mp + (mp < 10 ? 3 : -9)
  return [yoe + era * 400 + (m <= 2 ? 1 : 0), m, d]
}

const leap = (y: number) => (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0
export const monthLength = (y: number, m: number) => [31, leap(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]!

/** @returns the date, or null when it does not exist or is outside years 1 to 9999 */
export function makeDate(y: number, m: number, d: number): ExprDate | null {
  if (!Number.isInteger(y) || !Number.isInteger(m) || !Number.isInteger(d)) return null
  if (y < 1 || y > 9999 || m < 1 || m > 12 || d < 1 || d > monthLength(y, m)) return null
  return new ExprDate(y, m, d)
}

/** @returns the date written YYYY-MM-DD, or null */
export function parseDate(text: string): ExprDate | null {
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(text)) return null
  return makeDate(Number(text.slice(0, 4)), Number(text.slice(5, 7)), Number(text.slice(8, 10)))
}

export function fromEpoch(epoch: number): ExprDate | null {
  const [y, m, d] = civilFromDays(epoch)
  return makeDate(y, m, d)
}

export const plusDays = (date: ExprDate, n: number): ExprDate | null => fromEpoch(date.epoch + n)

/** LocalDate.plusMonths: the day is clamped to the end of the month. */
export function plusMonths(date: ExprDate, n: number): ExprDate | null {
  const total = date.y * 12 + (date.m - 1) + n
  const y = Math.floor(total / 12)
  const m = total - y * 12 + 1
  if (y < 1 || y > 9999) return null
  return makeDate(y, m, Math.min(date.d, monthLength(y, m)))
}

export const plusYears = (date: ExprDate, n: number): ExprDate | null => plusMonths(date, n * 12)

/** The S27 working calendar as far as day arithmetic needs it. */
export interface WorkingCalendar {
  zone: string
  weekdays: number[]
  holidays?: string[]
}

export const DEFAULT_CALENDAR: WorkingCalendar = { zone: 'UTC', weekdays: [1, 2, 3, 4, 5], holidays: [] }

/** Days of working-day arithmetic searched at most (BusinessCalendar.WORKING_DAY_HORIZON). */
export const WORKING_DAY_HORIZON = 7305

export function isWorkingDay(calendar: WorkingCalendar, day: ExprDate): boolean {
  return calendar.weekdays.includes(day.weekday) && !(calendar.holidays ?? []).includes(day.toString())
}

export function plusWorkingDays(calendar: WorkingCalendar, day: ExprDate, n: number): ExprDate | null {
  const step = n < 0 ? -1 : 1
  let remaining = Math.abs(n)
  let cursor = day.epoch
  for (let i = 0; remaining > 0; i++) {
    if (i >= WORKING_DAY_HORIZON) return null
    cursor += step
    const d = fromEpoch(cursor)
    if (d === null) return null
    if (isWorkingDay(calendar, d)) remaining--
  }
  return fromEpoch(cursor)
}

export function workingDaysBetween(calendar: WorkingCalendar, from: ExprDate, to: ExprDate): number | null {
  const span = to.epoch - from.epoch
  if (Math.abs(span) > WORKING_DAY_HORIZON) return null
  const lo = span < 0 ? to.epoch : from.epoch
  let count = 0
  for (let i = 1; i <= Math.abs(span); i++) {
    const d = fromEpoch(lo + i)
    if (d !== null && isWorkingDay(calendar, d)) count++
  }
  return span < 0 ? -count : count
}

/** The calendar date of an instant in a time zone. */
export function dateIn(instant: Date, zone: string): ExprDate | null {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(instant)
  const get = (type: string) => Number(parts.find(p => p.type === type)?.value)
  return makeDate(get('year'), get('month'), get('day'))
}
