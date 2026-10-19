import type { Effect } from '@/api/types'

/** Plain-language effect states. An unknown outcome is never shown as plain "Failed" (docs/build/S5-runtime.md section 6.2). */
export const EFFECT_STATE_LABEL: Record<Effect['state'], string> = {
  pending: 'Not started',
  inFlight: 'In progress',
  succeeded: 'Completed',
  retryableFailure: 'Did not respond, will retry',
  permanentFailure: 'Rejected by the other system',
  unknown: 'Outcome unknown — check the other system',
  notApplied: 'Confirmed not done',
}

export const EFFECT_CLASS_LABEL: Record<Effect['effectClass'], string> = {
  readOnly: 'Read only: safe to retry',
  idempotentWithKey: 'Safe to retry: the service de-duplicates repeated requests',
  nonIdempotent: 'Not safe to retry automatically: if there is no answer, someone must check the outcome',
}

/** Reasons the server gives when an action is unavailable (ActionAvailability.reasonCode). */
const REASONS: Record<string, string> = {
  EFFECT_NOT_RECONCILED: 'The outcome of a step is unknown. Reconcile it first.',
  RUN_NOT_ACTIVE: 'This request already finished or was cancelled.',
  RUN_NOT_RECOVERABLE: 'This request is not waiting on a failed step.',
  RUN_BUSY: 'The request is being processed right now.',
  RUN_SUSPENDED: 'This request is suspended. Resume it first.',
  RUN_NOT_MIGRATABLE: 'This request cannot move to that release.',
  RUN_NOT_SUSPENDED: 'This request is not suspended.',
  RUN_NOT_REPAIRABLE: 'There is nothing here that can be repaired safely.',
  RUN_NOT_SUSPENDABLE: 'Requests that need attention, child requests and requests with running child requests cannot be suspended.',
  NOTHING_TO_RECONCILE: 'No step needs reconciling.',
  NOT_NEEDS_ATTENTION: 'This request does not need attention.',
  FORBIDDEN: "You don't have permission to do this.",
}

export function reasonText(code: string | null | undefined): string {
  if (!code) return 'Not available right now.'
  return REASONS[code] ?? `Not available (${code.toLowerCase().replace(/_/g, ' ')}).`
}

/** A message sent through an agent write tool (IN04): reconciled on the Agent writes page, never as a step result. */
export function isAgentWrite(effect: Effect): boolean {
  return effect.operation.startsWith('agentWrite.')
}

export function candidateEffects(effects: Effect[] | undefined): Effect[] {
  return (effects ?? []).filter((e) => !isAgentWrite(e) && (e.state === 'unknown' || e.state === 'permanentFailure' || e.state === 'retryableFailure'))
}
