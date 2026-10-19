import { useQuery } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { getCasePlan } from './api'

export function CasePlan({ ws, instanceId, version }: { ws: string; instanceId: string; version: number }) {
  const [expanded, setExpanded] = useState(false)
  const panelId = useId()
  const plan = useQuery({ queryKey: ['ws', ws, 'case', instanceId, 'plan', version], queryFn: () => getCasePlan(ws, instanceId), enabled: expanded })
  const items = plan.data?.items ?? []
  const parents = new Map(items.map(item => [item.id, item.name ?? item.elementId]))
  return <section className="space-y-3" aria-label="Case plan">
    <Button variant="secondary" aria-expanded={expanded} aria-controls={panelId} onClick={() => setExpanded(value => !value)}>{expanded ? 'Hide case plan' : 'Show case plan'}</Button>
    <div id={panelId} hidden={!expanded} className="space-y-4">
      {expanded && plan.isPending ? <LoadingBlock label="Loading case plan" /> : null}
      {plan.isError ? <QueryError error={plan.error} onRetry={() => void plan.refetch()} what="the case plan" /> : null}
      {plan.data ? <>
        <h2>Stages and plan items</h2>
        {items.length ? <ul className="space-y-2">{items.map(item => <li key={item.id} className="flex flex-wrap items-center gap-2 rounded border p-3">
          <span>{item.name ?? item.elementId}</span><Badge>{item.state}</Badge>
          <span className="text-sm text-muted-foreground">{item.type}{item.parentStageId ? ` · In stage: ${parents.get(item.parentStageId) ?? item.parentStageId}` : ''}</span>
        </li>)}</ul> : <p>No plan items have started.</p>}
        <h2>Achieved milestones</h2>
        {plan.data.milestones.length ? <ul className="space-y-2">{plan.data.milestones.map(item => <li key={item.id} className="flex flex-wrap gap-2">
          <span>{item.name ?? item.elementId}</span><time className="text-sm text-muted-foreground" dateTime={item.achievedAt}>{new Date(item.achievedAt).toLocaleString()}</time>
        </li>)}</ul> : <p>No milestones have been achieved.</p>}
      </> : null}
    </div>
  </section>
}
