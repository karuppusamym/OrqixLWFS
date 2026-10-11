import { AlertTriangle, Download, Hourglass, TimerOff } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useWorkflows } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Select } from '@/components/ui/input'
import { ScrollTable } from '@/components/common/ScrollTable'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { downloadBlob } from '@/lib/download'
import { formatDateTime } from '@/lib/dates'
import { humanize } from '@/lib/text'
import { useInsightsSteps, useInsightsSummary, useInsightsThroughput, type InsightsSteps, type InsightsSummary } from './api'
import { formatSeconds, percent, rangeFor, STEP_KIND_LABEL, stepsCsv } from './format'
import { ThroughputChart } from './ThroughputChart'

const RANGES = [7, 30, 90] as const

/**
 * Insights (docs/build/S7-frontend.md section 9): workflow analytics for people with run.read. Aggregates only; requests
 * appear by reference, never by title or data. Every chart has a table with the same numbers.
 */
export function InsightsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const [search, setSearch] = useSearchParams()
  const days = RANGES.find((d) => String(d) === search.get('days')) ?? 30
  const workflowId = search.get('workflow') ?? undefined
  const query = useMemo(() => ({ ...rangeFor(days), workflowId }), [days, workflowId])
  const workflows = useWorkflows(ws, {}, can('workflow.read'))
  const options = workflows.data?.pages.flatMap((p) => p.items) ?? []

  const allowed = can('run.read')
  const summary = useInsightsSummary(ws, query, allowed)
  const throughput = useInsightsThroughput(ws, query, allowed)
  const steps = useInsightsSteps(ws, query, allowed)

  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(search)
    if (value) next.set(key, value)
    else next.delete(key)
    setSearch(next, { replace: true })
  }

  if (!allowed) {
    return (
      <Alert tone="warning" title="No access">
        Insights are for people who can see all requests of this workspace. Ask a workspace administrator if you need them.
      </Alert>
    )
  }
  if (summary.isError) return <QueryError error={summary.error} onRetry={() => void summary.refetch()} what="Insights" />

  return (
    <>
      <PageHeader
        title="Insights"
        description="How requests move through your workflows. These are figures only: no request titles or data."
      />

      <div className="mb-6 flex flex-wrap items-end gap-4" role="search" aria-label="Insights filters">
        {options.length > 0 ? (
          <div className="min-w-0 sm:w-72">
            <label htmlFor="insights-workflow" className="mb-1 block text-sm font-semibold">
              Workflow
            </label>
            <Select id="insights-workflow" value={workflowId ?? ''} onChange={(e) => set('workflow', e.target.value || null)}>
              <option value="">All workflows</option>
              {options.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name}
                </option>
              ))}
            </Select>
          </div>
        ) : null}
        {workflows.hasNextPage ? <Button variant="secondary" disabled={workflows.isFetchingNextPage} onClick={() => void workflows.fetchNextPage()}>
          {workflows.isFetchingNextPage ? 'Loading workflows?' : 'Load more workflows'}
        </Button> : null}
        <fieldset>
          <legend className="mb-1 text-sm font-semibold">Period</legend>
          <div className="inline-flex rounded-full border border-border bg-neutral-soft p-0.5" role="group" aria-label="Period" data-testid="insights-range">
            {RANGES.map((d) => (
              <button
                key={d}
                type="button"
                aria-pressed={days === d}
                onClick={() => set('days', d === 30 ? null : String(d))}
                className={
                  days === d
                    ? 'min-h-10 rounded-full bg-surface px-4 text-sm font-semibold text-primary-text shadow-card'
                    : 'min-h-10 rounded-full px-4 text-sm font-semibold text-muted-foreground hover:bg-neutral-hover'
                }
              >
                Last {d} days
              </button>
            ))}
          </div>
        </fieldset>
        {summary.data ? (
          <p className="pb-2 text-sm text-muted-foreground">
            {summary.data.range.from} to {summary.data.range.to} (UTC days)
          </p>
        ) : null}
      </div>

      {summary.isPending ? (
        <LoadingBlock rows={4} label="Calculating the figures" />
      ) : (
        <div className="space-y-6" aria-busy={summary.isFetching}>
          <Cards data={summary.data} />

          <Card>
            <CardHeader>
              <h2>Requests per day</h2>
            </CardHeader>
            <CardContent>
              {throughput.isPending ? <LoadingBlock rows={3} label="Loading the chart" /> : throughput.isError ? <QueryError error={throughput.error} onRetry={() => void throughput.refetch()} what="the chart" /> : <ThroughputChart days={throughput.data.days} />}
            </CardContent>
          </Card>

          <CycleTimes data={summary.data} />
          <Bottlenecks query={steps} />

          <div className="grid gap-6 xl:grid-cols-2">
            <NeedsAttention ws={ws} data={summary.data} />
            <OldestOpen ws={ws} data={summary.data} />
          </div>

          <Sla ws={ws} data={summary.data} />
        </div>
      )}
    </>
  )
}

