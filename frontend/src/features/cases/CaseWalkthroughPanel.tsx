import { RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { CaseModelItem } from './caseModel'
import { act, actionsFor, caseCanClose, startWalk, type WalkAction, type WalkState, type WalkStatus } from './caseWalkthrough'

const STATUS: Record<WalkStatus, { label: string; tone: 'neutral' | 'info' | 'warning' | 'success' }> = {
  waiting: { label: 'Waiting', tone: 'neutral' },
  offered: { label: 'Not planned', tone: 'neutral' },
  enabled: { label: 'Can be started', tone: 'warning' },
  active: { label: 'In progress', tone: 'info' },
  available: { label: 'Waiting for event', tone: 'warning' },
  completed: { label: 'Done', tone: 'success' },
  ended: { label: 'Ended', tone: 'neutral' },
}
const ACTION: Record<WalkAction, string> = { plan: 'Plan it', start: 'Start', complete: 'Complete', occur: 'It happens', data: 'Data condition met' }
const KIND: Record<string, string> = { humanTask: 'Task', processTask: 'Workflow task', caseTask: 'Case task', decisionTask: 'Decision', stage: 'Stage', milestone: 'Milestone', userEventListener: 'User event', timerEventListener: 'Timer' }

/** The Test tab of the case designer: walk the plan's rules with the designer playing people, data and time. */
export function CaseWalkthroughPanel({ items }: { items: CaseModelItem[] }) {
  const [state, setState] = useState<WalkState>(() => startWalk(items))
  const render = (list: CaseModelItem[]) => <ul className="space-y-2">{list.map(item => {
    const status = STATUS[state.status[item.id] ?? 'waiting']
    return <li key={item.id} className="rounded-control border border-border bg-surface p-3" aria-label={`${item.label}: ${status.label}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="min-w-0"><span className="font-semibold">{item.label}</span> <span className="text-xs text-muted-foreground">{KIND[item.kind] ?? item.kind}{item.required ? ' · required' : ''}{item.group ? ` · ${item.group}` : ''}</span></p>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={status.tone}>{status.label}</Badge>
          {actionsFor(item, state).map(action => <Button key={action} size="sm" variant={action === 'complete' || action === 'start' ? 'primary' : 'secondary'} aria-label={`${ACTION[action]}: ${item.label}`} onClick={() => setState(act(items, state, item.id, action))}>{ACTION[action]}</Button>)}
        </div>
      </div>
      {item.children?.length ? <div className="mt-3 border-l-2 border-border pl-3">{render(item.children)}</div> : null}
    </li>
  })}</ul>

  return <div className="grid max-w-5xl gap-4 lg:grid-cols-[minmax(0,1fr)_18rem]">
    <section aria-label="Plan walkthrough" className="space-y-3">
      <Alert tone="info" live="off" title="Walk through the plan">
        This follows the plan&apos;s rules in your browser: what can start, what waits, and when stages, milestones and the case finish. You play the people, the data and the clock. Nothing is saved or run: workflows, decisions and child cases do not execute. To test the real thing, publish and start a case.
      </Alert>
      <div className="flex flex-wrap items-center gap-3">
        <Badge tone={caseCanClose(items, state) ? 'success' : 'neutral'}>{caseCanClose(items, state) ? 'The case can be closed' : 'Required work is open'}</Badge>
        <Button size="sm" variant="ghost" onClick={() => setState(startWalk(items))}><RotateCcw aria-hidden="true" /> Start again</Button>
      </div>
      {items.length ? render(items) : <p className="text-sm text-muted-foreground">The plan has no items yet.</p>}
    </section>
    <section aria-label="What happened" className="space-y-2 rounded-card border border-border bg-surface p-3">
      <h2 className="text-sm">What happened</h2>
      <ol className="list-decimal space-y-1 pl-5 text-sm" aria-live="polite">{state.log.map((line, index) => <li key={index}>{line}</li>)}</ol>
    </section>
  </div>
}
