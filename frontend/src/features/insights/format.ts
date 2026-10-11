import { cell } from '@/features/migration/reportExport'
import type { InsightsStep } from './api'

/** "45 s", "12 min", "3 h 20 min", "2 d 4 h": two units at most, for durations from a few seconds to weeks. */
export function formatSeconds(total: number | null | undefined): string {
  if (total === null || total === undefined) return '—'
  const s = Math.max(0, Math.round(total))
  if (s < 60) return `${s} s`
  const minutes = Math.round(s / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return minutes % 60 === 0 ? `${hours} h` : `${hours} h ${minutes % 60} min`
  const days = Math.floor(hours / 24)
  return hours % 24 === 0 ? `${days} d` : `${days} d ${hours % 24} h`
}

export const percent = (share: number): string => `${Math.round(share * 1000) / 10}%`

/** The date range ending today (UTC), as the API expects it. */
export function rangeFor(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = now.toISOString().slice(0, 10)
  const from = new Date(Date.parse(to) - (days - 1) * 86_400_000).toISOString().slice(0, 10)
  return { from, to }
}

export const STEP_KIND_LABEL: Record<InsightsStep['kind'], string> = { approval: 'Approval', wait: 'Wait' }

/**
 * CSV of the bottleneck table. Step and workflow names are written by workflow designers, so every cell is quoted and
 * a cell that starts like a formula gets an apostrophe in front (the rule of the migration findings export).
 */
export function stepsCsv(items: InsightsStep[]): string {
  const header = ['Workflow', 'Step', 'Type', 'Finished', 'Waiting now', 'Average wait (seconds)', '90th percentile wait (seconds)', 'Timeouts', 'Timeout share (percent)']
  const rows = items.map((s) => [s.workflow.name, s.label, STEP_KIND_LABEL[s.kind], s.count, s.openNow, s.averageWaitSeconds, s.p90WaitSeconds, s.timeouts, Math.round(s.timeoutShare * 1000) / 10])
  return [header, ...rows].map((row) => row.map(cell).join(',')).join('\r\n') + '\r\n'
}
