import type { Lifecycle, OutcomeKind } from '@/api/types'

/** Run status shown to people: lifecycle plus outcome, never color alone (docs/build/S7-frontend.md section 8). */
export type StatusTone = 'waiting' | 'running' | 'attention' | 'success' | 'rejected' | 'cancelled'

export interface DisplayStatus {
  tone: StatusTone
  label: string
}

export function runStatus(run: {
  lifecycle: Lifecycle
  outcome?: string | null
  outcomeKind?: OutcomeKind | null
}): DisplayStatus {
  switch (run.lifecycle) {
    case 'waiting':
      return { tone: 'waiting', label: 'Waiting' }
    case 'running':
      return { tone: 'running', label: 'Running' }
    case 'needsAttention':
      return { tone: 'attention', label: 'Needs attention' }
    case 'cancelled':
      return { tone: 'cancelled', label: 'Cancelled' }
    case 'completed': {
      const outcome = run.outcome?.trim()
      const kind = run.outcomeKind
      return {
        tone: kind === 'rejected' ? 'rejected' : 'success',
        label: outcome ? `Completed · ${outcome}` : 'Completed',
      }
    }
  }
}

export const LIFECYCLE_LABELS: Record<Lifecycle, string> = {
  waiting: 'Waiting',
  running: 'Running',
  needsAttention: 'Needs attention',
  completed: 'Completed',
  cancelled: 'Cancelled',
}

export function isTerminal(lifecycle: Lifecycle): boolean {
  return lifecycle === 'completed' || lifecycle === 'cancelled'
}
