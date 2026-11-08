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
import { formatUsd } from '@/features/ai/api'
import { AiProblem, useAction } from './AiProblem'
import { aiSkillKeys, AUTONOMY_LABEL, checkAiSkillDrift, clearAiSkillDrift, getAiShadowDashboard, percent, type AiDriftCheck, type AiShadowDashboard, type AiSkillVersion } from './api'
import { Stat, SubTable } from './EvaluationPanel'

/**
 * Shadow dashboard of one version: how often the AI agreed with the people who decided, the confusion between the AI
 * value and the human value, per-label stats, cost, latency, the promotion thresholds, the autonomy ceiling and the
 * drift state; plus the drift check (ai.evaluate) and clearing a drift fallback (ai.approve-skill, with a reason).
 */
export function ShadowPanel({ version }: { version: AiSkillVersion }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const dashboard = useQuery({ queryKey: aiSkillKeys.shadow(ws, version.skillKey, version.version), queryFn: () => getAiShadowDashboard(ws, version.skillKey, version.version) })
  const drift = useAction()
  const [check, setCheck] = useState<AiDriftCheck | null>(null)
  const [clearing, setClearing] = useState(false)
  const refresh = () => client.invalidateQueries({ queryKey: aiSkillKeys.one(ws, version.skillKey) })

  if (dashboard.isPending) return <LoadingBlock rows={3} label="Loading the shadow dashboard" />
  if (dashboard.isError) return <QueryError error={dashboard.error} onRetry={() => void dashboard.refetch()} what="the shadow dashboard" />
  const d = dashboard.data
  return (
    <section aria-label="Shadow dashboard" className="space-y-5" data-testid="shadow-dashboard">
      <ShadowSummary d={d} />
      <section aria-label="Drift" className="space-y-3 rounded-card border border-border bg-surface p-4">
        <h3 className="text-base font-semibold">Drift</h3>
        <p className="text-sm text-muted-foreground">
          Every Monday, agreement over the last 7 days is compared with the agreement of the latest approved promotion. A drop beyond the
          skill's limit with at least 20 compared cases makes the version suggest only, until someone who may approve AI skills clears it.
        </p>
        {d.driftFallback ? (
          <Alert tone="warning" title="Drift fallback is on">
            AI tasks of this version only suggest.{version.driftReason ? ` ${version.driftReason}` : ''}
          </Alert>
        ) : <p className="text-sm">No drift fallback.</p>}
        <div className="flex flex-wrap gap-2">
          {can('ai.evaluate') && version.status === 'approved' ? (
            <Button variant="secondary" loading={drift.pending} onClick={() => void drift.run(() => checkAiSkillDrift(ws, version.skillKey, version.version), async (r) => {
              setCheck(r)
              await Promise.all([refresh(), dashboard.refetch()])
            })}>Run drift check now</Button>
          ) : null}
          {can('ai.approve-skill') && d.driftFallback ? <Button variant="primary" onClick={() => setClearing(true)}>Clear drift</Button> : null}
        </div>
        {drift.error ? <AiProblem error={drift.error} action="run the drift check" /> : null}
        {check ? (
          <Alert tone={check.drifted ? 'warning' : 'success'} data-testid="drift-result">
            {check.cases} compared cases in the last 7 days, agreement {percent(check.agreement)} against a baseline of {percent(check.baseline)} (largest
            allowed drop {percent(check.maxAgreementDrop)}). {check.drifted ? (check.fellBackToSuggest ? 'The version now suggests only.' : 'Agreement drifted.') : 'No drift.'}
          </Alert>
        ) : null}
      </section>
      {clearing ? <ClearDriftDialog version={version} onCancel={() => setClearing(false)} onDone={async () => { setClearing(false); await Promise.all([refresh(), dashboard.refetch()]) }} /> : null}
    </section>
  )
}

