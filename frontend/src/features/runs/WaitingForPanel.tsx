import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Hourglass } from 'lucide-react'
import { useState } from 'react'
import { api, call } from '@/api/client'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import type { ProblemError } from '@/api/problem'
import type { components } from '@/api/schema'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Select, Textarea } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'
import { describe as describeSeconds, seconds } from '@/features/studio/model/pollSchedule'

type RunWait = components['schemas']['RunWait']
type PollCheck = components['schemas']['PollCheck']
type Action = 'check-now' | 'extend-deadline' | 'stop'

export const WAIT_KIND: Record<RunWait['kind'], string> = {
  approval: 'A decision',
  timer: 'A set time',
  message: 'A message',
  callback: 'An external system',
  poll: 'Checking another system',
  externalJob: 'An outside worker',
  childRequest: 'Another request',
  signal: 'A signal',
}

export const CHECK_STATE: Record<PollCheck['state'], string> = {
  waiting: 'Waiting for the next check',
  checking: 'Checking now',
  met: 'Done',
  failed: 'Failed for good',
  timedOut: 'Gave up',
  cancelled: 'Stopped',
  attention: 'Checks stopped; needs an operator',
}

const EXTEND = ['PT1H', 'PT6H', 'P1D', 'P2D', 'P7D', 'P30D']

/**
 * "Waiting for" (docs/build/S129, CG-W08): one line per open wait of the request, whatever its kind, in plain words,
 * with the next check, the deadline and how often it was checked. People with run.operate can check now (once a
 * minute), extend the deadline (up to twice the authored time) or stop waiting; each needs a reason for the audit log.
 */
