import type { ClientProblemCode, ProblemError } from '@/api/problem'
import type { ProblemCode, TaskDetail } from '@/api/types'

/**
 * Problem code -> user-facing behavior, exactly as the table in docs/build/S7-frontend.md section 3.
 * Clients switch on `code` only; server titles and details are never shown as the primary message.
 */
export type ProblemBehavior =
  | 'inline-fields' // show fieldErrors on the matching fields and focus the first invalid one
  | 'studio-conflict' // studio conflict dialog (Reload latest / Download my version)
  | 'refresh-task' // refresh the task and disable actions
  | 'reload-form' // reload the start form, keep values whose keys still exist
  | 'open-validation' // open the validation panel
  | 'acknowledge-warnings' // focus the warnings acknowledgement on the publish page
  | 'operator-message' // operator dialog message with the next allowed action
  | 'retry' // transient: offer Retry with the same idempotency key
  | 'not-found' // not-found page
  | 'forbidden' // inline "You don't have permission to ..."
  | 'message' // plain message

export interface ProblemContext {
  /** Verb phrase for FORBIDDEN, e.g. "approve this task". */
  action?: string
  /** Display name of the person who saved a newer draft (STALE_REVISION). */
  otherUserName?: string
  /** Fresh task snapshot after a conflict (TASK_*). */
  task?: Pick<TaskDetail, 'status' | 'assignee' | 'completedBy'> | undefined
  /** Display name of the current user, to tell "Claimed by you" from "Claimed by someone". */
  currentUserId?: string
}

export interface ProblemUi {
  code: ProblemCode | ClientProblemCode
  behavior: ProblemBehavior
  message: string
  retryable: boolean
}

type Entry = { behavior: ProblemBehavior; message: string | ((ctx: ProblemContext) => string); retryable?: boolean }

function taskConflictMessage(ctx: ProblemContext, fallback: string): string {
  const task = ctx.task
  if (!task) return fallback
  if (task.status === 'completed' || task.status === 'cancelled') {
    const who = task.completedBy?.displayName
    return task.status === 'cancelled'
      ? 'This task was cancelled.'
      : who
        ? `This task was completed by ${who}.`
        : 'This task was already completed.'
  }
  if (task.assignee) {
    return task.assignee.id === ctx.currentUserId ? 'You already claimed this task.' : `Claimed by ${task.assignee.displayName}.`
  }
  return fallback
}

