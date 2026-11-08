import { Layers } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useReleases } from '@/api/queries'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import {
  BULK_ACTION_LABEL,
  BULK_ACTION_RULE,
  ITEM_STATE_LABEL,
  useBulkItems,
  useBulkOperation,
  useControlBulk,
  useExecuteBulk,
  usePreviewBulk,
  type BulkAction,
  type BulkOperation,
  type BulkRunFilter,
} from './bulkApi'

const ACTIONS: BulkAction[] = ['retry', 'cancel', 'suspend', 'resume', 'migrate']

const STATE_TONE = { previewed: 'info', running: 'primary', paused: 'warning', completed: 'success', cancelled: 'neutral' } as const
const STATE_LABEL = { previewed: 'Dry run', running: 'Running', paused: 'Paused', completed: 'Completed', cancelled: 'Cancelled' } as const

/** True when the filter narrows the runs enough for a bulk operation (the server refuses the rest with 422). */
export function filterNarrows(filter: BulkRunFilter): boolean {
  return !!(filter.workflowId || filter.businessKey || filter.createdFrom || filter.createdTo || filter.overdue || filter.attentionReason || filter.errorCode || filter.fields?.length)
}

/**
 * Bulk operations on the Runs page (CG-O01, docs/build/S142), for people with run.operate and the grant-only
 * run.bulk-operate. The current filters are the selection: a dry run shows the exact count and the first requests;
 * starting needs that count typed again and a reason. Progress, per-request results and pause, resume and cancel
 * follow while the batch runs in the background.
 */
export function BulkActionsPanel({ ws, filter }: { ws: string; filter: BulkRunFilter }) {
  const [open, setOpen] = useState(false)
  const [action, setAction] = useState<BulkAction>('retry')
  const [target, setTarget] = useState('')
  const [operationId, setOperationId] = useState<string | null>(null)
  const preview = usePreviewBulk(ws)
  const releases = useReleases(ws, filter.workflowId ?? '', open && action === 'migrate' && !!filter.workflowId)
  const releaseItems = releases.data?.pages.flatMap((p) => p.items) ?? []
  const narrows = filterNarrows(filter)

  if (!open) {
    return (
      <div className="mb-4">
        <Button variant="secondary" onClick={() => setOpen(true)}>
          <Layers aria-hidden="true" /> Bulk action on these runs
        </Button>
      </div>
    )
  }

  const runDryRun = (event: FormEvent) => {
    event.preventDefault()
    setOperationId(null)
    preview.mutate(
      { action, filter, ...(action === 'migrate' && target ? { targetReleaseId: target } : {}) },
      { onSuccess: (op) => setOperationId(op.id) },
    )
  }

  return (
    <section aria-labelledby="bulk-heading" className="mb-4 space-y-4 rounded-card border border-border bg-surface p-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="bulk-heading">Bulk action</h2>
        <Button variant="ghost" size="sm" onClick={() => { setOpen(false); setOperationId(null); preview.reset() }}>Close</Button>
      </div>
      <p className="text-sm text-muted-foreground">
        Uses the filters above. A dry run changes nothing: it shows exactly which requests would change. Each request is then
        changed on its own with the usual checks, and a request that no longer matches when its turn comes is skipped.
      </p>
      {!narrows ? <Alert tone="warning" title="Narrow the runs first">Choose a workflow, a request reference, an error code, a field condition or the overdue filter.</Alert> : null}
      <form className="flex flex-wrap items-end gap-3" onSubmit={runDryRun}>
        <FormField label="Action" id="bulk-action">
          {(ids) => (
            <Select id={ids.input} value={action} onChange={(e) => { setAction(e.target.value as BulkAction); setOperationId(null) }} className="w-48">
              {ACTIONS.map((a) => <option key={a} value={a}>{BULK_ACTION_LABEL[a]}</option>)}
            </Select>
          )}
        </FormField>
        {action === 'migrate' ? (
          <FormField label="Target release" id="bulk-target">
            {(ids) => (
              <Select id={ids.input} value={target} onChange={(e) => setTarget(e.target.value)} className="w-48" disabled={!filter.workflowId}>
                <option value="">Choose a release</option>
                {releaseItems.map((r) => <option key={r.id} value={r.id}>Release {r.releaseNumber}</option>)}
              </Select>
            )}
          </FormField>
        ) : null}
        <Button type="submit" variant="secondary" loading={preview.isPending} disabled={!narrows || (action === 'migrate' && !target)}>
          Dry run
        </Button>
      </form>
      <p className="text-sm text-muted-foreground">{BULK_ACTION_LABEL[action]} selects {BULK_ACTION_RULE[action]}.</p>
      {preview.isError ? <ProblemAlert error={preview.error} /> : null}
      {operationId ? <BulkOperationView ws={ws} id={operationId} /> : null}
    </section>
  )
}

function BulkOperationView({ ws, id }: { ws: string; id: string }) {
  const query = useBulkOperation(ws, id)
  const op = query.data
  if (!op) return null
  return op.state === 'previewed' ? <Confirm ws={ws} op={op} /> : <Progress ws={ws} op={op} />
}

