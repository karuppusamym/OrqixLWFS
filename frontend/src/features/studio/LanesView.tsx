import { useMemo } from 'react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { useStudio, useStudioContext } from './StudioContext'
import { useDiscussion } from './DiscussionPanel'
import { deriveLanes } from './model/lanes'
import { KINDS } from './model/kinds'
import { KIND_ICON } from './nodeMeta'

/**
 * Lanes tab (UI03, docs/build/S53): the steps laid out by who or what is responsible. It is derived from the
 * definition and read-only; choosing a step opens it in the inspector, where it is edited as usual.
 */
export function LanesView({ groupNames }: { groupNames: Record<string, string> }) {
  const def = useStudio((s) => s.definition)
  const selection = useStudio((s) => s.selection)
  const { store, wsId, workflowId } = useStudioContext()
  const discussion = useDiscussion(wsId, workflowId)
  const open = discussion.data?.openByElement ?? {}
  const model = useMemo(() => deriveLanes(def, groupNames), [def, groupNames])
  const laneLabel = useMemo(() => new Map(model.lanes.map((l) => [l.id, l.label])), [model])
  const stepLabel = useMemo(() => new Map(def.nodes.map((n) => [n.id, n.label])), [def])

  if (def.nodes.length === 0) return <p className="p-6 text-sm text-muted">Add steps to see who is responsible for each one.</p>

  return (
    <div className="space-y-4 p-4" data-testid="lanes">
      <p className="text-sm text-muted">Each row is a person, group or system that does the work. Steps run left to right in flow order. Lanes come from the approval groups; change them on the step.</p>
      <div className="overflow-x-auto">
        <div className="grid min-w-max gap-px rounded-md border border-border bg-border" style={{ gridTemplateColumns: `11rem repeat(${model.columns}, 9.5rem)` }} role="list" aria-label="Swimlanes">
          {model.lanes.map((lane) => (
            <div key={lane.id} role="listitem" aria-label={`Lane ${lane.label}`} data-testid={`lane-${lane.id}`} className="contents">
              <div className="bg-surface p-3 text-sm font-semibold">{lane.label}<span className="block text-xs font-normal text-muted">{lane.steps.length} {lane.steps.length === 1 ? 'step' : 'steps'}</span></div>
              {Array.from({ length: model.columns }, (_, column) => {
                const step = lane.steps.find((s) => s.column === column)
                if (!step) return <div key={column} className="bg-canvas" />
                const Icon = KIND_ICON[step.type]
                const selected = selection?.type === 'node' && selection.id === step.id
                return (
                  <div key={column} className="bg-canvas p-1.5">
                    <button
                      type="button"
                      aria-pressed={selected}
                      onClick={() => store.getState().select({ type: 'node', id: step.id })}
                      className={cn('flex h-full w-full flex-col items-start gap-1 rounded-md border bg-surface p-2 text-left text-sm focus-visible:outline focus-visible:outline-2', selected ? 'border-accent ring-2 ring-accent' : 'border-border')}
                    >
                      <span className="flex items-center gap-1 text-xs text-muted"><Icon className="size-3.5" aria-hidden="true" />{KINDS[step.type].caption}</span>
                      <span className="font-medium">{step.label}</span>
                      {open[step.id] ? <Badge tone="warning">{open[step.id]} open {open[step.id] === 1 ? 'thread' : 'threads'}</Badge> : null}
                    </button>
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </div>
      <section aria-label="Handoffs">
        <h2 className="text-base">Handoffs</h2>
        {model.handoffs.length === 0 ? (
          <p className="mt-1 text-sm text-muted">Every step stays in one lane.</p>
        ) : (
          <ul className="mt-2 space-y-1 text-sm" data-testid="handoffs">
            {model.handoffs.map((h) => (
              <li key={h.transitionId}>
                {laneLabel.get(h.fromLane)} &rarr; {laneLabel.get(h.toLane)}: <span className="font-medium">{stepLabel.get(h.from)}</span> to <span className="font-medium">{stepLabel.get(h.to)}</span>{h.on !== 'next' ? ` (${h.on})` : ''}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