function Stat({ label, value, note, testId }: { label: string; value: number; note?: string; testId: string }) {
  return (
    <div className="rounded-card border border-border bg-surface p-4 shadow-card" data-testid={testId}>
      <p className="text-sm font-semibold text-muted-foreground">{label}</p>
      <p className="mt-1 text-3xl font-bold tabular-nums">{value}</p>
      {note ? <p className="mt-1 text-sm text-muted-foreground">{note}</p> : null}
    </div>
  )
}

function Cards({ data }: { data: InsightsSummary }) {
  const t = data.totals
  return (
    <section aria-label="Totals for the period" className="grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6" data-testid="insights-cards">
      <Stat testId="card-started" label="Requests started" value={t.started} />
      <Stat testId="card-completed" label="Completed" value={t.completed} note={t.cancelled > 0 ? `${t.cancelled} cancelled` : undefined} />
      <Stat testId="card-fulfilled" label="Fulfilled" value={t.fulfilled} note={t.completed ? `${percent(t.fulfilled / t.completed)} of completed` : undefined} />
      <Stat testId="card-rejected" label="Rejected" value={t.rejected} note={t.completed ? `${percent(t.rejected / t.completed)} of completed` : undefined} />
      <Stat testId="card-open" label="Still open" value={t.open} />
      <Stat testId="card-attention" label="Needing attention" value={t.needsAttention} note={t.needsAttention > 0 ? 'An operator should look' : undefined} />
    </section>
  )
}

function CycleTimes({ data }: { data: InsightsSummary }) {
  return (
    <Card>
      <CardHeader>
        <h2>Cycle time</h2>
        <p className="text-sm text-muted-foreground">From submitted to finished, for requests that completed in this period. The median is the typical request; 90 percent finish within the 90th percentile time.</p>
      </CardHeader>
      <CardContent>
        {data.cycleTimes.length === 0 ? (
          <p className="text-sm text-muted-foreground">No request has completed in this period.</p>
        ) : (
          <ScrollTable label="Cycle time per workflow">
            <THead>
              <TR>
                <TH>Workflow</TH>
                <TH>Completed</TH>
                <TH>Median</TH>
                <TH>90th percentile</TH>
              </TR>
            </THead>
            <TBody>
              {data.cycleTimes.map((c) => (
                <TR key={c.workflow.id}>
                  <TD className="font-semibold">{c.workflow.name}</TD>
                  <TD className="tabular-nums">{c.completed}</TD>
                  <TD className="tabular-nums">{formatSeconds(c.medianSeconds)}</TD>
                  <TD className="tabular-nums">{formatSeconds(c.p90Seconds)}</TD>
                </TR>
              ))}
            </TBody>
          </ScrollTable>
        )}
      </CardContent>
    </Card>
  )
}

