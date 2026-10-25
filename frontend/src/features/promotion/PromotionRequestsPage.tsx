import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { keys, usePromotionRequests, useWorkspaceEnvironment } from '@/api/queries'
import type { PromotionRequest } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { STAGES, stageLabel } from './stages'

const STATUS_TONE = { pending: 'warning', approved: 'success', rejected: 'danger', cancelled: 'neutral' } as const

/**
 * Promotion approvals for this workspace (PL02, docs/build/S74): the stage and the approval requirement, and the
 * requests other people made to promote a release into it. A request is decided by a different person who holds the
 * explicit approval permission here; the person who asked can only withdraw it.
 */
export function PromotionRequestsPage() {
  const { workspace, me, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const [showAll, setShowAll] = useState(false)
  const allowed = can('workflow.read', 'workflow.edit', 'workflow.approve-promotion')
  const requests = usePromotionRequests(ws, showAll ? 'all' : 'pending', allowed)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState<PromotionRequest | null>(null)
  const [reason, setReason] = useState('')
  const [reasonMissing, setReasonMissing] = useState(false)

  if (!allowed && !can('workspace.manage-access')) {
    return <Alert tone="warning">You need permission to view promotion requests in this workspace.</Alert>
  }

  const refresh = async () => {
    await Promise.all([client.invalidateQueries({ queryKey: ['ws', ws, 'promotion-requests'] }), client.invalidateQueries({ queryKey: keys.environment(ws) })])
  }

  const act = async (request: PromotionRequest, action: () => Promise<unknown>) => {
    setBusy(request.id)
    setError(null)
    try {
      await action()
      setRejecting(null)
      setReason('')
      await refresh()
    } catch (cause) {
      setError(cause)
      void refresh()
    } finally {
      setBusy(null)
    }
  }

  const reject = () => {
    if (!rejecting) return
    if (!reason.trim()) {
      setReasonMissing(true)
      return
    }
    void act(rejecting, () => endpoints.rejectPromotionRequest(ws, rejecting.id, { note: reason.trim() }))
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Promotions" description="Which stage this workspace is in, and requests to promote a release into it." />

      <EnvironmentPanel ws={ws} manage={can('workspace.manage-access')} refresh={refresh} />

      {error ? <ProblemAlert error={toProblem(error)} context={{ action: 'decide this request' }} /> : null}

      {allowed ? (
        <section aria-labelledby="requests-heading">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h2 id="requests-heading">Requests</h2>
            <div className="flex items-center gap-2">
              <Checkbox id="show-decided" checked={showAll} onCheckedChange={(v) => setShowAll(v === true)} />
              <Label htmlFor="show-decided">Show decided requests</Label>
            </div>
          </div>
          {requests.isPending ? (
            <LoadingBlock rows={2} label="Loading promotion requests" />
          ) : requests.isError ? (
            <QueryError error={requests.error} onRetry={() => void requests.refetch()} what="the promotion requests" />
          ) : requests.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">{showAll ? 'Nobody has asked to promote a release into this workspace.' : 'No request is waiting for approval.'}</p>
          ) : (
            <div className="overflow-hidden rounded-card border border-border bg-surface">
              <Table>
                <caption className="sr-only">Promotion requests</caption>
                <THead>
                  <TR>
                    <TH>Release</TH>
                    <TH>Requested</TH>
                    <TH>Status</TH>
                    <TH>
                      <span className="sr-only">Actions</span>
                    </TH>
                  </TR>
                </THead>
                <TBody>
                  {requests.data.items.map((r) => {
                    const mine = r.requestedById === me.subject.id
                    return (
                      <TR key={r.id}>
                        <TD>
                          <span className="font-semibold">{r.workflowKey}</span> version {r.releaseNumber}
                          <span className="block text-xs text-muted-foreground">from {r.sourceWorkspaceKey}</span>
                          {r.note ? <span className="block text-xs text-muted-foreground">&ldquo;{r.note}&rdquo;</span> : null}
                        </TD>
                        <TD>
                          {r.requestedBy}
                          <span className="block text-xs text-muted-foreground">{formatDateTime(r.requestedAt)}</span>
                        </TD>
                        <TD>
                          <Badge tone={STATUS_TONE[r.status]}>{r.status === 'pending' ? 'Waiting' : r.status[0]!.toUpperCase() + r.status.slice(1)}</Badge>
                          {r.decidedBy && r.status !== 'cancelled' ? (
                            <span className="block text-xs text-muted-foreground">
                              by {r.decidedBy}
                              {r.decisionNote ? `: ${r.decisionNote}` : ''}
                            </span>
                          ) : null}
                        </TD>
                        <TD>
                          <div className="flex flex-wrap justify-end gap-2">
                            {r.status === 'pending' && can('workflow.approve-promotion') && !mine ? (
                              <>
                                <Button size="sm" variant="primary" loading={busy === r.id} disabled={busy !== null} onClick={() => void act(r, () => endpoints.approvePromotionRequest(ws, r.id, {}))} aria-label={`Approve ${r.workflowKey} version ${r.releaseNumber}`}>
                                  Approve
                                </Button>
                                <Button size="sm" variant="secondary" disabled={busy !== null} onClick={() => { setError(null); setReasonMissing(false); setRejecting(r) }} aria-label={`Reject ${r.workflowKey} version ${r.releaseNumber}`}>
                                  Reject
                                </Button>
                              </>
                            ) : null}
                            {r.status === 'pending' && mine ? (
                              <>
                                {can('workflow.approve-promotion') ? <span className="self-center text-xs text-muted-foreground">You asked for this, so someone else must decide.</span> : null}
                                <Button size="sm" variant="secondary" loading={busy === r.id} disabled={busy !== null} onClick={() => void act(r, () => endpoints.cancelPromotionRequest(ws, r.id))} aria-label={`Withdraw ${r.workflowKey} version ${r.releaseNumber}`}>
                                  Withdraw
                                </Button>
                              </>
                            ) : null}
                            {r.status === 'approved' && r.promotionId ? (
                              <Link to={`/w/${ws}/workflows?q=${encodeURIComponent(r.workflowKey)}`}>Open the draft</Link>
                            ) : null}
                          </div>
                        </TD>
                      </TR>
                    )
                  })}
                </TBody>
              </Table>
            </div>
          )}
        </section>
      ) : null}

      <ConfirmDialog
        open={rejecting !== null}
        title={rejecting ? `Reject ${rejecting.workflowKey} version ${rejecting.releaseNumber}?` : 'Reject'}
        confirmLabel="Reject request"
        destructive
        pending={busy !== null}
        onConfirm={reject}
        onCancel={() => setRejecting(null)}
        error={error && rejecting ? <ProblemAlert error={toProblem(error)} context={{ action: 'reject this request' }} /> : null}
      >
        <Label htmlFor="reject-reason">Reason</Label>
        <Textarea id="reject-reason" value={reason} maxLength={500} onChange={(e) => { setReason(e.target.value); setReasonMissing(false) }} aria-invalid={reasonMissing} />
        {reasonMissing ? <p className="text-sm text-danger-foreground">Say why the promotion is rejected.</p> : null}
      </ConfirmDialog>
    </div>
  )
}