export function WaitingForPanel({ ws, runId }: { ws: string; runId: string }) {
  const client = useQueryClient()
  const key = ['ws', ws, 'run-waits', runId]
  const query = useQuery({
    queryKey: key,
    retry: false,
    refetchInterval: 15_000,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/runs/{runId}/waits', { params: { path: { workspaceId: ws, runId } } })),
  })
  const [open, setOpen] = useState<{ action: Action; wait: RunWait } | null>(null)
  const items = query.data?.items ?? []
  if (items.length === 0) return null

  return (
    <section aria-labelledby="waiting-for-heading" className="rounded-card border border-border bg-surface p-4" data-testid="waiting-for">
      <h2 id="waiting-for-heading" className="mb-1 flex items-center gap-2 text-base">
        <Hourglass className="size-4" aria-hidden="true" /> Waiting for
      </h2>
      <ul className="divide-y divide-border" aria-label="What this request is waiting for">
        {items.map((w) => (
          <li key={w.nodeId} className="space-y-1.5 py-2 text-sm" data-testid={`wait-${w.nodeId}`}>
            <p className="flex flex-wrap items-center gap-2">
              <Badge>{WAIT_KIND[w.kind]}</Badge>
              <span className="font-semibold">{w.subject}</span>
            </p>
            <p className="text-muted-foreground">
              Since {formatDateTime(w.since)}
              {w.nextCheckAt ? ` · next check ${formatDateTime(w.nextCheckAt)}` : ''}
              {w.dueAt ? ` · due ${formatDateTime(w.dueAt)}` : ''}
              {w.deadlineAt ? ` · gives up ${formatDateTime(w.deadlineAt)}` : ''}
              {w.check ? ` · checked ${w.check.checksDone} of at most ${w.check.maxChecks} times` : ''}
            </p>
            {w.check ? <CheckLine check={w.check} /> : null}
            {w.check?.canOperate && ['waiting', 'attention'].includes(w.check.state) ? (
              <div className="flex flex-wrap gap-2">
                <Button variant="secondary" onClick={() => setOpen({ action: 'check-now', wait: w })} aria-label={`Check now: ${w.label}`}>Check now</Button>
                <Button variant="secondary" onClick={() => setOpen({ action: 'extend-deadline', wait: w })} aria-label={`Extend the deadline: ${w.label}`}>Extend deadline</Button>
                <Button variant="danger" onClick={() => setOpen({ action: 'stop', wait: w })} aria-label={`Stop waiting: ${w.label}`}>Stop waiting</Button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {open ? (
        <WaitActionDialog ws={ws} runId={runId} action={open.action} wait={open.wait}
          onClose={() => setOpen(null)} onDone={() => void client.invalidateQueries({ queryKey: key })} />
      ) : null}
    </section>
  )
}

function CheckLine({ check }: { check: PollCheck }) {
  const shown = check.shown && Object.keys(check.shown).length > 0
    ? Object.entries(check.shown).map(([k, v]) => `${k}: ${String(v)}`).join(', ') : null
  return (
    <p className="text-muted-foreground" data-testid="wait-check">
      {CHECK_STATE[check.state]}
      {check.kind === 'fallback' ? ' (safety-net check while waiting for the message)' : ''}
      {check.lastCheckedAt ? ` · last checked ${formatDateTime(check.lastCheckedAt)}` : ''}
      {check.lastOutcome ? ` (${check.lastOutcome})` : ''}
      {check.consecutiveErrors > 0 ? ` · ${check.consecutiveErrors} failing checks in a row` : ''}
      {shown ? ` · last answer ${shown}` : ''}
      {check.deadlineExtensions > 0 ? ` · deadline extended ${check.deadlineExtensions} ${check.deadlineExtensions === 1 ? 'time' : 'times'}` : ''}
    </p>
  )
}

const TITLE: Record<Action, string> = { 'check-now': 'Check now', 'extend-deadline': 'Extend the deadline', stop: 'Stop waiting' }

function WaitActionDialog({ ws, runId, action, wait, onClose, onDone }: {
  ws: string; runId: string; action: Action; wait: RunWait; onClose: () => void; onDone: () => void
}) {
  const check = wait.check as PollCheck
  const announce = useAnnounce()
  const idem = useIdempotencyKey()
  const room = Math.max(0, (new Date(check.extensionLimit).getTime() - new Date(check.deadlineAt).getTime()) / 1000)
  const choices = EXTEND.filter((p) => seconds(p) <= room)
  const [reason, setReason] = useState('')
  const [extendBy, setExtendBy] = useState(choices[0] ?? '')
  const [reasonError, setReasonError] = useState<string>()
  const [error, setError] = useState<ProblemError | null>(null)
  const [pending, setPending] = useState(false)

  const submit = async () => {
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded in the audit log.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const params = { path: { workspaceId: ws, runId, waitId: check.waitId }, header: { 'Idempotency-Key': idem.keyFor(fingerprintOf(action, check.waitId, reason.trim(), extendBy)) } }
      if (action === 'check-now') await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/waits/{waitId}/check-now', { params, body: { reason: reason.trim() } }))
      else if (action === 'stop') await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/waits/{waitId}/stop', { params, body: { reason: reason.trim() } }))
      else await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/waits/{waitId}/extend-deadline', { params, body: { reason: reason.trim(), extendBy } }))
      announce(action === 'check-now' ? 'The check runs within a few seconds.' : action === 'stop' ? 'Stopped waiting.' : 'Deadline extended.')
      onDone()
      onClose()
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open onClose={onClose} title={TITLE[action]} dismissible={!pending}
      description={action === 'check-now' ? `Ask ${wait.label} again now instead of at the next planned check. At most once a minute.`
        : action === 'stop' ? (check.kind === 'fallback' ? 'Stop the safety-net check. The step keeps waiting for the message.' : 'Stop checking. The request takes the path for when the checks give up.')
          : `Give the other system more time. The deadline is ${formatDateTime(check.deadlineAt)}; it can move to ${formatDateTime(check.extensionLimit)} at the latest.`}
      footer={<>
        <Button variant="secondary" onClick={onClose} disabled={pending}>Cancel</Button>
        <Button variant={action === 'stop' ? 'danger' : 'primary'} onClick={() => void submit()} loading={pending}
          disabled={action === 'extend-deadline' && !extendBy}>{TITLE[action]}</Button>
      </>}>
      <div className="space-y-4">
        {action === 'extend-deadline' ? (
          choices.length ? (
            <FormField label="Extend by" id="wait-extend-by">
              {(ids) => (
                <Select id={ids.input} value={extendBy} onChange={(e) => setExtendBy(e.target.value)}>
                  {choices.map((p) => <option key={p} value={p}>{describeSeconds(seconds(p))}</option>)}
                </Select>
              )}
            </FormField>
          ) : <p className="text-sm text-muted-foreground">The deadline is already as late as it can be.</p>
        ) : null}
        <FormField label="Reason" required id="wait-reason" error={reasonError} description="Recorded in the audit log with your name.">
          {(ids) => <Textarea id={ids.input} value={reason} maxLength={500} rows={3} onChange={(e) => setReason(e.target.value)} aria-invalid={reasonError ? true : undefined} aria-describedby={ids.describedBy} aria-required />}
        </FormField>
        {error ? <ProblemAlert error={error} /> : null}
      </div>
    </Dialog>
  )
}
