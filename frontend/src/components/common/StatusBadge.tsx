import { AlertTriangle, Ban, CheckCircle2, Clock, Loader2, XCircle } from 'lucide-react'
import type { Lifecycle, OutcomeKind } from '@/api/types'
import { cn } from '@/lib/cn'
import { runStatus, type StatusTone } from '@/lib/status'

const TONE_CLASS: Record<StatusTone, string> = {
  waiting: 'bg-status-waiting-bg text-status-waiting-fg',
  running: 'bg-status-running-bg text-status-running-fg',
  attention: 'bg-status-attention-bg text-status-attention-fg',
  success: 'bg-status-success-bg text-status-success-fg',
  rejected: 'bg-status-rejected-bg text-status-rejected-fg',
  failed: 'bg-danger-soft text-danger-foreground',
  cancelled: 'bg-status-cancelled-bg text-status-cancelled-fg',
}

const TONE_ICON: Record<StatusTone, typeof Clock> = {
  waiting: Clock,
  running: Loader2,
  attention: AlertTriangle,
  success: CheckCircle2,
  rejected: XCircle,
  failed: XCircle,
  cancelled: Ban,
}

/** Status is always icon + text + color, never color alone. */
export function StatusBadge({
  lifecycle,
  outcome,
  outcomeKind,
  className,
}: {
  lifecycle: Lifecycle
  outcome?: string | null
  outcomeKind?: OutcomeKind | null
  className?: string
}) {
  const status = runStatus({ lifecycle, outcome, outcomeKind })
  const Icon = TONE_ICON[status.tone]
  return (
    <span
      className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap', TONE_CLASS[status.tone], className)}
      data-status={status.tone}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {status.label}
    </span>
  )
}