/** The figures of a shadow dashboard (also the evidence stored with a promotion request). */
export function ShadowSummary({ d }: { d: AiShadowDashboard }) {
  const keys = [...new Set([...Object.keys(d.confusion), ...Object.values(d.confusion).flatMap((row) => Object.keys(row))])]
  const enoughCases = d.compared >= d.minShadowCases
  const enoughAgreement = d.agreement >= d.targetAgreement
  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="AI decisions" value={String(d.decisions)} />
        <Stat label="In shadow mode" value={String(d.shadowDecisions)} />
        <Stat label="Compared with a person" value={`${d.compared} (${d.agreed} agreed)`} />
        <Stat label="Agreement" value={percent(d.agreement)} />
        <Stat label="Cost per case" value={formatUsd(d.costPerCaseUsd)} />
        <Stat label="95th percentile latency" value={`${d.p95LatencyMs} ms`} />
        <Stat label="Autonomy ceiling" value={AUTONOMY_LABEL[d.autonomyCeiling]} />
        <Stat label="Drift" value={d.driftFallback ? 'Suggests only' : 'None'} />
      </dl>
      <div className="flex flex-wrap gap-2 text-sm">
        <Badge tone={enoughCases ? 'success' : 'neutral'}>{d.compared} of {d.minShadowCases} compared cases needed for promotion</Badge>
        <Badge tone={enoughAgreement ? 'success' : 'neutral'}>Agreement {percent(d.agreement)}, target {percent(d.targetAgreement)}</Badge>
      </div>
      {Object.keys(d.statuses).length > 0 ? (
        <SubTable caption="Statuses" head={['Status', 'Decisions']} rows={Object.entries(d.statuses).map(([s, n]) => [s, String(n)])} />
      ) : null}
      {keys.length > 0 ? (
        <div className="overflow-x-auto">
          <table className="text-sm" aria-label="Shadow confusion matrix">
            <caption className="mb-1 text-left font-semibold">Confusion (rows: AI value, columns: what the person decided)</caption>
            <thead><tr><th scope="col" className="p-2 text-left">AI value</th>{keys.map((k) => <th key={k} scope="col" className="p-2 font-mono text-xs">{k}</th>)}</tr></thead>
            <tbody>
              {keys.filter((k) => d.confusion[k]).map((ai) => (
                <tr key={ai}>
                  <th scope="row" className="p-2 text-left font-mono text-xs">{ai}</th>
                  {keys.map((h) => <td key={h} className={`p-2 text-center ${ai === h ? 'font-semibold' : ''}`}>{d.confusion[ai]?.[h] ?? 0}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <p className="text-sm text-muted-foreground">No AI decision of this version has a recorded human outcome yet.</p>}
      {Object.keys(d.perLabel).length > 0 ? (
        <SubTable caption="Per label" head={['Label', 'Precision', 'Recall', 'Support']}
          rows={Object.entries(d.perLabel).map(([l, s]) => [l, percent(s.precision), percent(s.recall), String(s.support)])} />
      ) : null}
    </div>
  )
}

function ClearDriftDialog({ version, onCancel, onDone }: { version: AiSkillVersion; onCancel: () => void; onDone: () => Promise<void> }) {
  const { workspace } = useWorkspace()
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const action = useAction()
  const confirm = () => {
    if (!reason.trim()) return setMissing(true)
    void action.run(() => clearAiSkillDrift(workspace.id, version.skillKey, version.version, reason.trim()), onDone)
  }
  return (
    <ConfirmDialog open title={`Clear drift of version ${version.version}?`} confirmLabel="Clear drift" pending={action.pending} onConfirm={confirm} onCancel={onCancel}
      error={action.error ? <AiProblem error={action.error} action="clear the drift" /> : null}>
      <p>AI tasks of this version decide again up to the autonomy ceiling.</p>
      <FormField label="Reason" required error={missing ? 'Say why the skill may decide automatically again.' : undefined}>
        {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={missing ? true : undefined} value={reason} maxLength={1000} onChange={(e) => { setReason(e.target.value); setMissing(false) }} />}
      </FormField>
    </ConfirmDialog>
  )
}
