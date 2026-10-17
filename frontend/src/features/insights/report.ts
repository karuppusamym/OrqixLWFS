import { cell } from '@/features/migration/reportExport'
import type { InsightsQuery, InsightsSteps, InsightsSummary, InsightsThroughput } from './api'

type Workflow = InsightsSteps['items'][number]['workflow']
const workflow = (value: Workflow) => ({ id: value.id, key: value.key, name: value.name })

/** Export only known aggregate fields. Never serialize the API responses wholesale (they include request lists). */
export function insightsReport(
  workspace: { id: string; name: string }, query: InsightsQuery,
  summary: InsightsSummary, throughput: InsightsThroughput, steps: InsightsSteps,
  now = new Date(),
) {
  for (const response of [summary, throughput, steps]) {
    if (response.range.from !== query.from || response.range.to !== query.to) {
      throw new Error('Wait for all figures to finish loading for the selected period.')
    }
  }
  const workflows = [...summary.cycleTimes, ...summary.sla.timeouts, ...steps.items]
  if (query.workflowId && workflows.some(item => item.workflow.id !== query.workflowId)) {
    throw new Error('The figures do not match the selected workflow. Refresh Insights and try again.')
  }
  return {
    format: 'orqix.insights', version: 1,
    exportedAt: now.toISOString(),
    workspace: { id: workspace.id, name: workspace.name },
    filters: { from: query.from, to: query.to, workflowId: query.workflowId ?? null, timezone: 'UTC' },
    generatedAt: { summary: summary.range.generatedAt, throughput: throughput.range.generatedAt, steps: steps.range.generatedAt },
    notes: [
      'Figures come from separate requests and are not an atomic snapshot.',
      'Summary totals describe requests started in the selected period, by their current state.',
      'Daily throughput counts starts and completions on each UTC day; it is not the summary cohort.',
      'Open step counts and overdue totals describe current work, including work started before the period.',
      'Durations are seconds; shares are fractions from 0 to 1. Null durations mean no measured value.',
      'Request titles, inputs, outputs, references and individual request lists are excluded.',
    ],
    totals: {
      started: summary.totals.started, completed: summary.totals.completed,
      fulfilled: summary.totals.fulfilled, rejected: summary.totals.rejected,
      cancelled: summary.totals.cancelled, open: summary.totals.open,
      needsAttention: summary.totals.needsAttention,
    },
    cycleTimes: summary.cycleTimes.map(item => ({
      workflow: workflow(item.workflow), completed: item.completed,
      medianSeconds: item.medianSeconds, p90Seconds: item.p90Seconds,
    })),
    dailyThroughput: throughput.days.map(day => ({
      date: day.date, started: day.started, completed: day.completed, fulfilled: day.fulfilled, rejected: day.rejected,
    })),
    bottlenecks: steps.items.map(item => ({
      workflow: workflow(item.workflow), nodeId: item.nodeId, label: item.label, kind: item.kind,
      count: item.count, openNow: item.openNow, averageWaitSeconds: item.averageWaitSeconds,
      p90WaitSeconds: item.p90WaitSeconds, timeouts: item.timeouts, timeoutShare: item.timeoutShare,
    })),
    sla: {
      overdueCount: summary.sla.overdueCount,
      timeouts: summary.sla.timeouts.map(item => ({
        workflow: workflow(item.workflow), nodeId: item.nodeId, label: item.label,
        timeouts: item.timeouts, decided: item.decided, share: item.share,
      })),
    },
    limits: {
      bottlenecks: { limit: 200, truncated: steps.truncated },
      cycleTimes: { limit: 100, atLimit: summary.cycleTimes.length >= 100 },
      timeoutSteps: { limit: 50, atLimit: summary.sla.timeouts.length >= 50 },
      note: 'At a summary list limit, additional rows may exist. Filter to one workflow to narrow the report.',
    },
  }
}

/** Reuse the formula-safe CSV cell writer even for server-provided dates and counts. */
export function throughputCsv(data: InsightsThroughput): string {
  const header = ['UTC date', 'Started', 'Completed', 'Fulfilled', 'Rejected']
  return [header, ...data.days.map(day => [day.date, day.started, day.completed, day.fulfilled, day.rejected])]
    .map(row => row.map(cell).join(',')).join('\r\n') + '\r\n'
}

export function reportFilename(query: InsightsQuery, extension: 'json' | 'csv'): string {
  // Never put designer-controlled labels or arbitrary query text into download names.
  const date = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : 'period'
  return `orqix-insights-${date(query.from)}-to-${date(query.to)}.${extension}`
}
