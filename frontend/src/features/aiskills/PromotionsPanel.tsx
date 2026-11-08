import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useWorkspace } from '@/app/workspace'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'
import { AiProblem, useAction } from './AiProblem'
import { aiSkillKeys, AUTONOMY_LABEL, decideAiSkillPromotion, listAiSkillPromotions, nextAutonomy, percent, requestAiSkillPromotion, type AiSkill, type AiSkillPromotion, type AiSkillVersion } from './api'
import { ShadowSummary } from './ShadowPanel'

const STATUS_TONE: Record<AiSkillPromotion['status'], 'info' | 'success' | 'danger' | 'neutral'> = { pending: 'info', approved: 'success', rejected: 'danger', withdrawn: 'neutral' }
const STATUS_LABEL: Record<AiSkillPromotion['status'], string> = { pending: 'Waiting for a decision', approved: 'Approved', rejected: 'Rejected', withdrawn: 'Withdrawn' }

/**
 * Promotions of a skill: a designer (workflow.edit) asks to raise the autonomy ceiling of an approved version one step
 * (suggest, autoWithReview, auto) with the shadow evidence; a different person holding ai.approve-skill decides.
 */
export function PromotionsPanel({ skill, version }: { skill: AiSkill; version: AiSkillVersion }) {
  const { workspace, can, me } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const promotions = useQuery({ queryKey: aiSkillKeys.promotions(ws, skill.key), queryFn: () => listAiSkillPromotions(ws, skill.key) })
  const [requesting, setRequesting] = useState(false)
  const [deciding, setDeciding] = useState<{ promotion: AiSkillPromotion; decision: 'approve' | 'reject' } | null>(null)
  const next = nextAutonomy(version.autonomyCeiling)
  const pendingForVersion = promotions.data?.items.some((p) => p.status === 'pending' && p.version === version.version) ?? false
  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: aiSkillKeys.promotions(ws, skill.key) }),
      client.invalidateQueries({ queryKey: aiSkillKeys.one(ws, skill.key) }),
      client.invalidateQueries({ queryKey: aiSkillKeys.list(ws) }),
    ])
  }

  return (
    <section aria-label="Promotions" className="space-y-4">
      <p className="text-sm">
        Version {version.version} decides at most <strong>{AUTONOMY_LABEL[version.autonomyCeiling].toLowerCase()}</strong>.
        {next ? ` The next step is ${AUTONOMY_LABEL[next].toLowerCase()}.` : ' This is the highest ceiling.'}
      </p>
      {can('workflow.edit') && version.status === 'approved' && next ? (
        pendingForVersion
          ? <p className="text-sm text-muted-foreground">A promotion of this version is waiting for a decision.</p>
          : <Button variant="primary" onClick={() => setRequesting(true)}>Request promotion to {AUTONOMY_LABEL[next].toLowerCase()}</Button>
      ) : version.status !== 'approved' ? <p className="text-sm text-muted-foreground">Only an approved version can be promoted.</p> : null}

      {promotions.isPending ? <LoadingBlock rows={2} label="Loading promotions" />
        : promotions.isError ? <QueryError error={promotions.error} onRetry={() => void promotions.refetch()} what="the promotions" />
          : promotions.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No promotion of this skill yet.</p>
            : (
              <ol className="space-y-3" aria-label="Promotion history">
                {promotions.data.items.map((p) => {
                  const mine = p.requestedBy === me.subject.id
                  return (
                    <li key={p.id} className="space-y-2 rounded-card border border-border bg-surface p-4" aria-label={`Promotion of version ${p.version} to ${AUTONOMY_LABEL[p.toAutonomy]}`}>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold">Version {p.version}: {AUTONOMY_LABEL[p.fromAutonomy]} to {AUTONOMY_LABEL[p.toAutonomy]}</span>
                        <Badge tone={STATUS_TONE[p.status]}>{STATUS_LABEL[p.status]}</Badge>
                      </div>
                      <p className="text-sm">Requested {mine ? 'by you' : ''} on {formatDateTime(p.requestedAt)}: {p.reason}</p>
                      <p className="text-xs text-muted-foreground">
                        Evidence: {p.evidence.compared} compared cases (needs {p.evidence.minShadowCases}), agreement {percent(p.evidence.agreement)} (target {percent(p.evidence.targetAgreement)}).
                      </p>
                      {p.decidedAt ? <p className="text-sm">Decided {p.decidedBy === me.subject.id ? 'by you ' : ''}on {formatDateTime(p.decidedAt)}: {p.decisionReason}</p> : null}
                      {p.status === 'pending' && can('ai.approve-skill') ? (
                        mine ? (
                          <Alert tone="info" data-testid="promotion-four-eyes">You asked for this promotion, so someone else who may approve AI skills has to decide it (four eyes).</Alert>
                        ) : (
                          <div className="flex flex-wrap gap-2">
                            <Button size="sm" variant="primary" onClick={() => setDeciding({ promotion: p, decision: 'approve' })}>Approve promotion</Button>
                            <Button size="sm" variant="danger" onClick={() => setDeciding({ promotion: p, decision: 'reject' })}>Reject promotion</Button>
                          </div>
                        )
                      ) : null}
                    </li>
                  )
                })}
              </ol>
            )}

      {requesting && next ? <RequestDialog ws={ws} version={version} to={next} onCancel={() => setRequesting(false)} onDone={async () => { setRequesting(false); await refresh() }} /> : null}
      {deciding ? <DecideDialog ws={ws} {...deciding} onCancel={() => setDeciding(null)} onDone={async () => { setDeciding(null); await refresh() }} /> : null}
    </section>
  )
}

