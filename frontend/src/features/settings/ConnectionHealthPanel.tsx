import { Activity, CirclePause, CirclePlay, FlaskConical } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { QueryError } from '@/components/common/QueryError'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import {
  CIRCUIT_LABEL,
  formatLatency,
  formatRate,
  useConnectionHealth,
  useConnectionHealthList,
  useSetRateLimit,
  type ConnectionHealth,
  type ConnectionHealthNotice,
  type ConnectionHealthWindow,
} from './connectionHealthApi'

const NOTICE_TONE = { critical: 'danger', warning: 'warning', info: 'info' } as const

/**
 * Operator notices of every connection (CG-C09, docs/build/S131): open circuits, failing token refresh, low success
 * rate, 429 answers and deferred calls, most severe first. Shown above the connection cards.
 */
export function ConnectionHealthNotices() {
  const { workspace } = useWorkspace()
  const list = useConnectionHealthList(workspace.id)
  if (!list.data) return null
  const notices = list.data.items.flatMap((h) => h.notices.map((n) => ({ ...n, connectionKey: h.connectionKey, name: h.name })))
  if (notices.length === 0) return null
  const rank = { critical: 0, warning: 1, info: 2 } as const
  notices.sort((a, b) => rank[a.severity] - rank[b.severity])
  return (
    <section aria-labelledby="connection-notices" className="space-y-2">
      <h2 id="connection-notices">Connection notices</h2>
      {notices.map((n) => (
        <Alert key={`${n.connectionKey}-${n.code}`} tone={NOTICE_TONE[n.severity]} title={n.name} live="polite" data-testid={`connection-notice-${n.connectionKey}-${n.code}`}>
          {n.message}
        </Alert>
      ))}
    </section>
  )
}

/** Health of one connection binding inside its card. */
export function ConnectionHealthSection({ connectionKey, name }: { connectionKey: string; name: string }) {
  const { workspace, can } = useWorkspace()
  const query = useConnectionHealth(workspace.id, connectionKey)
  if (query.isPending) return null
  if (query.isError) {
    return (
      <div className="border-t border-border p-5">
        <QueryError error={query.error} onRetry={() => void query.refetch()} what={`the health of ${name}`} />
      </div>
    )
  }
  const h = query.data
  return (
    <div className="space-y-4 border-t border-border p-5" data-testid={`connection-health-${connectionKey}`}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 text-sm text-muted-foreground uppercase">
          <Activity aria-hidden="true" className="size-4" /> Health
        </h3>
        <CircuitBadge health={h} />
      </div>
      {h.notices.map((n) => (
        <NoticeLine key={n.code} notice={n} />
      ))}
      <div className="overflow-x-auto rounded-card border border-border">
        <Table>
          <caption className="sr-only">Calls to {name} in the last hour and day</caption>
          <THead>
            <TR>
              <TH>Period</TH>
              <TH>Calls</TH>
              <TH>Succeeded</TH>
              <TH>p50</TH>
              <TH>p95</TH>
              <TH>Too many requests (429)</TH>
              <TH>Token refresh failures</TH>
              <TH>Waited</TH>
            </TR>
          </THead>
          <TBody>
            <WindowRow label="Last hour" w={h.lastHour} />
            <WindowRow label="Last 24 hours" w={h.lastDay} />
          </TBody>
        </Table>
      </div>
      <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted-foreground">Last error</dt>
          <dd data-testid={`connection-last-error-${connectionKey}`}>
            {h.lastError ? (
              <>
                <span className="font-mono">{h.lastError.errorCode ?? h.lastError.outcome}</span>
                {h.lastError.httpStatus ? ` (HTTP ${h.lastError.httpStatus})` : ''} at {formatDateTime(h.lastError.at)}
              </>
            ) : (
              'None in the last 24 hours'
            )}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Request limit</dt>
          <dd>
            {h.rateLimit.maxRequestsPerMinute === null
              ? 'No limit'
              : `${h.rateLimit.maxRequestsPerMinute} a minute (${h.rateLimit.perPod} on each of ${h.rateLimit.livePods} server${h.rateLimit.livePods === 1 ? '' : 's'})`}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Pause after</dt>
          <dd>
            {h.circuit.failureThreshold} failures in a row ({h.circuit.consecutiveFailures} now); paused {h.circuit.timesOpened} time
            {h.circuit.timesOpened === 1 ? '' : 's'}
          </dd>
        </div>
      </dl>
      {can('connection.manage') ? <RateLimitForm key={String(h.rateLimit.maxRequestsPerMinute)} health={h} /> : null}
    </div>
  )
}

