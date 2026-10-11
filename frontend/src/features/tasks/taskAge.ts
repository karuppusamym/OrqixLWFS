/** Elapsed time since creation. This does not imply an SLA or change queue priority. */
export function taskAge(createdAt: string, now = Date.now()): string {
  const elapsed = Math.max(0, now - Date.parse(createdAt))
  if (!Number.isFinite(elapsed)) return 'Age unavailable'
  const hours = Math.floor(elapsed / 3_600_000)
  return hours >= 24 ? `Open for ${Math.floor(hours / 24)}d ${hours % 24}h` : `Open for ${hours}h`
}