/** Every server problem code has an entry; `satisfies` makes a missing code a compile error. */
const TABLE = {
  INTERNAL_ERROR: { behavior: 'retry', message: 'Something went wrong. Try again with the same request.', retryable: true },
  MALFORMED_REQUEST: { behavior: 'message', message: 'Something in the request could not be read. Reload the page and try again.' },
  IDEMPOTENCY_KEY_REQUIRED: { behavior: 'message', message: 'The request could not be sent safely. Reload the page and try again.' },
  INVALID_CURSOR: { behavior: 'message', message: 'The list changed while you were paging. Start again from the first page.' },
  UNAUTHENTICATED: { behavior: 'message', message: 'Your session has ended. Redirecting you to sign in.' },
  FORBIDDEN: { behavior: 'forbidden', message: (c) => `You don't have permission to ${c.action ?? 'do this'}.` },
  CSRF_INVALID: { behavior: 'message', message: 'Your session security token expired. Reload the page and try again.' },
  SELF_APPROVAL_DENIED: { behavior: 'message', message: "You can't approve your own request." },
  SEPARATION_OF_DUTIES: { behavior: 'message', message: 'You decided an earlier step of this request, so another person must decide this one.' },
  NOT_ELIGIBLE: { behavior: 'forbidden', message: "You're not eligible to act on this task." },
  NOT_FOUND: { behavior: 'not-found', message: "This doesn't exist or you don't have access to it." },
  IDEMPOTENCY_KEY_REUSED: { behavior: 'message', message: 'This request was already sent with different details. Reload the page and start again.' },
  DUPLICATE_KEY: { behavior: 'inline-fields', message: 'That key is already used. Choose a different one.' },
  WORKFLOW_VERSION_CONFLICT: { behavior: 'message', message: 'The workflow changed while you were looking at it. Reload and try again.' },
  RELEASE_NOT_ACTIVE: { behavior: 'reload-form', message: 'This form was updated. Review it and submit again.' },
  TASK_VERSION_CONFLICT: { behavior: 'refresh-task', message: (c) => taskConflictMessage(c, 'This task changed since you opened it. It has been refreshed.') },
  TASK_ALREADY_CLAIMED: { behavior: 'refresh-task', message: (c) => taskConflictMessage(c, 'Someone else claimed this task.') },
  TASK_NOT_CLAIMED_BY_CALLER: { behavior: 'refresh-task', message: 'This task is no longer assigned to you.' },
  TASK_NOT_OPEN: { behavior: 'refresh-task', message: (c) => taskConflictMessage(c, 'This task was already completed.') },
  RUN_NOT_ACTIVE: { behavior: 'operator-message', message: 'This request already finished or was cancelled. Reload to see its current state.' },
  RUN_BUSY: { behavior: 'operator-message', message: 'The request is being processed right now. Wait a moment, then try again.', retryable: true },
  RUN_SUSPENDED: { behavior: 'operator-message', message: 'This request is suspended by an operator. It can continue after it is resumed.' },
  JOB_LEASE_LOST: { behavior: 'message', message: 'A worker job is no longer held by that worker.' },
  JOB_NOT_OPEN: { behavior: 'message', message: 'That worker job has already ended.' },
  RUN_NOT_MIGRATABLE: { behavior: 'operator-message', message: 'This request cannot move to that release. The reasons are listed in the dialog.' },
  RUN_NOT_SUSPENDED: { behavior: 'operator-message', message: 'This request is not suspended.' },
  RUN_NOT_SUSPENDABLE: { behavior: 'operator-message', message: 'This request cannot be suspended in its current state.' },
  RUN_NOT_RECOVERABLE: { behavior: 'operator-message', message: 'This request cannot be recovered with retry. Reconcile the step or cancel the request.' },
  EFFECT_NOT_RECONCILED: { behavior: 'operator-message', message: 'The outcome of this step is unknown. Check the other system, reconcile the outcome, then retry.' },
  EFFECT_STATE_CONFLICT: { behavior: 'operator-message', message: 'This step changed in the meantime. Reload to see its current state.' },
  GROUP_REVISION_CONFLICT: { behavior: 'message', message: 'This group was changed by someone else. Reload and try again.' },
  STALE_REVISION: { behavior: 'studio-conflict', message: (c) => `A newer version was saved by ${c.otherUserName ?? 'someone else'}.` },
  PAYLOAD_TOO_LARGE: { behavior: 'message', message: 'This is too large to save. Remove some content and try again.' },
  VALIDATION_FAILED: { behavior: 'inline-fields', message: 'Check the highlighted fields and try again.' },
  WORKFLOW_INVALID: { behavior: 'open-validation', message: "This workflow has problems that must be fixed before it can be published." },
  WARNINGS_NOT_ACKNOWLEDGED: { behavior: 'acknowledge-warnings', message: 'Confirm that you have read the warnings before publishing.' },
  NO_ACTIVE_RELEASE: { behavior: 'message', message: "This workflow isn't published yet." },
  REASON_REQUIRED: { behavior: 'inline-fields', message: 'Add a reason so the requester knows what to change.' },
  CONNECTION_NOT_BOUND: { behavior: 'message', message: 'This connection has no address configured in this workspace yet.' },
  EGRESS_NOT_ALLOWED: { behavior: 'inline-fields', message: "That address isn't allowed by your organization's network policy." },
  OPERATION_NOT_TESTABLE: { behavior: 'message', message: 'This operation changes data in another system, so it cannot be tested from here.' },
  PRECONDITION_REQUIRED: { behavior: 'message', message: 'Reload the page before saving again.' },
  QUOTA_EXCEEDED: { behavior: 'message', message: 'Too many open requests for this workflow. Try again later.' },
  RATE_LIMITED: { behavior: 'retry', message: 'Too many requests in a short time. Wait a moment and try again.', retryable: true },
  DEPENDENCY_UNAVAILABLE: { behavior: 'retry', message: 'Service temporarily unavailable. Nothing was submitted.', retryable: true },
  AI_NOT_CONFIGURED: { behavior: 'message', message: 'AI assistance is not set up for this workspace. Start from a template or a blank workflow instead.' },
  AI_MODEL_NOT_ALLOWED: { behavior: 'message', message: 'That AI model is not allowed here. Choose another option or use the default.' },
  AI_OUTPUT_INVALID: { behavior: 'message', message: "The AI couldn't produce a valid workflow from that description. Add more detail, or start from a template." },
  AI_PROVIDER_UNAVAILABLE: { behavior: 'retry', message: 'The AI service is not responding right now. Nothing was created. Try again in a moment.', retryable: true },
  AI_BUDGET_EXCEEDED: { behavior: 'message', message: "This workspace has used this month's AI budget. Templates and manual design still work." },
  SOURCE_INVALID: { behavior: 'message', message: 'The source cannot be read. Check the selected format, file limits and migration report.' },
  ATTACHMENT_BLOCKED: { behavior: 'message', message: 'This file was blocked by the virus scan and cannot be downloaded. Ask the person who added it to upload a clean copy.' },
  ATTACHMENT_LIMIT_REACHED: { behavior: 'message', message: 'This request has reached its limit of files. Remove a file you no longer need, then add this one.' },
  MIGRATION_BLOCKED: { behavior: 'message', message: 'Some imported steps still need a decision. Resolve the open items in the report, then convert again.' },
} satisfies Record<ProblemCode, Entry>

