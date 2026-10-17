import { FileQuestion, ShieldAlert } from 'lucide-react'
import { Link } from 'react-router'
import { describeProblem } from '@/lib/problemMessages'
import { Alert } from '@/components/ui/alert'
import { EmptyState } from './EmptyState'
import { ProblemAlert, toProblem } from './ProblemAlert'

/**
 * Page-level query failure. NOT_FOUND becomes the not-found state ("doesn't exist or you don't have access"),
 * FORBIDDEN an inline permission message, everything else a problem alert with retry.
 */
export function QueryError({
  error,
  onRetry,
  what = 'this page',
  backTo,
}: {
  error: unknown
  onRetry?: () => void
  what?: string
  backTo?: { to: string; label: string }
}) {
  const problem = toProblem(error)
  const ui = describeProblem(problem, { action: `view ${what}` })
  if (ui.behavior === 'not-found') {
    return (
      <EmptyState
        icon={FileQuestion}
        title="Page not found"
        action={backTo ? <Link to={backTo.to}>{backTo.label}</Link> : undefined}
      >
        This page doesn&apos;t exist or you don&apos;t have access to it.
      </EmptyState>
    )
  }
  if (ui.behavior === 'forbidden') {
    return (
      <Alert tone="warning" title="No access">
        <span className="inline-flex items-center gap-2">
          <ShieldAlert className="size-4" aria-hidden="true" />
          {ui.message}
        </span>
      </Alert>
    )
  }
  return <ProblemAlert error={error} onRetry={onRetry} />
}
