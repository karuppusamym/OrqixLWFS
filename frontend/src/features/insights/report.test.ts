import { describe, expect, it } from 'vitest'
import type { InsightsSteps, InsightsSummary, InsightsThroughput } from './api'
import { insightsReport, reportFilename, throughputCsv } from './report'

const query = { from: '2026-10-01', to: '2026-10-16', workflowId: 'workflow' }
const range = { from: query.from, to: query.to, days: 16, generatedAt: '2026-10-16T12:00:00Z' }
const workflow = { id: 'workflow', key: 'review', name: '=untrusted designer label' }
function fixtures() {
  const summary: InsightsSummary = {
    range, totals: { started: 8, completed: 5, fulfilled: 4, rejected: 1, cancelled: 1, open: 2, needsAttention: 1 },
    cycleTimes: [{ workflow, completed: 5, medianSeconds: 123, p90Seconds: 456 }],
    oldestOpen: [{ runId: 'private-run-id', reference: 'PRIVATE-REFERENCE', workflow, lifecycle: 'waiting', startedAt: range.generatedAt, ageSeconds: 10, stepLabel: 'Review' }],
    needsAttention: [], sla: { overdueCount: 32, overdue: [], timeouts: [{ workflow, nodeId: 'review', label: 'Review', timeouts: 1, decided: 6, share: 1 / 6 }] },
  }
  const throughput: InsightsThroughput = { range: { ...range, generatedAt: '2026-10-16T12:00:01Z' }, days: [{ date: query.from, started: 8, completed: 6, fulfilled: 5, rejected: 1 }] }
  const steps: InsightsSteps = { range, truncated: true, items: [{ workflow, nodeId: 'review', label: 'Review', kind: 'approval', count: 6, openNow: 2, averageWaitSeconds: null, p90WaitSeconds: null, timeouts: 1, timeoutShare: 1 / 6 }] }
  return { summary, throughput, steps }
}

describe('Insights aggregate report', () => {
  it('retains exact figures, filters, distinct generation times, nulls and truncation without mutating responses', () => {
    const data = fixtures()
    const original = structuredClone(data)
    const result = insightsReport({ id: 'ws', name: 'Purchasing' }, query, data.summary, data.throughput, data.steps, new Date('2026-10-16T12:01:00Z'))
    expect(result).toMatchObject({ format: 'orqix.insights', version: 1, exportedAt: '2026-10-16T12:01:00.000Z', filters: { ...query, timezone: 'UTC' }, totals: data.summary.totals, sla: { overdueCount: 32 } })
    expect(result.generatedAt.throughput).not.toBe(result.generatedAt.summary)
    expect(result.dailyThroughput[0]!.completed).toBe(6)
    expect(result.totals.completed).toBe(5)
    expect(result.bottlenecks[0]!.averageWaitSeconds).toBeNull()
    expect(result.limits.bottlenecks.truncated).toBe(true)
    expect(data).toEqual(original)
  })

  it('excludes individual requests and unknown future fields at every exported level', () => {
    const data = fixtures()
    const secret = { input: 'PRIVATE-PAYLOAD', output: 'PRIVATE-PAYLOAD', title: 'PRIVATE-PAYLOAD' }
    Object.assign(data.summary, secret)
    Object.assign(data.summary.totals, secret)
    Object.assign(data.summary.cycleTimes[0]!, secret)
    Object.assign(workflow, secret)
    Object.assign(data.throughput.days[0]!, secret)
    Object.assign(data.steps.items[0]!, secret)
    const result = insightsReport({ id: 'ws', name: 'Purchasing' }, query, data.summary, data.throughput, data.steps)
    expect(JSON.stringify(result)).not.toMatch(/PRIVATE-|oldestOpen|runId|needsAttention":\[/)
    expect(result.cycleTimes[0]!.workflow.name).toBe(workflow.name)
  })

  it.each(['summary', 'throughput', 'steps'] as const)('refuses a stale %s period', section => {
    const data = fixtures()
    data[section].range = { ...range, from: '2026-09-01' }
    expect(() => insightsReport({ id: 'ws', name: 'Purchasing' }, query, data.summary, data.throughput, data.steps)).toThrow(/selected period/)
  })

  it('refuses another workflow and marks summary lists that may be capped', () => {
    const data = fixtures()
    expect(() => insightsReport({ id: 'ws', name: 'Purchasing' }, { ...query, workflowId: 'other' }, data.summary, data.throughput, data.steps)).toThrow(/selected workflow/)
    data.summary.cycleTimes = Array.from({ length: 100 }, () => data.summary.cycleTimes[0]!)
    data.summary.sla.timeouts = Array.from({ length: 50 }, () => data.summary.sla.timeouts[0]!)
    const result = insightsReport({ id: 'ws', name: 'Purchasing' }, { from: query.from, to: query.to }, data.summary, data.throughput, data.steps)
    expect(result.filters.workflowId).toBeNull()
    expect(result.limits.cycleTimes.atLimit).toBe(true)
    expect(result.limits.timeoutSteps.atLimit).toBe(true)
  })

  it('writes daily CSV with explicit units and formula-safe cells, including empty reports', () => {
    const data = fixtures()
    expect(throughputCsv(data.throughput)).toBe('"UTC date","Started","Completed","Fulfilled","Rejected"\r\n"2026-10-01","8","6","5","1"\r\n')
    data.throughput.days[0]!.date = '=HYPERLINK("x")'
    expect(throughputCsv(data.throughput)).toContain('"\'=HYPERLINK(""x"")"')
    expect(throughputCsv({ range, days: [] }).split('\r\n')).toHaveLength(2)
    expect(reportFilename(query, 'json')).toBe('orqix-insights-2026-10-01-to-2026-10-16.json')
    expect(reportFilename({ from: '../escape', to: 'bad' }, 'csv')).toBe('orqix-insights-period-to-period.csv')
  })
})