function Bottlenecks({ query }: { query: { isPending: boolean; isError: boolean; error: unknown; data: InsightsSteps | undefined; refetch: () => unknown } }) {
  const items = query.data?.items ?? []
  const maxAverage = Math.max(1, ...items.map((s) => s.averageWaitSeconds ?? 0))
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2>Where requests wait</h2>
            <p className="text-sm text-muted-foreground">Time spent waiting at each approval or wait, slowest first. Choose a workflow above to look at one.</p>
          </div>
          <Button variant="secondary" disabled={items.length === 0} onClick={() => downloadBlob(new Blob([stepsCsv(items)], { type: 'text/csv;charset=utf-8' }), 'orqix-insights-steps.csv')}>
            <Download aria-hidden="true" /> Download CSV
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        {query.isPending ? (
          <LoadingBlock rows={3} label="Loading the table" />
        ) : query.isError ? (
          <QueryError error={query.error} onRetry={() => void query.refetch()} what="the table" />
        ) : items.length === 0 ? (
          <p className="text-sm text-muted-foreground">No approval or wait has finished in this period.</p>
        ) : (
          <>
            <ScrollTable label="Waiting time per step" data-testid="bottleneck-table">
              <THead>
                <TR>
                  <TH>Step</TH>
                  <TH>Type</TH>
                  <TH>Finished</TH>
                  <TH>Waiting now</TH>
                  <TH>Average wait</TH>
                  <TH>90th percentile</TH>
                  <TH>Timed out</TH>
                </TR>
              </THead>
              <TBody>
                {items.map((s) => (
                  <TR key={`${s.workflow.id}|${s.kind}|${s.nodeId}`}>
                    <TD className="min-w-48">
                      <span className="font-semibold">{s.label}</span>
                      <span className="block text-xs text-muted-foreground">{s.workflow.name}</span>
                    </TD>
                    <TD>{STEP_KIND_LABEL[s.kind]}</TD>
                    <TD className="tabular-nums">{s.count}</TD>
                    <TD className="tabular-nums">{s.openNow}</TD>
                    <TD className="min-w-40">
                      <span className="tabular-nums">{formatSeconds(s.averageWaitSeconds)}</span>
                      <span className="mt-1 block h-2 rounded-full bg-neutral-soft" aria-hidden="true">
                        <span className="block h-2 rounded-full bg-primary" style={{ width: `${Math.max(2, Math.round(((s.averageWaitSeconds ?? 0) / maxAverage) * 100))}%` }} />
                      </span>
                    </TD>
                    <TD className="tabular-nums">{formatSeconds(s.p90WaitSeconds)}</TD>
                    <TD className="tabular-nums">{s.timeouts > 0 ? `${s.timeouts} (${percent(s.timeoutShare)})` : '0'}</TD>
                  </TR>
                ))}
              </TBody>
            </ScrollTable>
            {query.data?.truncated ? <p className="mt-2 text-sm text-muted-foreground">Showing the 200 slowest steps. Choose one workflow to narrow the list.</p> : null}
          </>
        )}
      </CardContent>
    </Card>
  )
}

