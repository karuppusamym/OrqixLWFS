import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { formatAge, useJobDashboard } from '@/features/runs/bulkApi'
import { CIRCUIT_LABEL, formatRate } from './connectionHealthApi'

/** A queue whose oldest job waited longer than this, or whose workers were not seen for this long, is flagged. */
const STALE_SECONDS = 15 * 60

function seenAgo(at: string | null, now: string): number | null {
  return at ? Math.max(0, Math.round((Date.parse(now) - Date.parse(at)) / 1000)) : null
}

/**
 * Settings, Operations (CG-O05, docs/build/S142): external job queues by topic, open polling checks, connector package
 * trigger lag and, with connection.read, calls and errors per connection. Counts, times and codes only.
 */
export function JobDashboardPanel() {
  const { workspace } = useWorkspace()
  const query = useJobDashboard(workspace.id)
  if (query.isPending) return <LoadingBlock rows={4} label="Loading the job dashboard" />
  if (query.isError) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="the job dashboard" />
  const d = query.data
  return (
    <div className="space-y-8">
      <p className="text-sm text-muted-foreground">As of {formatDateTime(d.asOf)}. Refreshes every 30 seconds.</p>
      <section aria-labelledby="ops-queues" className="space-y-2">
        <h2 id="ops-queues">External job queues</h2>
        {d.jobQueues.length === 0 ? (
          <p className="text-sm text-muted-foreground">No external worker jobs in the last day.</p>
        ) : (
          <Table>
            <caption className="sr-only">External job queues by topic</caption>
            <THead>
              <TR><TH>Topic</TH><TH>Waiting</TH><TH>In progress</TH><TH>Oldest waiting</TH><TH>Failed (24 h)</TH><TH>Worker last seen</TH></TR>
            </THead>
            <TBody>
              {d.jobQueues.map((q) => {
                const seen = seenAgo(q.workerLastSeenAt, d.asOf)
                const noWorker = q.available > 0 && (seen === null || seen > STALE_SECONDS)
                return (
                  <TR key={q.topic}>
                    <TD className="font-mono text-sm">{q.topic}</TD>
                    <TD>{q.available}</TD>
                    <TD>{q.locked}</TD>
                    <TD>{q.oldestAgeSeconds !== null && q.oldestAgeSeconds > STALE_SECONDS ? <Badge tone="warning">{formatAge(q.oldestAgeSeconds)}</Badge> : formatAge(q.oldestAgeSeconds)}</TD>
                    <TD>{q.failedLastDay > 0 ? <Badge tone="danger">{q.failedLastDay}</Badge> : 0}</TD>
                    <TD>
                      {q.workerLastSeenAt ? `${formatAge(seen)} ago (${q.workersSeenLastHour} in the last hour)` : 'Never'}
                      {noWorker ? <> <Badge tone="warning">No worker</Badge></> : null}
                    </TD>
                  </TR>
                )
              })}
            </TBody>
          </Table>
        )}
      </section>
      <section aria-labelledby="ops-polls" className="space-y-2">
        <h2 id="ops-polls">Checks until done</h2>
        {d.pollChecks.length === 0 ? (
          <p className="text-sm text-muted-foreground">No request is waiting on a polling check.</p>
        ) : (
          <Table>
            <caption className="sr-only">Open polling checks by connection operation</caption>
            <THead>
              <TR><TH>Connection</TH><TH>Operation</TH><TH>Waiting</TH><TH>Checking</TH><TH>Need attention</TH><TH>Late</TH><TH>Next check</TH></TR>
            </THead>
            <TBody>
              {d.pollChecks.map((p) => (
                <TR key={`${p.connectionKey}-${p.operation}`}>
                  <TD>{p.connectionKey}</TD><TD>{p.operation}</TD><TD>{p.waiting}</TD><TD>{p.checking}</TD>
                  <TD>{p.needsAttention > 0 ? <Badge tone="danger">{p.needsAttention}</Badge> : 0}</TD>
                  <TD>{p.lateChecks > 0 ? <Badge tone="warning">{p.lateChecks}</Badge> : 0}</TD>
                  <TD>{formatDateTime(p.nextCheckAt)}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </section>
      <section aria-labelledby="ops-triggers" className="space-y-2">
        <h2 id="ops-triggers">Connector package triggers</h2>
        {d.packageTriggers.length === 0 ? (
          <p className="text-sm text-muted-foreground">No connector package triggers are installed.</p>
        ) : (
          <Table>
            <caption className="sr-only">Package trigger lag</caption>
            <THead>
              <TR><TH>Trigger</TH><TH>Kind</TH><TH>Last success</TH><TH>Lag</TH><TH>Last error</TH><TH>Dead records (24 h)</TH></TR>
            </THead>
            <TBody>
              {d.packageTriggers.map((t) => (
                <TR key={t.id}>
                  <TD>{t.packageId} / {t.triggerKey}{t.enabled ? null : <> <Badge>Off</Badge></>}</TD>
                  <TD>{t.kind === 'poll' ? 'Polling' : 'Webhook'}</TD>
                  <TD>{formatDateTime(t.lastSuccessAt)}</TD>
                  <TD>{formatAge(t.lagSeconds)}</TD>
                  <TD>{t.lastErrorCode ?? '–'}</TD>
                  <TD>{t.deadLastDay > 0 ? <Badge tone="danger">{t.deadLastDay}</Badge> : 0}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </section>
      {d.connections ? (
        <section aria-labelledby="ops-connections" className="space-y-2">
          <h2 id="ops-connections">Connection calls</h2>
          <Table>
            <caption className="sr-only">Calls and errors per connection</caption>
            <THead>
              <TR><TH>Connection</TH><TH>Calls (1 h)</TH><TH>Success (1 h)</TH><TH>Calls (24 h)</TH><TH>Failed (24 h)</TH><TH>Last error</TH><TH>Circuit</TH></TR>
            </THead>
            <TBody>
              {d.connections.map((c) => (
                <TR key={c.connectionKey}>
                  <TD>{c.name}{c.bound ? null : <> <Badge>Not bound</Badge></>}</TD>
                  <TD>{c.callsLastHour}</TD>
                  <TD>{formatRate(c.successRateLastHour)}</TD>
                  <TD>{c.callsLastDay}</TD>
                  <TD>{c.failedLastDay > 0 ? <Badge tone="danger">{c.failedLastDay}</Badge> : 0}</TD>
                  <TD>{c.lastErrorCode ?? '–'}</TD>
                  <TD>{c.circuitState === 'closed' ? CIRCUIT_LABEL[c.circuitState] : <Badge tone="warning">{CIRCUIT_LABEL[c.circuitState]}</Badge>}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </section>
      ) : null}
    </div>
  )
}