function EnvironmentPanel({ ws, manage, refresh }: { ws: string; manage: boolean; refresh: () => Promise<void> }) {
  const env = useWorkspaceEnvironment(ws)
  const [stage, setStage] = useState<string | null>(null)
  const [gate, setGate] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<unknown>(null)
  if (env.isPending) return <LoadingBlock rows={1} label="Loading the stage" />
  if (env.isError) return <QueryError error={env.error} onRetry={() => void env.refetch()} what="the stage" />
  const current = env.data
  const chosenStage = stage ?? current.stage
  const chosenGate = chosenStage === 'production' ? (gate ?? current.requireApproval) : false
  const dirty = chosenStage !== current.stage || chosenGate !== current.requireApproval
  const save = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      await endpoints.setWorkspaceEnvironment(ws, { stage: chosenStage as (typeof STAGES)[number], expectedVersion: current.version, requireApproval: chosenGate })
      setStage(null)
      setGate(null)
      await refresh()
    } catch (cause) {
      setSaveError(cause)
      void refresh()
    } finally {
      setSaving(false)
    }
  }
  return (
    <section aria-labelledby="stage-heading" className="rounded-card border border-border bg-surface p-5">
      <h2 id="stage-heading" className="mb-2">
        Stage
      </h2>
      <p className="mb-3 text-sm" data-testid="stage-summary">
        This workspace is <strong>{stageLabel(current.stage)}</strong>
        {current.explicit ? '' : ' (taken from the test flag; no stage was set)'}.{' '}
        {current.requireApproval ? 'A promotion into it needs a second person to approve it.' : 'Promotions into it are saved as drafts without approval.'}
      </p>
      {saveError ? <ProblemAlert error={toProblem(saveError)} className="mb-3" context={{ action: 'change the stage' }} /> : null}
      {manage ? (
        <div className="flex flex-wrap items-end gap-4">
          <div className="space-y-1.5">
            <Label htmlFor="env-stage">Stage</Label>
            <Select id="env-stage" value={chosenStage} onChange={(e) => setStage(e.target.value)} className="w-48">
              {STAGES.map((s) => (
                <option key={s} value={s}>
                  {stageLabel(s)}
                </option>
              ))}
            </Select>
          </div>
          <div className="flex items-center gap-2 pb-2">
            <Checkbox id="env-gate" checked={chosenGate} disabled={chosenStage !== 'production'} onCheckedChange={(v) => setGate(v === true)} />
            <Label htmlFor="env-gate">Require approval before a promotion into this workspace</Label>
          </div>
          <Button variant="primary" loading={saving} disabled={!dirty} onClick={() => void save()}>
            Save stage
          </Button>
        </div>
      ) : null}
      {manage && chosenStage !== 'production' ? <p className="mt-2 text-xs text-muted-foreground">Approval can only be required for a production workspace.</p> : null}
    </section>
  )
}
