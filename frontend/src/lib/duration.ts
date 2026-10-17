/** ISO-8601 durations in days, hours and minutes, as the workflow compiler accepts them (PT1M to P90D, S12). */
const FORMAT = /^P(?:(\d{1,3})D)?(?:T(?:(\d{1,4})H)?(?:(\d{1,5})M)?)?$/

/** Length in minutes, or null when the text is not a duration of the supported subset. */
export function durationMinutes(text: string): number | null {
  const m = FORMAT.exec(text.trim())
  if (!m || text.trim() === 'P' || text.trim().endsWith('T')) return null
  return Number(m[1] ?? 0) * 1440 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0)
}

/** "2 days", "1 day 12 hours", "1 minute". */
export function describeMinutes(total: number): string {
  const parts: string[] = []
  const unit = (n: number, name: string) => { if (n > 0) parts.push(`${n} ${name}${n === 1 ? '' : 's'}`) }
  unit(Math.floor(total / 1440), 'day')
  unit(Math.floor((total % 1440) / 60), 'hour')
  unit(total % 60, 'minute')
  return parts.join(' ') || '0 minutes'
}

/** Plain-language preview for an inspector field, for example "Waits 2 days." or the reason it is not valid. */
export function durationPreview(text: string, verb: 'waits' | 'allows'): { ok: boolean; text: string } {
  if (!text.trim()) return { ok: false, text: 'Enter a duration such as PT30M, PT2H or P2D.' }
  const minutes = durationMinutes(text)
  if (minutes === null) return { ok: false, text: 'Use days, hours and minutes, for example PT30M, PT2H or P2D.' }
  if (minutes < 1) return { ok: false, text: 'The shortest time is 1 minute (PT1M).' }
  if (minutes > 90 * 1440) return { ok: false, text: 'The longest time is 90 days (P90D).' }
  return { ok: true, text: `${verb === 'waits' ? 'Waits' : 'Allows'} ${describeMinutes(minutes)}.` }
}