function Confirm({ ws, op }: { ws: string; op: BulkOperation }) {
  const [typed, setTyped] = useState('')
  const [reason, setReason] = useState('')
  const [key] = useState(() => crypto.randomUUID())
  const execute = useExecuteBulk(ws)
  if (op.exceedsCap) {
    return (
      <Alert tone="danger" title={`${op.matchedCount} requests match`}>
        One bulk operation changes at most {op.cap}. Narrow the filters and run the dry run again.
      </Alert>
    )
  }
  if (op.matchedCount === 0) {
    return <Alert tone="info" title="No request matches">Nothing would change. Check the filters and the action.</Alert>
  }
  const start = (event: FormEvent) => {
    event.preventDefault()
    execute.mutate({ id: op.id, confirmedCount: Number(typed), reason: reason.trim(), key })
  }
  const confirmed = typed.trim() === String(op.matchedCount) && reason.trim().length > 0
  return (
    <div className="space-y-3">
      <p role="status" className="font-medium">
        {BULK_ACTION_LABEL[op.action]}: {op.matchedCount} {op.matchedCount === 1 ? 'request' : 'requests'} match
      </p>
      <SampleTable op={op} />
      {op.matchedCount > op.sample.length ? <p className="text-sm text-muted-foreground">and {op.matchedCount - op.sample.length} more.</p> : null}
      <p className="text-sm text-muted-foreground">This dry run is valid until {formatDateTime(op.previewExpiresAt)}. At most {op.ratePerMinute} requests change per minute.</p>
      <form className="space-y-3" onSubmit={start}>
        <FormField label={`Type ${op.matchedCount} to confirm the number of requests`} id="bulk-confirm" required>
          {(ids) => <Input id={ids.input} aria-describedby={ids.describedBy} inputMode="numeric" value={typed} onChange={(e) => setTyped(e.target.value)} className="w-32" autoComplete="off" />}
        </FormField>
        <FormField label="Reason" id="bulk-reason" required description="Shown on each request's timeline and in the audit log.">
          {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} value={reason} maxLength={450} onChange={(e) => setReason(e.target.value)} rows={2} />}
        </FormField>
        {execute.isError ? <ProblemAlert error={execute.error} /> : null}
        <Button type="submit" variant="danger" disabled={!confirmed} loading={execute.isPending}>
          {BULK_ACTION_LABEL[op.action]} {op.matchedCount} {op.matchedCount === 1 ? 'request' : 'requests'}
        </Button>
      </form>
    </div>
  )
}

function SampleTable({ op }: { op: BulkOperation }) {
  if (op.sample.length === 0) return null
  return (
    <Table>
      <caption className="sr-only">First matching requests</caption>
      <THead>
        <TR><TH>#</TH><TH>Request</TH></TR>
      </THead>
      <TBody>
        {op.sample.map((i) => (
          <TR key={i.runId}><TD>{i.position}</TD><TD>{i.reference}</TD></TR>
        ))}
      </TBody>
    </Table>
  )
}

function Progress({ ws, op }: { ws: string; op: BulkOperation }) {
  const control = useControlBulk(ws)
  const [showItems, setShowItems] = useState(false)
  const items = useBulkItems(ws, op.id, undefined, showItems)
  const p = op.progress
  const done = p.succeeded + p.failed + p.skipped + p.cancelled
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={STATE_TONE[op.state]}>{STATE_LABEL[op.state]}</Badge>
        <span>{BULK_ACTION_LABEL[op.action]} by {op.createdBy.displayName}: {op.reason}</span>
      </div>
      <progress className="w-full" max={Math.max(1, p.total)} value={done} aria-label="Bulk operation progress" />
      <p role="status">
        {done} of {p.total} handled: {p.succeeded} done, {p.failed} failed, {p.skipped} skipped, {p.cancelled} cancelled
      </p>
      {op.stopReason ? <p className="text-sm text-muted-foreground">{op.stopReason}</p> : null}
      {control.isError ? <ProblemAlert error={control.error} /> : null}
      <div className="flex flex-wrap gap-2">
        {op.state === 'running' ? <Button variant="secondary" loading={control.isPending} onClick={() => control.mutate({ id: op.id, action: 'pause', expectedVersion: op.version })}>Pause</Button> : null}
        {op.state === 'paused' ? <Button variant="secondary" loading={control.isPending} onClick={() => control.mutate({ id: op.id, action: 'resume', expectedVersion: op.version })}>Resume</Button> : null}
        {op.state === 'running' || op.state === 'paused' ? <Button variant="ghost" loading={control.isPending} onClick={() => control.mutate({ id: op.id, action: 'cancel', expectedVersion: op.version })}>Cancel remaining</Button> : null}
        <Button variant="link" onClick={() => setShowItems((v) => !v)}>{showItems ? 'Hide' : 'Show'} each request</Button>
      </div>
      {showItems && items.data ? (
        <Table>
          <caption className="sr-only">Result per request</caption>
          <THead>
            <TR><TH>Request</TH><TH>Result</TH><TH>Details</TH></TR>
          </THead>
          <TBody>
            {items.data.items.map((i) => (
              <TR key={i.runId}>
                <TD>{i.reference}</TD>
                <TD>{ITEM_STATE_LABEL[i.state]}</TD>
                <TD>{i.errorCode ? `${i.errorCode}${i.message ? `: ${i.message}` : ''}` : ''}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : null}
    </div>
  )
}
