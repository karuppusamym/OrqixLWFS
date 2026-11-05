/**
 * Timing of a "Keep checking until..." step (docs/build/S129, CG-W01). Twin of the compiler's PollSchedule
 * (backend/compiler/src/main/java/com/orqix/compiler/PollSchedule.java): same intervals, same check count, same summary
 * sentence, so what Studio shows before publishing is what the runtime does (without jitter).
 */

export const CHECK_LIMIT = 1000
export const MIN_EVERY = 10
export const MAX_INTERVAL = 86_400
export const MIN_DEADLINE = 60
export const MAX_DEADLINE = 90 * 86_400

/** The settings as stored in `config.poll`. */
export interface PollConfig {
  firstCheck?: 'immediate' | 'afterInterval'
  every?: string
  backoff?: { multiplier: number; maxInterval: string }
  jitterPercent?: number
  until?: unknown
  failWhen?: unknown
  giveUpAfter?: string
  maxChecks?: number
  onCheckError?: { maxConsecutive: number; then?: 'needsAttention' | 'timedOut' }
  finalCheckAtDeadline?: boolean
  showWhileWaiting?: string[]
}

export interface Schedule {
  every: number
  multiplier: number
  maxInterval: number
  deadline: number
  immediate: boolean
  finalCheck: boolean
  maxChecks: number
}

const FORMAT = /^P(?=\d|T\d)(?:(\d{1,3})D)?(?:T(?=\d)(?:(\d{1,4})H)?(?:(\d{1,5})M)?(?:(\d{1,6})S)?)?$/

/** @returns seconds of an ISO-8601 poll duration (days to seconds), or -1 when it is not one */
export function seconds(text: string | undefined): number {
  const m = text ? FORMAT.exec(text) : null
  if (!m) return -1
  return Number(m[1] ?? 0) * 86_400 + Number(m[2] ?? 0) * 3600 + Number(m[3] ?? 0) * 60 + Number(m[4] ?? 0)
}

/** @returns the shortest ISO-8601 text for a number of seconds, for example PT90S becomes PT1M30S */
export function isoOf(total: number): string {
  const d = Math.floor(total / 86_400)
  const h = Math.floor((total % 86_400) / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const time = `${h ? `${h}H` : ''}${m ? `${m}M` : ''}${s ? `${s}S` : ''}`
  return d || time ? `P${d ? `${d}D` : ''}${time ? `T${time}` : ''}` : 'PT0S'
}

export function interval(s: Schedule, i: number): number {
  if (s.multiplier <= 1) return Math.min(s.every, s.maxInterval)
  let value = s.every
  for (let k = 0; k < i && value < s.maxInterval; k++) value *= s.multiplier
  return Math.round(Math.min(value, s.maxInterval))
}

export function intervalAfter(s: Schedule, check: number): number {
  return interval(s, s.immediate ? check - 1 : check)
}

export function firstCheckOffset(s: Schedule): number {
  return s.immediate ? 0 : Math.min(interval(s, 0), s.deadline)
}

/** @returns seconds after the step start of each check, capped at `limit` entries */
export function offsets(s: Schedule, limit: number): number[] {
  const out: number[] = []
  let at = firstCheckOffset(s)
  let check = 0
  while (out.length < limit) {
    if (at >= s.deadline) {
      if (s.finalCheck && (out.length === 0 || (out[out.length - 1] ?? 0) < s.deadline)) out.push(s.deadline)
      break
    }
    out.push(at)
    check++
    at += intervalAfter(s, check)
  }
  return out
}

/** @returns the schedule of a poll config, or null while its timing is incomplete or out of bounds */
export function scheduleOf(p: PollConfig | undefined): Schedule | null {
  if (!p) return null
  const every = seconds(p.every)
  const deadline = seconds(p.giveUpAfter)
  const maxInterval = p.backoff ? seconds(p.backoff.maxInterval) : every
  if (every < MIN_EVERY || every > MAX_INTERVAL || deadline < MIN_DEADLINE || deadline > MAX_DEADLINE || deadline < every) return null
  if (maxInterval < every || maxInterval > MAX_INTERVAL) return null
  const base: Schedule = {
    every,
    multiplier: p.backoff?.multiplier ?? 1,
    maxInterval,
    deadline,
    immediate: p.firstCheck !== 'afterInterval',
    finalCheck: p.finalCheckAtDeadline !== false,
    maxChecks: Number.MAX_SAFE_INTEGER,
  }
  const worst = offsets(base, CHECK_LIMIT + 1).length
  return { ...base, maxChecks: Math.min(CHECK_LIMIT, p.maxChecks ? Math.min(worst, p.maxChecks) : worst) }
}

/** @returns the worst case from the timing alone (may exceed CHECK_LIMIT), or null while the timing is invalid */
export function worstCase(p: PollConfig | undefined): number | null {
  const s = scheduleOf(p)
  return s ? offsets({ ...s, maxChecks: Number.MAX_SAFE_INTEGER }, CHECK_LIMIT + 1).length : null
}

export function cappedFrom(s: Schedule): number {
  if (s.multiplier <= 1 || s.every >= s.maxInterval) return -1
  let at = firstCheckOffset(s)
  let check = 0
  while (at < s.deadline) {
    check++
    if (intervalAfter(s, check) >= s.maxInterval) return at
    at += intervalAfter(s, check)
  }
  return -1
}

/** Plain-language length such as "2 days", "1 hour 15 minutes" or "30 seconds". */
export function describe(total: number): string {
  const parts: string[] = []
  const add = (n: number, unit: string) => {
    if (n) parts.push(`${n} ${unit}${n === 1 ? '' : 's'}`)
  }
  add(Math.floor(total / 86_400), 'day')
  add(Math.floor((total % 86_400) / 3600), 'hour')
  add(Math.floor((total % 3600) / 60), 'minute')
  add(total % 60, 'second')
  return parts.length ? parts.join(' ') : '0 seconds'
}

function once(s: number): string {
  if (s === 60) return 'once a minute'
  if (s === 3600) return 'once an hour'
  if (s === 86_400) return 'once a day'
  return `every ${describe(s)}`
}

function rate(perHour: number): string {
  if (perHour >= 1) {
    const n = Math.round(perHour)
    return `${n} ${n === 1 ? 'call an hour' : 'calls an hour'}`
  }
  const perDay = Math.max(1, Math.round(perHour * 24))
  return `${perDay} ${perDay === 1 ? 'call a day' : 'calls a day'}`
}

/** The summary shown before publishing; twin of PollSchedule.summary. */
export function summary(s: Schedule, systemName: string, waitingRequests: number): string {
  const checks = offsets(s, Math.max(1, s.maxChecks)).length
  let text = `Checks up to ${checks} ${checks === 1 ? 'time' : 'times'} over ${describe(s.deadline)}`
  const capped = cappedFrom(s)
  if (capped > 0) text += `; from ${describe(capped)} on, ${once(s.maxInterval)}`
  else if (capped === 0 || s.multiplier <= 1 || s.every >= s.maxInterval) text += `; ${once(Math.min(s.every, s.maxInterval))}`
  text += '.'
  if (waitingRequests > 0) {
    text += ` With ${waitingRequests} ${waitingRequests === 1 ? 'request' : 'requests'} waiting this is about ${rate((waitingRequests * 3600) / s.maxInterval)} to ${systemName}.`
  }
  return text
}
