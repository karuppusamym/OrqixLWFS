import { AlertTriangle, CheckCircle2, CircleDot, Circle } from 'lucide-react'
import type { StageView } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'
import { formatDay } from '@/lib/dates'
import { stageSentence } from '@/lib/stages'

/** A compact badge for lists: "Finance review" and, when late, "Overdue". Text and an icon, never color alone. */
export function StageBadge({ stage }: { stage: StageView | null | undefined }) {
  if (!stage) return null
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5" data-testid="stage-badge">
      <Badge tone={stage.state === 'completed' ? 'success' : 'info'}>{stage.label}</Badge>
      {stage.overdue ? (
        <Badge tone="danger" data-testid="overdue-badge">
          <AlertTriangle aria-hidden="true" /> Overdue
        </Badge>
      ) : null}
    </span>
  )
}

/** The stage of a task in a list: its name, when it is due and, when late, the overdue badge. */
export function TaskStage({ stage }: { stage: StageView | null | undefined }) {
  if (!stage) return null
  return (
    <span className="mt-1 flex flex-col items-start gap-1" data-testid="task-stage">
      <StageBadge stage={stage} />
      {stage.dueAt && stage.state !== 'completed' ? <span className="text-xs text-muted-foreground">Stage due {formatDay(stage.dueAt)}</span> : null}
    </span>
  )
}

/**
 * The stage progress of a request (docs/build/S17 section 4): one segment per stage and a sentence such as
 * "Stage 2 of 4: Finance review - due 14 Oct - overdue". Renders nothing for workflows without stages.
 */
export function StageProgress({ stage, stages }: { stage: StageView | null | undefined; stages: StageView[] | undefined }) {
  if (!stages || stages.length === 0) return null
  const overdue = stage?.overdue === true
  return (
    <section
      aria-label="Stage progress"
      className={cn('rounded-card border px-4 py-3', overdue ? 'border-danger bg-danger-soft' : 'border-border bg-surface')}
      data-testid="stage-progress"
    >
      <p className="flex flex-wrap items-center gap-2 text-sm font-semibold" data-testid="stage-sentence">
        {overdue ? <AlertTriangle className="size-4 shrink-0 text-danger" aria-hidden="true" /> : null}
        {stage ? stageSentence(stage) : 'This request has not reached its first stage yet.'}
      </p>
      <ol className="mt-3 grid gap-2" style={{ gridTemplateColumns: `repeat(${stages.length}, minmax(0, 1fr))` }}>
        {stages.map((s) => (
          <li key={s.id} className="min-w-0" data-stage-state={s.state} data-stage-overdue={s.overdue || undefined}>
            <div
              className={cn(
                'h-2 rounded-full',
                s.state === 'completed' && 'bg-success',
                s.state === 'active' && (s.overdue ? 'bg-danger' : 'bg-primary'),
                s.state === 'notStarted' && 'bg-neutral-soft',
              )}
              aria-hidden="true"
            />
            <p className="mt-1.5 flex items-start gap-1 text-xs">
              {s.state === 'completed' ? <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-success" aria-hidden="true" /> : s.state === 'active' ? <CircleDot className="mt-0.5 size-3.5 shrink-0 text-primary-text" aria-hidden="true" /> : <Circle className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />}
              <span className="min-w-0 wrap-anywhere">
                <span className={cn(s.state === 'active' && 'font-semibold')}>{s.label}</span>
                <span className="sr-only">{s.state === 'completed' ? ' (done)' : s.state === 'active' ? (s.overdue ? ' (current, overdue)' : ' (current)') : ' (not started)'}</span>
              </span>
            </p>
          </li>
        ))}
      </ol>
    </section>
  )
}
