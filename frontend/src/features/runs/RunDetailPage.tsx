import { AlertTriangle, Ban, CheckCircle2, Clock, RefreshCw, Wrench, XCircle } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { asRecord, type Effect, type RunDetail, type RunEvent } from '@/api/types'
import { useRelease, useRun, useRunEvents } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { StatusBadge } from '@/components/common/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { dueInfo, formatDateTime, formatTime } from '@/lib/dates'
import { cn } from '@/lib/cn'
import { attentionNextStep, describeEvent, eventTone, timelineEvents, type EventContext, type EventTone } from '@/lib/events'
import { displayJsonValue } from '@/lib/form/display'
import { orderedRequestRows } from '@/lib/form/rows'
import { buildFields, parseFormSchema, parseFormUi, type FieldDef } from '@/lib/form/schema'
import { ownReference, requestId, stepOwnerText } from '@/lib/runText'
import { humanize } from '@/lib/text'
import { isTerminal } from '@/lib/status'
import { CancelDialog, ReassignDialog, ReconcileDialog, RetryDialog } from './OperatorDialogs'
import { candidateEffects, EFFECT_CLASS_LABEL, EFFECT_STATE_LABEL, reasonText } from './operatorText'

const TONE_DOT: Record<EventTone, string> = {
  neutral: 'bg-muted-foreground',
  waiting: 'bg-status-waiting-fg',
  success: 'bg-success',
  rejected: 'bg-status-rejected-fg',
  cancelled: 'bg-status-cancelled-fg',
  danger: 'bg-danger',
}

type DialogName = 'retry' | 'reconcile' | 'cancel' | 'reassign' | null

/**
 * Run detail for requesters, approvers and operators (docs/build/S7-frontend.md section 4).
 * Sections appear according to what the API returns: request data only for people allowed to see it,
 * technical details and operator actions only for operators.
 */
export function RunDetailPage() {
  const { runId = '' } = useParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const run = useRun(ws, runId)
  const events = useRunEvents(ws, runId, run.data?.lifecycle)
  const release = useRelease(ws, run.data?.workflow.id ?? '', can('workflow.read') ? run.data?.release.id : null)

  if (run.isPending) return <LoadingBlock rows={5} label="Loading the request" />
  if (run.isError) return <QueryError error={run.error} onRetry={() => void run.refetch()} what="this request" backTo={{ to: `/w/${ws}/requests`, label: 'Back to My requests' }} />
  return <RunView run={run.data} events={events.data?.events ?? []} eventsUpdatedAt={events.dataUpdatedAt} releaseDefinition={release.data?.definition} />
}