function RequestDialog({ ws, version, to, onCancel, onDone }: { ws: string; version: AiSkillVersion; to: 'autoWithReview' | 'auto'; onCancel: () => void; onDone: () => Promise<void> }) {
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const action = useAction()
  const confirm = () => {
    if (!reason.trim()) return setMissing(true)
    void action.run(() => requestAiSkillPromotion(ws, version.skillKey, version.version, { toAutonomy: to, reason: reason.trim() }), onDone)
  }
  return (
    <ConfirmDialog open title={`Request promotion to ${AUTONOMY_LABEL[to].toLowerCase()}`} confirmLabel="Request promotion" pending={action.pending} onConfirm={confirm} onCancel={onCancel}
      error={action.error ? <AiProblem error={action.error} action="request this promotion" /> : null}>
      <p>The current shadow figures are stored with the request. It needs enough compared cases at the target agreement, and someone else decides it.</p>
      <FormField label="Reason" required error={missing ? 'Say why this skill may decide with more autonomy.' : undefined}>
        {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={missing ? true : undefined} value={reason} maxLength={1000} onChange={(e) => { setReason(e.target.value); setMissing(false) }} />}
      </FormField>
    </ConfirmDialog>
  )
}

function DecideDialog({ ws, promotion, decision, onCancel, onDone }: { ws: string; promotion: AiSkillPromotion; decision: 'approve' | 'reject'; onCancel: () => void; onDone: () => Promise<void> }) {
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const action = useAction()
  const confirm = () => {
    if (!reason.trim()) return setMissing(true)
    void action.run(() => decideAiSkillPromotion(ws, promotion.id, { decision, reason: reason.trim() }), onDone)
  }
  return (
    <ConfirmDialog open title={`${decision === 'approve' ? 'Approve' : 'Reject'} promotion to ${AUTONOMY_LABEL[promotion.toAutonomy].toLowerCase()}?`}
      confirmLabel={decision === 'approve' ? 'Approve' : 'Reject'} destructive={decision === 'reject'} pending={action.pending} onConfirm={confirm} onCancel={onCancel}
      error={action.error ? <AiProblem error={action.error} action="decide this promotion" /> : null}>
      <ShadowSummary d={promotion.evidence} />
      <FormField label="Reason" required error={missing ? 'Give a reason for the decision.' : undefined}>
        {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={missing ? true : undefined} value={reason} maxLength={1000} onChange={(e) => { setReason(e.target.value); setMissing(false) }} />}
      </FormField>
    </ConfirmDialog>
  )
}
