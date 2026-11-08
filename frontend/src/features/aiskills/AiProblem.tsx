import { useState } from 'react'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { describeProblem } from '@/lib/problemMessages'

/**
 * Problems of the AI skills API. The four-eyes refusal and state conflicts get messages written for skills (the general
 * table speaks of requests); the server's own sentence for these codes names the exact state ("Only a submitted version
 * can be approved."), so it is shown under the message as the reason. Field errors are listed when the caller does not
 * place them next to fields itself.
 */
const SPECIFIC: Record<string, string> = {
  SELF_APPROVAL_DENIED: 'You are the author of this change, so someone else who may approve AI skills has to approve it (four eyes).',
  AI_SKILL_STATE_CONFLICT: 'This changed state since you loaded it, so the action does not apply any more.',
  CONFIGURATION_VERSION_CONFLICT: 'Someone else changed this since you loaded it. Reload to see their change, then make yours again.',
  REASON_REQUIRED: 'Give a reason.',
  FORBIDDEN: 'You do not have the permission this action needs.',
  AI_MODEL_NOT_ALLOWED: 'The model profile of this version is not allowed in this workspace (policy, residency or price limits).',
}

const REASON_CODES = new Set(['AI_SKILL_STATE_CONFLICT', 'AI_MODEL_NOT_ALLOWED', 'VALIDATION_FAILED'])

export function AiProblem({ error, action, listFieldErrors = true }: { error: unknown; action: string; listFieldErrors?: boolean }) {
  const problem = toProblem(error)
  const specific = SPECIFIC[problem.code]
  if (!specific && problem.code !== 'VALIDATION_FAILED') return <ProblemAlert error={problem} context={{ action }} />
  const message = specific ?? describeProblem(problem, { action }).message
  return (
    <Alert tone="danger" data-testid="ai-problem" data-code={problem.code}>
      <p>{message}</p>
      {REASON_CODES.has(problem.code) && problem.detail ? <p className="mt-1">Reason: {problem.detail}</p> : null}
      {listFieldErrors && problem.fieldErrors.length > 0 ? (
        <ul className="mt-1 list-disc pl-5">
          {problem.fieldErrors.map((f, i) => <li key={i}><span className="font-mono text-xs">{f.pointer || '/'}</span> {f.message}</li>)}
        </ul>
      ) : null}
      {problem.correlationId ? <p className="mt-1 text-xs opacity-80">Support reference: <span className="font-mono">{problem.correlationId}</span></p> : null}
    </Alert>
  )
}

/** Run an async action with pending and error state (the dialogs of this feature share it). */
export function useAction() {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const run = async <T,>(action: () => Promise<T>, then?: (result: T) => void | Promise<void>) => {
    setPending(true)
    setError(null)
    try {
      const result = await action()
      await then?.(result)
      return true
    } catch (cause) {
      setError(cause)
      return false
    } finally {
      setPending(false)
    }
  }
  return { pending, error, setError, run }
}