const CLIENT_TABLE: Record<ClientProblemCode, Entry> = {
  NETWORK_ERROR: { behavior: 'retry', message: "Can't reach Orqix. Check your connection and try again.", retryable: true },
  UNKNOWN_ERROR: { behavior: 'message', message: 'Something went wrong. Try again, and contact support with the reference if it keeps happening.' },
}

/** Map a ProblemError to the behavior and message the UI must use. */
export function describeProblem(error: ProblemError, ctx: ProblemContext = {}): ProblemUi {
  const entry: Entry = (TABLE as Record<string, Entry>)[error.code] ?? CLIENT_TABLE[error.code as ClientProblemCode] ?? CLIENT_TABLE.UNKNOWN_ERROR
  const message = typeof entry.message === 'function' ? entry.message(ctx) : entry.message
  return { code: error.code, behavior: entry.behavior, message, retryable: entry.retryable ?? false }
}

/** Every code the table knows (used by tests to prove the mapping is exhaustive). */
export const MAPPED_PROBLEM_CODES = Object.keys(TABLE) as ProblemCode[]

/**
 * Field errors keyed by field name. Pointers are JSON Pointers into the request body, so
 * "/input/amount" (startRun) and "/amount" (fixtures) both address the field "amount".
 */
export function fieldErrorMap(error: ProblemError): Record<string, string> {
  const result: Record<string, string> = {}
  for (const fe of error.fieldErrors) {
    const key = fieldKeyFromPointer(fe.pointer)
    if (key && !(key in result)) result[key] = fe.message
  }
  return result
}

export function fieldKeyFromPointer(pointer: string): string {
  const parts = pointer.split('/').filter(Boolean)
  if (parts[0] === 'input') parts.shift()
  return (parts[0] ?? '').replace(/~1/g, '/').replace(/~0/g, '~')
}
