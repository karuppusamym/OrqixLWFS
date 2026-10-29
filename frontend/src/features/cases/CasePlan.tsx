import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Alert } from '@/components/ui/alert'
import { Dialog } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/input'
import { changeCasePlanItem, getCasePlan, type PlanItemAction } from './api'

const ITEM_LABELS: Record<string, string> = { usereventlistener: 'User event', humantask: 'Human task', stage: 'Stage', milestone: 'Milestone', processTask: 'Workflow task', discretionaryItem: 'Discretionary item' }
const STATE_LABELS: Record<string, string> = { planned: 'Planned', notPlanned: 'Not planned' }
const WORKFLOW_LABELS: Record<string, string> = { running: 'Workflow running', completed: 'Workflow finished', failed: 'Workflow could not start', cancelled: 'Workflow cancelled', fenced: 'Late result not applied' }
const ACTION_LABELS: Record<PlanItemAction, string> = { occur: 'Trigger event', plan: 'Plan into case', start: 'Start task', enable: 'Enable task', disable: 'Disable task', activateStage: 'Activate stage', completeStage: 'Complete stage', retryWorkflow: 'Retry workflow', terminate: 'Cancel plan item' }
const HISTORY_LABELS: Record<string, string> = { dataUpdated: 'Case data updated', started: 'Case started', taskCompleted: 'Task completed', suspend: 'Case put on hold', resume: 'Case resumed', close: 'Case closed', terminated: 'Case terminated', workflowStarted: 'Workflow started', workflowNotStarted: 'Workflow could not start', workflowCompleted: 'Workflow finished', workflowCancelled: 'Workflow cancelled', workflowResultFenced: 'Late workflow result not applied', workflowResultRejected: 'Workflow result did not fit the case data', ...ACTION_LABELS }

export function CasePlan({ ws, instanceId, version, busy, onPending }: { ws: string; instanceId: string; version: number; busy: boolean; onPending: (pending: boolean) => void }) {
  const [expanded, setExpanded] = useState(false)
  const [command, setCommand] = useState<{ itemId: string; name: string; action: PlanItemAction } | null>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const client = useQueryClient()
  const panelId = useId()
  const plan = useQuery({ queryKey: ['ws', ws, 'case', instanceId, 'plan', version], queryFn: () => getCasePlan(ws, instanceId), enabled: expanded })
  const items = plan.data?.items ?? []
  const parents = new Map(items.map(item => [item.id, item.name ?? item.elementId]))
  const apply = async () => {
    if (!command || !reason.trim()) return
    onPending(true); setError(null)
    try {
      const updated = await changeCasePlanItem(ws, instanceId, command.itemId, command.action, version, reason.trim())
      client.setQueryData(['ws', ws, 'case', instanceId], updated)
      void client.invalidateQueries({ queryKey: ['ws', ws, 'cases'] })
      setCommand(null); setReason('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The plan item changed. Refresh the case and try again.')
      setCommand(null)
      void client.invalidateQueries({ queryKey: ['ws', ws, 'case', instanceId], exact: true })
      void plan.refetch()
    } finally { onPending(false) }
  }
  return <section className="space-y-3" aria-label="Case plan">
    <Button variant="secondary" aria-expanded={expanded} aria-controls={panelId} onClick={() => setExpanded(value => !value)}>{expanded ? 'Hide case plan' : 'Show case plan'}</Button>
    <div id={panelId} hidden={!expanded} className="space-y-4">
      {expanded && plan.isPending ? <LoadingBlock label="Loading case plan" /> : null}
      {plan.isError ? <QueryError error={plan.error} onRetry={() => void plan.refetch()} what="the case plan" /> : null}
      {error ? <Alert tone="danger">{error}</Alert> : null}
      {plan.data ? <>
        <h2>Stages and plan items</h2>
        {items.length ? <ul className="space-y-2">{items.map(item => <li key={item.id} className="flex flex-wrap items-center gap-2 rounded border p-3">
          <span>{item.name ?? item.elementId}</span><Badge>{STATE_LABELS[item.state] ?? item.state}</Badge>{item.required ? <Badge>Required</Badge> : null}
          <span className="text-sm text-muted-foreground">{ITEM_LABELS[item.type] ?? 'Plan item'}{item.parentStageId ? ` · In stage: ${parents.get(item.parentStageId) ?? item.parentStageId}` : ''}</span>
          {item.workflow ? <span className="w-full text-sm" data-testid={`workflow-${item.id}`}>{WORKFLOW_LABELS[item.workflow.status] ?? item.workflow.status}{item.workflow.reference ? <>: {item.workflow.runId ? <Link className="underline" to={`/w/${ws}/runs/${item.workflow.runId}`}>{item.workflow.reference}</Link> : item.workflow.reference}</> : null}{item.workflow.outcome ? ` · Outcome: ${item.workflow.outcome}` : ''}{item.workflow.attempt > 1 ? ` · Attempt ${item.workflow.attempt}` : ''}{item.workflow.detail ? <span className="block text-muted-foreground">{item.workflow.detail}</span> : null}</span> : null}
          <div className="flex flex-wrap gap-2">{Object.entries(ACTION_LABELS).filter(([action]) => item.allowedActions?.[action] === true).map(([action, label]) => <Button key={action} variant="secondary" disabled={busy} onClick={() => { setReason(''); setCommand({ itemId: item.id, name: item.name ?? item.elementId, action: action as PlanItemAction }) }}>{label}</Button>)}</div>
        </li>)}</ul> : <p>No plan items have started.</p>}
        <h2>Achieved milestones</h2>
        {plan.data.milestones.length ? <ul className="space-y-2">{plan.data.milestones.map(item => <li key={item.id} className="flex flex-wrap gap-2">
          <span>{item.name ?? item.elementId}</span><time className="text-sm text-muted-foreground" dateTime={item.achievedAt}>{new Date(item.achievedAt).toLocaleString()}</time>
        </li>)}</ul> : <p>No milestones have been achieved.</p>}
        <h2>Case history</h2>
        {plan.data.events?.length ? <ol className="space-y-3">{plan.data.events.map(event => <li key={event.version} className="rounded border p-3">
          <p>Version {event.version}: {HISTORY_LABELS[event.action] ?? event.action}{event.planItemId ? `: ${parents.get(event.planItemId) ?? 'Plan item'}` : ''} · {event.actorName ?? 'Case participant'}</p>
          <time className="text-sm text-muted-foreground" dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString()}</time>
          {event.reason ? <p className="mt-1 whitespace-pre-wrap">{event.reason}</p> : null}
        </li>)}</ol> : <p>No command history has been recorded.</p>}
      </> : null}
    </div>
    <Dialog open={command !== null} onClose={() => setCommand(null)} dismissible={!busy} title={command ? `${ACTION_LABELS[command.action]}?` : 'Change plan item'} description={command?.name}>
      <div className="space-y-3">
        <Label htmlFor={`${panelId}-reason`}>Reason</Label>
        <Textarea id={`${panelId}-reason`} value={reason} onChange={event => setReason(event.target.value)} maxLength={1000} disabled={busy} />
        <div className="flex gap-2"><Button disabled={busy || !reason.trim()} loading={busy} onClick={() => void apply()}>Confirm plan change</Button><Button variant="secondary" disabled={busy} onClick={() => setCommand(null)}>Keep current work</Button></div>
      </div>
    </Dialog>
  </section>
}