function NeedsAttention({ ws, data }: { ws: string; data: InsightsSummary }) {
  return (
    <Card data-testid="attention-list">
      <CardHeader>
        <h2 className="flex items-center gap-2">
          <AlertTriangle className="size-5 text-status-attention-fg" aria-hidden="true" /> Needing attention
        </h2>
      </CardHeader>
      <CardContent>
        {data.needsAttention.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing needs attention right now.</p>
        ) : (
          <ul className="divide-y divide-border">
            {data.needsAttention.map((r) => (
              <li key={r.runId} className="py-2 text-sm">
                <Link className="font-semibold underline" to={`/w/${ws}/runs/${r.runId}`}>
                  {r.reference}
                </Link>{' '}
                <span className="text-muted-foreground">{r.workflow.name}</span>
                <p>
                  {r.stepLabel ? `${r.stepLabel}: ` : ''}
                  {humanize(r.reason)} <span className="text-muted-foreground">since {formatDateTime(r.since)}</span>
                </p>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function OldestOpen({ ws, data }: { ws: string; data: InsightsSummary }) {
  return (
    <Card data-testid="oldest-open">
      <CardHeader>
        <h2 className="flex items-center gap-2">
          <Hourglass className="size-5 text-status-waiting-fg" aria-hidden="true" /> Oldest open requests
        </h2>
      </CardHeader>
      <CardContent>
        {data.oldestOpen.length === 0 ? (
          <p className="text-sm text-muted-foreground">No request is open.</p>
        ) : (
          <ul className="divide-y divide-border">
            {data.oldestOpen.map((r) => (
              <li key={r.runId} className="py-2 text-sm">
                <Link className="font-semibold underline" to={`/w/${ws}/runs/${r.runId}`}>
                  {r.reference}
                </Link>{' '}
                <span className="text-muted-foreground">{r.workflow.name}</span>
                <p>
                  Open for {formatSeconds(r.ageSeconds)}
                  {r.stepLabel ? <span className="text-muted-foreground">, at {r.stepLabel}</span> : null}
                </p>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function Sla({ ws, data }: { ws: string; data: InsightsSummary }) {
  const { timeouts, overdue, overdueCount } = data.sla
  return (
    <Card data-testid="sla-view">
      <CardHeader>
        <h2 className="flex items-center gap-2">
          <TimerOff className="size-5 text-status-attention-fg" aria-hidden="true" /> Time limits and due dates
        </h2>
        <p className="text-sm text-muted-foreground">Approvals that reached their time limit in this period, and approvals or waits that are past their due date now.</p>
      </CardHeader>
      <CardContent className="space-y-6">
        <div>
          <h3 className="mb-2 text-base">Approvals that timed out</h3>
          {timeouts.length === 0 ? (
            <EmptyState title="No approval timed out">Every approval in this period was decided before its time limit, or the workflows have no time limit.</EmptyState>
          ) : (
            <ScrollTable label="Approvals that reached their time limit">
              <THead>
                <TR>
                  <TH>Step</TH>
                  <TH>Timed out</TH>
                  <TH>Approvals ended</TH>
                  <TH>Share</TH>
                </TR>
              </THead>
              <TBody>
                {timeouts.map((t) => (
                  <TR key={`${t.workflow.id}|${t.nodeId}`}>
                    <TD>
                      <span className="font-semibold">{t.label}</span>
                      <span className="block text-xs text-muted-foreground">{t.workflow.name}</span>
                    </TD>
                    <TD className="tabular-nums">{t.timeouts}</TD>
                    <TD className="tabular-nums">{t.decided}</TD>
                    <TD className="tabular-nums">{percent(t.share)}</TD>
                  </TR>
                ))}
              </TBody>
            </ScrollTable>
          )}
        </div>
        <div>
          <h3 className="mb-2 text-base">Overdue now{overdueCount > 0 ? ` (${overdueCount})` : ''}</h3>
          {overdue.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing is past its due date.</p>
          ) : (
            <ScrollTable label="Approvals and waits past their due date">
              <THead>
                <TR>
                  <TH>Request</TH>
                  <TH>Step</TH>
                  <TH>Due</TH>
                  <TH>Overdue by</TH>
                </TR>
              </THead>
              <TBody>
                {overdue.map((o) => (
                  <TR key={`${o.runId}|${o.stepLabel}`}>
                    <TD>
                      <Link className="font-semibold underline" to={`/w/${ws}/runs/${o.runId}`}>
                        {o.reference}
                      </Link>
                      <span className="block text-xs text-muted-foreground">{o.workflow.name}</span>
                    </TD>
                    <TD>
                      {o.stepLabel} <span className="text-muted-foreground">({STEP_KIND_LABEL[o.kind]})</span>
                    </TD>
                    <TD>{formatDateTime(o.dueAt)}</TD>
                    <TD className="font-semibold text-danger tabular-nums">{formatSeconds(o.overdueSeconds)}</TD>
                  </TR>
                ))}
              </TBody>
            </ScrollTable>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