function RunView({ run, events, eventsUpdatedAt, releaseDefinition }: { run: RunDetail; events: RunEvent[]; eventsUpdatedAt: number; releaseDefinition: { inputSchema: unknown; inputUi?: unknown } | undefined }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const [dialog, setDialog] = useState<DialogName>(null)
  const [reconcileId, setReconcileId] = useState<string | undefined>()
  const operator = can('run.operate') || run.effects !== undefined
  const needsAttention = run.lifecycle === 'needsAttention'

  const fields = useMemo<FieldDef[]>(
    () => (releaseDefinition ? buildFields(parseFormSchema(releaseDefinition.inputSchema), parseFormUi(releaseDefinition.inputUi)) : []),
    [releaseDefinition],
  )

  const ctx = useMemo<EventContext>(() => {
    const labels: Record<string, string> = {}
    const groupNames: Record<string, string> = {}
    for (const t of run.tasks) {
      labels[t.nodeId] = t.label
      groupNames[t.candidateGroup.key] = t.candidateGroup.name
    }
    for (const s of run.currentSteps) labels[s.nodeId] = s.label
    for (const e of run.effects ?? []) if (e.label) labels[e.nodeId] = e.label
    for (const e of events) {
      const label = e.nodeLabel ?? (typeof e.data.label === 'string' ? e.data.label : undefined)
      if (e.nodeId && label) labels[e.nodeId] = label
    }
    return { initiatorName: run.initiator.displayName, labels, groupNames }
  }, [run, events])

  const input = run.input ? asRecord(run.input) : null
  const requestRows = input ? orderedRequestRows(input, fields, asRecord(run.inputLabels) as Record<string, string>) : []

  const decisions = run.tasks.filter((t) => t.decision).sort((a, b) => (a.decision?.decidedAt ?? '').localeCompare(b.decision?.decidedAt ?? ''))
  const openTasks = run.tasks.filter((t) => t.status === 'open')
  const allowed = run.allowedActions
  const effects = run.effects ?? []
  const reconcileCandidates = candidateEffects(effects)
  const notReversed = run.completedEffectsNotReversed ?? []

  return (
    <>
      <PageHeader
        title={run.workflow.name}
        description={
          <>
            Request <span className="font-semibold tabular-nums" data-testid="run-reference">{requestId(run)}</span>
            {ownReference(run) ? <> (your reference {ownReference(run)})</> : null} &middot; Version {run.release.releaseNumber} &middot; submitted by {run.initiator.displayName} on {formatDateTime(run.createdAt)}
          </>
        }
        actions={
          <Button asChild variant="secondary">
            <Link to={operator ? `/w/${ws}/runs` : `/w/${ws}/requests`}>{operator ? 'All runs' : 'My requests'}</Link>
          </Button>
        }
      />

      <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-2" data-testid="run-status">
        <StatusBadge lifecycle={run.lifecycle} outcome={run.outcome} outcomeKind={run.outcomeKind} className="px-3 py-1 text-sm" />
        {run.currentSteps.map((s) => {
          const due = dueInfo(s.dueAt)
          return (
            <span key={s.nodeId} className="text-sm">
              {stepOwnerText(s)}
              {due ? <span className={due.overdue ? 'ml-2 font-semibold text-danger' : 'ml-2 text-muted-foreground'}>{due.label}</span> : null}
            </span>
          )
        })}
        {!isTerminal(run.lifecycle) ? (
          <span className="ml-auto inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <RefreshCw className="size-3.5" aria-hidden="true" /> Updates automatically &middot; last checked {eventsUpdatedAt ? formatTime(new Date(eventsUpdatedAt)) : 'just now'}
          </span>
        ) : null}
      </div>

      {needsAttention && run.attention ? (
        <Alert tone="danger" title="This request needs attention" className="mb-6" live="polite">
          {operator ? (
            <>
              <p data-testid="attention-message">{run.attention.message}</p>
              <p className="mt-1">{attentionNextStep(run.attention.reason)}</p>
            </>
          ) : (
            <p>Your request is delayed. An operator has been alerted and will look at it.</p>
          )}
        </Alert>
      ) : null}

      {run.lifecycle === 'cancelled' ? (
        <Alert tone="warning" title="This request was cancelled" className="mb-6">
          {notReversed.length > 0 ? (
            <>
              These steps had already happened and were <strong>not</strong> undone:
              <ul className="mt-1 list-disc pl-5">
                {notReversed.map((e) => (
                  <li key={e.id}>
                    {e.label ?? e.nodeId}
                    {e.remoteReference ? ` (reference ${e.remoteReference})` : ''}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            'No step in another system had completed.'
          )}
        </Alert>
      ) : null}

      {operator ? (
        <section aria-labelledby="operator-heading" className="mb-6 rounded-card border border-border bg-surface p-4">
          <h2 id="operator-heading" className="mb-3 flex items-center gap-2 text-base">
            <Wrench className="size-4" aria-hidden="true" /> Operator actions
          </h2>
          <div className="flex flex-wrap gap-3">
            <Button variant="secondary" onClick={() => setDialog('retry')} disabled={!allowed.retry.allowed}>
              Retry step
            </Button>
            <Button variant="secondary" onClick={() => { setReconcileId(undefined); setDialog('reconcile') }} disabled={!allowed.reconcile.allowed}>
              Reconcile outcome
            </Button>
            <Button variant="secondary" onClick={() => setDialog('reassign')} disabled={openTasks.length === 0}>
              Reassign task
            </Button>
            <Button variant="danger" onClick={() => setDialog('cancel')} disabled={!allowed.cancel.allowed}>
              <Ban aria-hidden="true" /> Cancel request
            </Button>
          </div>
          <ul className="mt-3 space-y-1 text-sm text-muted-foreground">
            {!allowed.retry.allowed && needsAttention ? <li>Retry: {reasonText(allowed.retry.reasonCode)}</li> : null}
            {!allowed.cancel.allowed ? <li>Cancel: {reasonText(allowed.cancel.reasonCode)}</li> : null}
          </ul>
        </section>
      ) : null}

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_26rem]">
        <div className="min-w-0 space-y-6">
          <Card>
            <CardHeader>
              <h2>Request</h2>
            </CardHeader>
            <CardContent>
              {input === null ? (
                <p className="text-sm text-muted-foreground">You can see where this request stands, but not what was requested.</p>
              ) : (
                <dl className="divide-y divide-border rounded-card border border-border" data-testid="request-values">
                  {requestRows.map((row) => (
                    <div key={row.key} className="grid gap-1 px-4 py-2.5 sm:grid-cols-[12rem_1fr]">
                      <dt className="text-sm font-semibold text-muted-foreground">{row.label}</dt>
                      <dd className="break-words whitespace-pre-wrap">{row.value}</dd>
                    </div>
                  ))}
                </dl>
              )}
              {run.output && Object.keys(asRecord(run.output)).length > 0 ? (
                <div className="mt-4">
                  <h3 className="mb-1 text-sm text-muted-foreground uppercase">Result</h3>
                  <dl className="divide-y divide-border rounded-card border border-border">
                    {Object.entries(asRecord(run.output)).map(([k, v]) => (
                      <div key={k} className="grid gap-1 px-4 py-2.5 sm:grid-cols-[12rem_1fr]">
                        <dt className="text-sm font-semibold text-muted-foreground">{humanize(k)}</dt>
                        <dd>{displayJsonValue(undefined, v)}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <h2>Decisions</h2>
            </CardHeader>
            <CardContent>
              {decisions.length === 0 && openTasks.length === 0 ? (
                <p className="text-sm text-muted-foreground">No one has been asked to decide yet.</p>
              ) : (
                <ul className="space-y-2">
                  {decisions.map((t) => {
                    const d = t.decision!
                    return (
                      <li key={t.id} className="rounded-control border border-border px-4 py-3 text-sm">
                        <p className="flex items-center gap-1.5 font-semibold">
                          {d.action === 'approve' ? <CheckCircle2 className="size-4 text-success" aria-hidden="true" /> : <XCircle className="size-4 text-status-rejected-fg" aria-hidden="true" />}
                          {t.label}: {d.action === 'approve' ? 'Approved' : 'Rejected'} by {d.actor.displayName}
                        </p>
                        <p className="text-xs text-muted-foreground">{formatDateTime(d.decidedAt)}</p>
                        {d.reason ? <p className="mt-1 whitespace-pre-wrap">Reason: {d.reason}</p> : null}
                        {d.comment ? <p className="mt-1 whitespace-pre-wrap">Comment: {d.comment}</p> : null}
                        {d.policyFlags.length > 0 ? <p className="mt-1 text-xs text-warning-foreground">Policy: {d.policyFlags.map(humanize).join(', ')}</p> : null}
                      </li>
                    )
                  })}
                  {openTasks.map((t) => (
                    <li key={t.id} className="flex items-center gap-1.5 rounded-control border border-dashed border-input-border px-4 py-3 text-sm">
                      <Clock className="size-4 text-status-waiting-fg" aria-hidden="true" />
                      {t.label}: waiting for {t.assignee ? t.assignee.displayName : t.candidateGroup.name}
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>

          {operator ? <TechnicalDetails run={run} effects={effects} onReconcile={(id) => { setReconcileId(id); setDialog('reconcile') }} /> : null}
        </div>

        <aside aria-labelledby="timeline-heading">
          <Card className="xl:sticky xl:top-20">
            <CardHeader>
              <h2 id="timeline-heading">Timeline</h2>
            </CardHeader>
            <CardContent>
              {events.length === 0 ? (
                <p className="text-sm text-muted-foreground">Nothing has happened yet.</p>
              ) : (
                <ol className="relative space-y-4 border-l-2 border-border pl-5" data-testid="timeline">
                  {timelineEvents(events).map((e) => (
                    <li key={e.seq} className="relative">
                      <span className={cn('absolute top-1.5 -left-[1.6rem] size-2.5 rounded-full border-2 border-surface', TONE_DOT[eventTone(e)])} aria-hidden="true" />
                      <p className="text-sm">{describeEvent(e, ctx)}</p>
                      <p className="text-xs text-muted-foreground">
                        <time dateTime={e.occurredAt}>{formatDateTime(e.occurredAt)}</time>
                      </p>
                    </li>
                  ))}
                </ol>
              )}
            </CardContent>
          </Card>
        </aside>
      </div>

      {dialog === 'retry' ? <RetryDialog open run={run} onClose={() => setDialog(null)} onNeedReconcile={() => { setDialog('reconcile') }} /> : null}
      {dialog === 'reconcile' ? <ReconcileDialog open run={run} onClose={() => setDialog(null)} initialEffectId={reconcileId ?? reconcileCandidates[0]?.id} /> : null}
      {dialog === 'cancel' ? <CancelDialog open run={run} onClose={() => setDialog(null)} /> : null}
      {dialog === 'reassign' ? <ReassignDialog open run={run} onClose={() => setDialog(null)} /> : null}
    </>
  )
}

function TechnicalDetails({ run, effects, onReconcile }: { run: RunDetail; effects: Effect[]; onReconcile: (effectId: string) => void }) {
  return (
    <Card>
      <details open={run.lifecycle === 'needsAttention'}>
        <summary className="flex cursor-pointer items-center gap-2 p-5 text-lg font-semibold">
          <AlertTriangle className="size-4 text-muted-foreground" aria-hidden="true" /> Technical details
        </summary>
        <CardContent>
          <dl className="mb-5 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[10rem_1fr]">
            <dt className="font-semibold text-muted-foreground">Run ID</dt>
            <dd className="font-mono break-all">{run.id}</dd>
            <dt className="font-semibold text-muted-foreground">Workflow version</dt>
            <dd>
              {run.release.releaseNumber} <span className="font-mono text-xs text-muted-foreground">({run.release.id})</span>
            </dd>
            <dt className="font-semibold text-muted-foreground">Lifecycle</dt>
            <dd className="font-mono">{run.lifecycle}</dd>
            <dt className="font-semibold text-muted-foreground">State version</dt>
            <dd className="font-mono">{run.version}</dd>
            {run.attention ? (
              <>
                <dt className="font-semibold text-muted-foreground">Attention reason</dt>
                <dd className="font-mono">{run.attention.reason}</dd>
              </>
            ) : null}
          </dl>
          <h3 className="mb-2 text-sm text-muted-foreground uppercase">Steps in other systems</h3>
          {effects.length === 0 ? (
            <p className="text-sm text-muted-foreground">This request has not called another system.</p>
          ) : (
            <div className="overflow-hidden rounded-card border border-border">
              <Table>
                <caption className="sr-only">Calls to other systems</caption>
                <THead>
                  <TR>
                    <TH>Step</TH>
                    <TH>State</TH>
                    <TH>Attempts</TH>
                    <TH>Last problem</TH>
                    <TH>Reference</TH>
                    <TH>
                      <span className="sr-only">Actions</span>
                    </TH>
                  </TR>
                </THead>
                <TBody>
                  {effects.map((e) => (
                    <TR key={e.id}>
                      <TD className="min-w-56">
                        <span className="font-semibold">{e.label ?? e.nodeId}</span>
                        <span className="block text-xs text-muted-foreground">
                          {e.connectionKey} &middot; {e.operation}
                        </span>
                        <span className="block text-xs text-muted-foreground">{EFFECT_CLASS_LABEL[e.effectClass]}</span>
                      </TD>
                      <TD className="min-w-44">
                        <span className={e.state === 'unknown' ? 'font-semibold text-status-attention-fg' : ''} data-effect-state={e.state}>
                          {e.state === 'unknown' ? <Ban className="mr-1 inline size-3.5" aria-hidden="true" /> : null}
                          {EFFECT_STATE_LABEL[e.state]}
                        </span>
                      </TD>
                      <TD>{e.attempts}</TD>
                      <TD className="max-w-60 text-xs">
                        {e.lastErrorCode ? <span className="font-mono">{e.lastErrorCode}</span> : '—'}
                        {e.lastHttpStatus ? <span className="block text-muted-foreground">HTTP {e.lastHttpStatus}</span> : null}
                        {e.lastErrorDetail ? <span className="block text-muted-foreground">{e.lastErrorDetail}</span> : null}
                      </TD>
                      <TD className="font-mono text-xs">{e.remoteReference ?? '—'}</TD>
                      <TD>
                        {e.state === 'unknown' || e.state === 'permanentFailure' || e.state === 'retryableFailure' ? (
                          <Button size="sm" variant="secondary" onClick={() => onReconcile(e.id)} aria-label={`Reconcile ${e.label ?? e.nodeId}`}>
                            Reconcile
                          </Button>
                        ) : null}
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            </div>
          )}
        </CardContent>
      </details>
    </Card>
  )
}
