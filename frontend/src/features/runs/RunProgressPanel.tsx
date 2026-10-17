import { GitFork, ListOrdered } from 'lucide-react'
import { lazy, Suspense, type ReactNode } from 'react'
import type { RunDetail } from '@/api/types'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { cn } from '@/lib/cn'

// The canvas libraries load only when the diagram is opened.
const RunDiagram = lazy(() => import('./diagram/RunDiagram'))

export type RunView = 'timeline' | 'diagram'

interface Props {
  ws: string
  run: RunDetail
  operator: boolean
  view: RunView
  onView: (view: RunView) => void
  /** The existing timeline list (the default view). */
  timeline: ReactNode
}

/**
 * The "where is this request" card: a toggle between the Timeline (a list, always available) and the read-only Diagram
 * of the workflow. The diagram never replaces the timeline.
 */
export function RunProgressPanel({ ws, run, operator, view, onView, timeline }: Props) {
  return (
    <Card className={cn(view === 'timeline' && 'xl:sticky xl:top-20')}>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="timeline-heading">{view === 'timeline' ? 'Timeline' : 'Where is this request?'}</h2>
          <div role="group" aria-label="Show as" className="inline-flex rounded-full border border-border bg-neutral-soft p-0.5" data-testid="run-view-toggle">
            {(
              [
                ['timeline', 'Timeline', ListOrdered],
                ['diagram', 'Diagram', GitFork],
              ] as const
            ).map(([value, label, Icon]) => (
              <button
                key={value}
                type="button"
                aria-pressed={view === value}
                onClick={() => onView(value)}
                className={cn(
                  'inline-flex min-h-9 items-center gap-1.5 rounded-full px-3 text-sm font-semibold',
                  view === value ? 'bg-surface text-primary-text shadow-card' : 'text-muted-foreground hover:bg-neutral-hover',
                )}
              >
                <Icon className="size-4" aria-hidden="true" /> {label}
              </button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {view === 'timeline' ? (
          timeline
        ) : (
          <Suspense fallback={<LoadingBlock rows={4} label="Loading the diagram" />}>
            <RunDiagram ws={ws} run={run} operator={operator} />
          </Suspense>
        )}
      </CardContent>
    </Card>
  )
}
