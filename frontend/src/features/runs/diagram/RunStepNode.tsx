import type { Node, NodeProps } from '@xyflow/react'
import { AlertTriangle, Ban, CheckCircle2, Circle, Clock, Hourglass, MapPin, MinusCircle, ThumbsDown, ThumbsUp, Timer, XCircle, type LucideIcon } from 'lucide-react'
import { memo } from 'react'
import { cn } from '@/lib/cn'
import { StepNode, type StepFlowNode, type StepNodeData } from '@/features/studio/StepNode'
import { statusLabel, type StepStatus } from './pathModel'

export interface RunStepData extends Record<string, unknown> {
  card: StepNodeData
  status: StepStatus
  operator: boolean
  /** Id of the visually hidden description element (full status sentence for assistive technology). */
  descriptionId: string
  description: string
  occurrences: number
}

export type RunFlowNode = Node<RunStepData, 'runStep'>

const ICON: Record<StepStatus, LucideIcon> = {
  waiting: MapPin,
  running: Hourglass,
  attention: AlertTriangle,
  done: CheckCircle2,
  approved: ThumbsUp,
  rejected: ThumbsDown,
  failed: XCircle,
  timedOut: Timer,
  stopped: Ban,
  skipped: MinusCircle,
  ahead: Circle,
}

/** Chip colors by status: theme tokens only; the text and the icon say the same thing. */
const CHIP: Record<StepStatus, string> = {
  waiting: 'border-status-waiting-fg bg-status-waiting-bg text-status-waiting-fg',
  running: 'border-status-running-fg bg-status-running-bg text-status-running-fg',
  attention: 'border-status-attention-fg bg-status-attention-bg text-status-attention-fg',
  done: 'border-status-success-fg bg-status-success-bg text-status-success-fg',
  approved: 'border-status-success-fg bg-status-success-bg text-status-success-fg',
  rejected: 'border-status-rejected-fg bg-status-rejected-bg text-status-rejected-fg',
  failed: 'border-danger bg-danger-soft text-danger-foreground',
  timedOut: 'border-status-attention-fg bg-status-attention-bg text-status-attention-fg',
  stopped: 'border-status-cancelled-fg bg-status-cancelled-bg text-status-cancelled-fg',
  skipped: 'border-border bg-surface-muted text-muted-foreground',
  ahead: 'border-border bg-surface text-muted-foreground',
}

/**
 * A step of the workflow with its state in this request: the studio's step card, a text chip (icon and words) and, for
 * the current step, an outline that pulses. Steps this request did not use are dimmed and say so in words.
 */
function RunStepNodeComponent(props: NodeProps<RunFlowNode>) {
  const { data } = props
  const Icon = data.status === 'waiting' && data.operator ? Clock : ICON[data.status]
  const label = statusLabel(data.status, data.operator)
  const showChip = data.status !== 'ahead'
  return (
    <div className="run-step" data-status={data.status} data-dim={data.status === 'skipped'} data-testid={`run-step-${props.id}`} data-step-status={data.status}>
      <div className="run-step-card">
        <StepNode {...(props as unknown as NodeProps<StepFlowNode>)} data={data.card} selected={false} />
      </div>
      {showChip ? (
        <span
          className={cn('absolute -top-5 left-4 z-10 inline-flex items-center gap-1.5 rounded-full border-2 px-3 py-0.5 text-[15px] leading-6 font-bold shadow-card', CHIP[data.status])}
          data-testid="run-step-chip"
        >
          <Icon className="size-4" aria-hidden="true" />
          {label}
          {data.occurrences > 1 ? <span className="font-semibold"> · {data.occurrences}x</span> : null}
        </span>
      ) : null}
      <span id={data.descriptionId} className="sr-only">
        {data.description}
      </span>
    </div>
  )
}

export const RunStepNode = memo(RunStepNodeComponent)
