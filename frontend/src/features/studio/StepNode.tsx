import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { AlertTriangle, CheckCircle2, XCircle } from 'lucide-react'
import { memo } from 'react'
import { cn } from '@/lib/cn'
import { KINDS } from './model/kinds'
import { APPROVAL_HEIGHT, NODE_HEIGHT } from './model/layout'
import type { NodeKind } from './model/types'
import { KIND_ACCENT, KIND_BADGE, KIND_ICON } from './nodeMeta'

export interface StepNodeData extends Record<string, unknown> {
  kind: NodeKind
  label: string
  subtitle: string
  blockers: number
  warnings: number
  /** Finish steps only: which kind of outcome the step ends with. */
  outcomeKind?: 'success' | 'rejected'
  /** Finish steps only: the outcome name shown on the card, for example "Fulfilled". */
  outcome?: string
  timeout?: boolean
}

export type StepFlowNode = Node<StepNodeData, 'step'>

/**
 * Custom node card (296 px wide, text 14 px or larger even at the 85% fit zoom): a kind badge (icon and name), the step name, a one-line
 * summary, an issue badge from the last check, and the labelled handles. Finish steps are tinted by outcome
 * (Fulfilled green, Rejected slate) and always print the outcome as text with an icon.
 */
function StepNodeComponent({ data, selected }: NodeProps<StepFlowNode>) {
  const info = KINDS[data.kind]
  const Icon = KIND_ICON[data.kind]
  const issues = data.blockers + data.warnings
  const isEnd = data.kind === 'end'
  const rejectedEnd = isEnd && data.outcomeKind === 'rejected'
  const OutcomeIcon = rejectedEnd ? XCircle : CheckCircle2
  return (
    <div
      className={cn(
        'relative flex w-[296px] rounded-card border-2 shadow-card',
        isEnd ? (rejectedEnd ? 'bg-status-rejected-bg' : 'bg-status-success-bg') : 'bg-surface',
        selected ? 'border-primary ring-2 ring-primary/25' : isEnd ? (rejectedEnd ? 'border-outcome-rejected-border' : 'border-outcome-success-border') : 'border-input-border',
      )}
      style={{ minHeight: data.kind === 'approval' ? APPROVAL_HEIGHT - 8 : NODE_HEIGHT - 8 }}
      data-kind={data.kind}
      data-testid={`node-card-${data.kind}`}
    >
      {data.kind !== 'start' ? <Handle type="target" position={Position.Left} id="in" aria-hidden="true" /> : null}
      <span className={cn('w-2 shrink-0 rounded-l-[10px]', isEnd ? (rejectedEnd ? 'bg-status-rejected-fg' : 'bg-status-success-fg') : KIND_ACCENT[data.kind])} aria-hidden="true" />
      <div className={cn('flex min-w-0 flex-1 flex-col gap-1 px-3 py-2.5', data.kind === 'approval' && 'pr-[8.25rem]')}>
        <div className="flex items-center justify-between gap-2">
          <span className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[15px] leading-6 font-semibold', KIND_BADGE[data.kind])}>
            <Icon className="size-4" aria-hidden="true" />
            {info.caption}
          </span>
          {issues > 0 ? (
            <span
              className={cn(
                'inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[15px] leading-6 font-bold',
                data.blockers > 0 ? 'bg-danger-soft text-danger-foreground' : 'bg-warning-soft text-warning-foreground',
              )}
              data-testid="node-issues"
            >
              <AlertTriangle className="size-4" aria-hidden="true" />
              {issues}
              <span className="sr-only"> {issues === 1 ? 'issue' : 'issues'}</span>
            </span>
          ) : null}
        </div>
        <p className="line-clamp-2 text-lg leading-snug font-semibold text-foreground" title={data.label}>
          {data.label}
        </p>
        {isEnd ? (
          <p className={cn('inline-flex items-center gap-1.5 text-[17px] leading-6 font-semibold', rejectedEnd ? 'text-status-rejected-fg' : 'text-status-success-fg')} title={data.subtitle}>
            <OutcomeIcon className="size-5 shrink-0" aria-hidden="true" />
            <span className="truncate">{data.outcome || (rejectedEnd ? 'Rejected' : 'Completed')}</span>
          </p>
        ) : (
          <p className="truncate text-[17px] leading-6 text-muted-foreground" title={data.subtitle}>
            {data.subtitle}
          </p>
        )}
      </div>

      {data.kind === 'approval' ? (
        <>
          <Handle type="source" position={Position.Right} id="approved" style={{ top: '38%' }} aria-hidden="true" />
          <Handle type="source" position={Position.Right} id="rejected" style={{ top: '78%' }} aria-hidden="true" />
          <span className="pointer-events-none absolute top-[38%] right-5 flex -translate-y-1/2 items-center gap-1.5 rounded-full bg-success-soft px-2.5 py-0.5 text-[15px] leading-6 font-bold text-success-foreground">
            <CheckCircle2 className="size-4" aria-hidden="true" /> Approved
          </span>
          <span className="pointer-events-none absolute top-[78%] right-5 flex -translate-y-1/2 items-center gap-1.5 rounded-full bg-danger-soft px-2.5 py-0.5 text-[15px] leading-6 font-bold text-danger-foreground">
            <XCircle className="size-4" aria-hidden="true" /> Rejected
          </span>
          {data.timeout ? <Handle type="source" position={Position.Bottom} id="timedOut" aria-label="Timed out route" /> : null}
        </>
      ) : data.kind === 'branch' ? (
        <Handle type="source" position={Position.Right} id="route" aria-hidden="true" />
      ) : data.kind !== 'end' ? (
        <Handle type="source" position={Position.Right} id="next" aria-hidden="true" />
      ) : null}
    </div>
  )
}

export const StepNode = memo(StepNodeComponent)