function CircuitBadge({ health: h }: { health: ConnectionHealth }) {
  const state = h.circuit.state
  const tone = state === 'open' ? 'danger' : state === 'halfOpen' ? 'warning' : 'success'
  const Icon = state === 'open' ? CirclePause : state === 'halfOpen' ? FlaskConical : CirclePlay
  return (
    <span className="flex flex-wrap items-center gap-2" data-testid={`circuit-${h.connectionKey}`}>
      <Badge tone={tone}>
        <Icon aria-hidden="true" /> {CIRCUIT_LABEL[state]}
      </Badge>
      {state !== 'closed' && h.circuit.openUntil ? (
        <span className="text-sm text-muted-foreground">until {formatDateTime(h.circuit.openUntil)}</span>
      ) : null}
    </span>
  )
}

function NoticeLine({ notice }: { notice: ConnectionHealthNotice }) {
  return (
    <Alert tone={NOTICE_TONE[notice.severity]} live="off">
      {notice.message}
    </Alert>
  )
}

function WindowRow({ label, w }: { label: string; w: ConnectionHealthWindow }) {
  return (
    <TR>
      <TD className="font-semibold">{label}</TD>
      <TD>{w.calls}</TD>
      <TD>{formatRate(w.successRate)}</TD>
      <TD>{formatLatency(w.p50Ms)}</TD>
      <TD>{formatLatency(w.p95Ms)}</TD>
      <TD>{w.rateLimited}</TD>
      <TD>{w.tokenRefreshFailures}</TD>
      <TD>{w.deferred}</TD>
    </TR>
  )
}

function RateLimitForm({ health: h }: { health: ConnectionHealth }) {
  const { workspace } = useWorkspace()
  const mutation = useSetRateLimit(workspace.id, h.connectionKey)
  const [value, setValue] = useState(h.rateLimit.maxRequestsPerMinute === null ? '' : String(h.rateLimit.maxRequestsPerMinute))
  const [error, setError] = useState<string | undefined>()
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const text = value.trim()
    if (text === '') {
      setError(undefined)
      mutation.mutate(null)
      return
    }
    const limit = Number(text)
    if (!Number.isInteger(limit) || limit < 1 || limit > 100000) {
      setError('Enter a whole number from 1 to 100000, or leave it empty for no limit.')
      return
    }
    setError(undefined)
    mutation.mutate(limit)
  }
  return (
    <form onSubmit={submit} className="flex flex-wrap items-end gap-3" aria-label={`Request limit for ${h.name}`}>
      <FormField
        label="Requests a minute"
        description="Calls over the limit wait for a free slot without using an attempt. Leave empty for no limit."
        error={error}
        className="min-w-64"
      >
        {(ids) => (
          <Input
            id={ids.input}
            inputMode="numeric"
            aria-describedby={ids.describedBy}
            aria-invalid={error ? true : undefined}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        )}
      </FormField>
      <Button type="submit" loading={mutation.isPending}>
        Save limit
      </Button>
      {mutation.isError ? <ProblemAlert error={mutation.error} /> : null}
      {mutation.isSuccess ? <span role="status" className="text-sm text-muted-foreground">Saved.</span> : null}
    </form>
  )
}
