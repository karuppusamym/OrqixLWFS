import { RotateCw } from 'lucide-react'
import { isProblemError, ProblemError } from '@/api/problem'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { describeProblem, type ProblemContext } from '@/lib/problemMessages'

export function toProblem(error: unknown): ProblemError {
  if (isProblemError(error)) return error
  return new ProblemError({
    code: 'UNKNOWN_ERROR',
    status: 0,
    title: 'Unexpected error',
    detail: error instanceof Error ? error.message : undefined,
  })
}

/** Business-readable error with the support reference (correlation ID) and an optional retry. */
export function ProblemAlert({
  error,
  context,
  onRetry,
  retrying,
  title,
  className,
}: {
  error: unknown
  context?: ProblemContext
  onRetry?: () => void
  retrying?: boolean
  title?: string
  className?: string
}) {
  const problem = toProblem(error)
  const ui = describeProblem(problem, context)
  return (
    <Alert tone="danger" title={title} className={className}>
      <p>{ui.message}</p>
      {problem.correlationId ? (
        <p className="mt-1 text-xs opacity-80">
          Support reference: <span className="font-mono">{problem.correlationId}</span>
        </p>
      ) : null}
      {onRetry && (ui.retryable || ui.behavior === 'retry' || ui.behavior === 'message') ? (
        <Button variant="secondary" size="sm" className="mt-3" onClick={onRetry} loading={retrying}>
          <RotateCw aria-hidden="true" /> Try again
        </Button>
      ) : null}
    </Alert>
  )
}
