import { Bell, CheckCheck, FileInput, GitBranch, ListChecks, Plug, UserCheck, type LucideIcon } from 'lucide-react'
import type { NodeKind } from './model/types'

export const KIND_ICON: Record<NodeKind, LucideIcon> = {
  start: FileInput,
  approval: UserCheck,
  decision: ListChecks,
  branch: GitBranch,
  http: Plug,
  notification: Bell,
  end: CheckCheck,
}

/**
 * Per-kind colors, all from theme tokens (src/index.css). The accent bar and the palette icon use `accent`; the kind
 * badge on a card uses `badge` (text on a soft tint, 4.5:1). The kind name is always printed as text too, so color is
 * never the only signal.
 */
export const KIND_ACCENT: Record<NodeKind, string> = {
  start: 'bg-kind-start',
  approval: 'bg-kind-approval',
  decision: 'bg-kind-decision',
  branch: 'bg-kind-branch',
  http: 'bg-kind-http',
  notification: 'bg-kind-notification',
  end: 'bg-kind-end',
}

export const KIND_BADGE: Record<NodeKind, string> = {
  start: 'bg-kind-start-soft text-kind-start',
  approval: 'bg-kind-approval-soft text-kind-approval',
  decision: 'bg-kind-decision-soft text-kind-decision',
  branch: 'bg-kind-branch-soft text-kind-branch',
  http: 'bg-kind-http-soft text-kind-http',
  notification: 'bg-kind-notification-soft text-kind-notification',
  end: 'bg-kind-end-soft text-kind-end',
}

/** CSS variable per kind, for SVG (the minimap) where utility classes do not reach. */
export const KIND_VAR: Record<NodeKind, string> = {
  start: 'var(--kind-start)',
  approval: 'var(--kind-approval)',
  decision: 'var(--kind-decision)',
  branch: 'var(--kind-branch)',
  http: 'var(--kind-http)',
  notification: 'var(--kind-notification)',
  end: 'var(--kind-end)',
}
