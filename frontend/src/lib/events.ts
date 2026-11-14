import type { RunEvent } from '@/api/types'
import { formatDay, formatTime, formatUtcDateTime } from './dates'
import { humanize } from './text'

/**
 * Business-language timeline text (docs/build/S7-frontend.md section 4 table).
 * Automated decision values/evidence are scoped by the server to request-data permissions (S21).
 * Evidence details use their own bounded renderer; ordinary wording uses labels, names and safe event fields.
 */
export interface EventContext {
  /** Display name of the run initiator. */
  initiatorName: string
  /** nodeId -> step label, learned from tasks, current steps and effects. */
  labels: Record<string, string>
  /** group key -> display name. */
  groupNames: Record<string, string>
  now?: Date
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** S143: "skipped the step X" / "sent the request back to Y" / "moved the request to Y". */
function moveText(data: Record<string, unknown>, done = false): string {
  const from = str(data.fromLabel) || str(data.from)
  const to = str(data.toLabel) || str(data.to)
  if (data.kind === 'skip') return `${done ? 'skipped' : 'skip'} the step ${from}`
  if (data.kind === 'rerun') return `${done ? 'sent' : 'send'} the request back to ${to}`
  return `${done ? 'moved' : 'move'} the request from ${from} to ${to}`
}

export function stepLabel(event: RunEvent, ctx: EventContext): string {
  // nodeLabel is the step's name as written in the workflow; the rest are fallbacks for older servers.
  return str(event.nodeLabel) ?? str(event.data.label) ?? (event.nodeId ? (ctx.labels[event.nodeId] ?? humanize(event.nodeId)) : 'this step')
}

function groupName(key: string | undefined, ctx: EventContext): string {
  if (!key) return 'the group'
  return ctx.groupNames[key] ?? humanize(key)
}

function actorName(event: RunEvent, ctx: EventContext): string {
  return event.actor?.displayName ?? str(event.data.actor) ?? ctx.initiatorName ?? 'Someone'
}

/** { route: "financeReview" } -> "Route is financeReview" */
export function outputsInWords(outputs: unknown): string {
  if (typeof outputs !== 'object' || outputs === null) return 'decision made'
  const parts = Object.entries(outputs as Record<string, unknown>).map(([k, v]) => `${humanize(k)} is ${String(v)}`)
  return parts.length > 0 ? parts.join(', ') : 'decision made'
}

/** Outputs on the timeline: values are words too, so "financeReview" reads "finance review". */
function outputsForTimeline(outputs: unknown): string {
  if (typeof outputs !== 'object' || outputs === null) return 'decision made'
  const parts = Object.entries(outputs as Record<string, unknown>).map(([k, v]) => `${humanize(k)} is ${typeof v === 'string' ? humanize(v).toLowerCase() : String(v)}`)
  return parts.length > 0 ? parts.join(', ') : 'decision made'
}

function retryTime(value: unknown, now: Date): string | undefined {
  const text = str(value)
  if (!text) return undefined
  const d = new Date(text)
  if (Number.isNaN(d.getTime())) return undefined
  const sameDay = d.toDateString() === now.toDateString()
  return sameDay ? formatTime(d) : `${formatDay(d)} ${formatTime(d)}`
}

/** Timeline marker color: neutral for ordinary events, status hues for outcomes, danger only for problems. */
export type EventTone = 'neutral' | 'waiting' | 'success' | 'rejected' | 'cancelled' | 'danger'

export function eventTone(event: Pick<RunEvent, 'type' | 'data'>): EventTone {
  switch (event.type) {
    case 'TASK_CREATED':
    case 'WAIT_STARTED':
    case 'SIGNAL_WAIT_STARTED':
    case 'CHILD_STARTED':
      return 'waiting'
    case 'TASK_DECIDED':
    case 'TASK_DECIDED_BY_AI':
      return event.data.action === 'reject' ? 'rejected' : 'success'
    case 'EFFECT_SUCCEEDED':
    case 'EFFECT_RECONCILED':
      return 'success'
    case 'RUN_COMPLETED':
      return event.data.outcomeKind === 'failed' ? 'danger' : event.data.outcomeKind === 'rejected' ? 'rejected' : 'success'
    case 'RUN_CANCELLED':
      return 'cancelled'
    case 'RUN_SUSPENDED':
    case 'EXTERNAL_JOB_PUBLISHED':
    case 'EXTERNAL_JOB_CALLBACK_REGISTERED':
    case 'EXTERNAL_JOB_RETRY_SCHEDULED':
    case 'EFFECT_DEFERRED':
      return 'waiting'
    case 'EFFECT_FAILED':
    case 'EFFECT_UNKNOWN':
    case 'STAGE_OVERDUE':
    case 'RUN_NEEDS_ATTENTION':
    case 'CHILD_NEEDS_ATTENTION':
    case 'EXTERNAL_JOB_FAILED':
      return 'danger'
    default:
      return 'neutral'
  }
}

/** What went wrong with a call to another system, in the terms a requester or approver can act on. */
export type FailureKind = 'not-sent' | 'timed-out' | 'rejected' | 'rate-limited' | 'server-error' | 'unknown' | 'unspecified'

const NOT_SENT_CODES = new Set(['CONNECT_FAILED', 'CONNECT_TIMEOUT', 'CONNECTION_UNAVAILABLE', 'EGRESS_NOT_ALLOWED', 'INVALID_REQUEST'])
const TIMED_OUT_CODES = new Set(['TIMEOUT', 'CONNECTION_RESET', 'INTERRUPTED', 'CLIENT_FAILURE'])

/**
 * Classifies an EFFECT_FAILED event from its `classification` (retryable, permanent, unknown), `httpStatus` and
 * `errorCode` (S5 sections 6.2 and 8). The HTTP status wins over the code, because the code for a status is just
 * "HTTP_<status>" or UNEXPECTED_STATUS. Events without any of these (older servers) stay "unspecified".
 */
export function classifyFailure(data: Record<string, unknown>): FailureKind {
  const code = str(data.errorCode)?.toUpperCase()
  const status = num(data.httpStatus)
  if (status === 429 || code === 'RATE_LIMITED') return 'rate-limited'
  if (status === 408) return 'timed-out'
  if (status !== undefined && status >= 500) return 'server-error'
  if (data.classification === 'unknown') return 'unknown'
  if (status !== undefined && status >= 400) return 'rejected'
  if (code && NOT_SENT_CODES.has(code)) return 'not-sent'
  if (code && TIMED_OUT_CODES.has(code)) return 'timed-out'
  if (code === 'RESPONSE_INVALID' || code === 'RESPONSE_TOO_LARGE' || code === 'UNEXPECTED_STATUS') return 'unknown'
  return 'unspecified'
}

function failureText(kind: FailureKind, label: string, data: Record<string, unknown>): string {
  const status = num(data.httpStatus)
  switch (kind) {
    case 'not-sent':
      return `${label} could not be sent. The other system could not be reached.`
    case 'timed-out':
      return `${label} timed out. The other system did not answer in time.`
    case 'rejected':
      return `${label} was rejected by the other system${status !== undefined ? ` (HTTP ${status})` : ''}.`
    case 'rate-limited':
      return `${label} was rate limited: the other system asked us to slow down.`
    case 'server-error':
      return `${label} hit an error in the other system${status !== undefined ? ` (HTTP ${status})` : ''}.`
    case 'unknown':
      return `The outcome of ${label} is unknown. The other system may have completed it.`
    case 'unspecified':
      return `${label} did not respond.`
  }
}

export function describeEvent(event: RunEvent, ctx: EventContext): string {
  const label = stepLabel(event, ctx)
  const now = ctx.now ?? new Date()
  switch (event.type) {
    case 'RUN_STARTED': {
      const version = num(event.data.releaseNumber)
      const who = event.actor?.displayName ?? ctx.initiatorName
      return `${who} submitted the request${version !== undefined ? ` (version ${version})` : ''}`
    }
    case 'TASK_CREATED':
      return `Waiting for ${groupName(str(event.data.candidateGroup), ctx)}: ${label}`
    case 'TASK_TIMED_OUT':
      return `The time limit for ${label} passed; the approval was cancelled`
    case 'EXTERNAL_JOB_PUBLISHED':
      return `${label}: waiting for an outside worker (${str(event.data.topic) ?? 'job'})`
    case 'EXTERNAL_JOB_CALLBACK_REGISTERED':
      return `${label}: waiting for an asynchronous result`
    case 'EXTERNAL_JOB_COMPLETED':
      return `An outside worker completed ${label}`
    case 'EXTERNAL_JOB_RETRY_SCHEDULED': {
      const message = str(event.data.message)
      return `${label}: the worker did not finish, so the job is offered again${message ? ` (${message})` : ''}`
    }
    case 'EXTERNAL_JOB_FAILED': {
      const message = str(event.data.message)
      return `The outside worker could not complete ${label}${message ? `: ${message}` : ''}`
    }
    case 'WAIT_STARTED': {
      const message = str(event.data.message)
      if (message) return `${label}: waiting for the message ${message}`
      // A wait until a date has no duration; it reports the moment it ends (UTC).
      const until = str(event.data.until)
      return str(event.data.duration) || !until ? `${label}: waiting for the timer` : `${label}: waiting until ${formatUtcDateTime(until)}`
    }
    case 'WAIT_ENDED': {
      const message = str(event.data.message)
      if (message && event.data.outcome === 'timedOut') return `${label}: the message ${message} did not arrive in time`
      if (message) return `${label}: the message ${message} arrived${event.data.buffered === true ? ' (it was received before the wait started)' : ''}`
      return `${label}: the wait finished`
    }
    case 'SIGNAL_WAIT_STARTED':
      return `${label}: waiting for the signal ${str(event.data.signal) ?? ''}`.trimEnd()
    case 'SIGNAL_SENT': {
      const signal = str(event.data.signal) ?? ''
      // An operator sends it with a reason; a "Send a signal" step sends it without an actor.
      if (event.actor) {
        const reason = str(event.data.reason)
        return `${actorName(event, ctx)} sent the signal ${signal}${reason ? `: ${reason}` : ''}`
      }
      return `${label}: sent the signal ${signal}`.trimEnd()
    }
    case 'SIGNAL_RECEIVED':
      return `${label}: the signal ${str(event.data.signal) ?? ''} arrived`.replace('  ', ' ')
    case 'MESSAGE_DISCARDED':
      return `${label}: a message ${str(event.data.message)} that arrived earlier did not fit and was ignored`
    case 'GUEST_LINK_SENT':
      return `${label}: a one-time response link was emailed${str(event.data.domain) ? ` to an address at ${str(event.data.domain)}` : ''}`
    case 'GUEST_LINK_REFUSED':
      return event.data.reason === 'domainNotApproved'
        ? `${label}: the response link was not emailed because ${str(event.data.domain) ?? 'the address domain'} is not an approved guest domain`
        : `${label}: the response link was not emailed (no usable address)`
    case 'GUEST_RESPONSE_SUBMITTED': {
      const files = typeof event.data.fileCount === 'number' ? event.data.fileCount : 0
      return `${label}: the guest sent their response${files ? ` with ${files} file${files === 1 ? '' : 's'}` : ''}`
    }
    case 'CHILD_STARTED': {
      const name = str(event.data.workflowName)
      const reference = str(event.data.childReference)
      return name && reference ? `Started ${name} ${reference}` : `${label}: a child request was started`
    }
    case 'CHILD_ENDED': {
      const name = str(event.data.workflowName)
      const outcome = str(event.data.outcome)
      return name ? `${name} ${str(event.data.childReference) ?? ''} finished${outcome ? `: ${outcome}` : ''}`.replace('  ', ' ') : `${label}: child request finished${outcome ? ` (${outcome})` : ''}`
    }
    case 'CHILD_NEEDS_ATTENTION':
      return `${str(event.data.childReference) ?? 'A child request'} needs attention`
    case 'TASK_CLAIMED':
      return `${actorName(event, ctx)} picked up ${label}`
    case 'TASK_DISPATCHED':
      return `${str(event.data.assignee) ?? 'Someone'} was assigned ${label}${str(event.data.rule) ? `: ${str(event.data.rule)}` : ''}`
    case 'TASK_ESCALATED': {
      // CG-T04 (S141): one level of the step's escalation policy; the due date is not changed.
      const level = Number(event.data.level ?? 0)
      const hours = Number(event.data.afterHours ?? 0)
      const waited = `${hours} ${event.data.workingTime ? 'working ' : ''}hour${hours === 1 ? '' : 's'}`
      const group = groupName(str(event.data.group), ctx)
      const count = Number(event.data.recipients ?? 0)
      const people = `${count} ${count === 1 ? 'person' : 'people'}`
      switch (str(event.data.outcome)) {
        case 'notified': return `Escalation level ${level}: after ${waited}, ${people} in ${group} were told about ${label}`
        case 'reassigned': return `Escalation level ${level}: after ${waited}, ${label} moved to ${group}`
        case 'alerted': return `Escalation level ${level}: after ${waited}, operators were alerted about ${label} (${people})`
        case 'noEligiblePerson': return `Escalation level ${level}: ${label} stayed in its queue because nobody in ${group} may decide it`
        case 'noRecipients': return `Escalation level ${level}: nobody in ${group} could be told about ${label}`
        case 'noOperators': return `Escalation level ${level}: no operator could be alerted about ${label}`
        default: return `Escalation level ${level} for ${label} did not act (${str(event.data.outcome) ?? 'unknown'})`
      }
    }
    case 'TASK_PROPOSED_UNAVAILABLE': {
      // S104: the person named on the request item cannot decide it; the email is never in the event.
      const item = str(event.data.itemLabel)
      return `The person proposed for ${label}${item ? ` (${item})` : ''} cannot decide it, so it waits in the group queue`
    }
    case 'TASK_RELEASED':
      return `${actorName(event, ctx)} returned ${label} to ${groupName(str(event.data.candidateGroup), ctx)}`
    case 'TASK_REASSIGNED': {
      const to = str(event.data.to)
      return `${actorName(event, ctx)} reassigned ${label}${to ? ` to ${to}` : ' to the group'}`
    }
    case 'TASK_PRIORITY_CHANGED':
      return `${actorName(event, ctx)} changed ${label} priority from ${event.data.from} to ${event.data.to}${str(event.data.reason) ? `: ${str(event.data.reason)}` : ''}`
    case 'TASK_SENT_BACK':
      return `${actorName(event, ctx)} sent ${label} back to the requester${str(event.data.reason) ? `: ${str(event.data.reason)}` : ''}`
    case 'TASK_SEND_BACK_WITHDRAWN':
      return `${actorName(event, ctx)} withdrew the send-back of ${label}`
    case 'TASK_SEND_BACK_ANSWERED':
      return `${actorName(event, ctx)} answered the send-back of ${label}`
    case 'INPUT_CORRECTED':
      return `${actorName(event,ctx)} corrected ${event.data.fields} request field${event.data.fields===1 ? '' : 's'}${str(event.data.reason) ? `: ${str(event.data.reason)}` : ''}`
    case 'TASK_DECIDED': {
      const action = event.data.action === 'reject' ? 'rejected' : 'approved'
      return `${actorName(event, ctx)} ${action} ${label}`
    }
    // S152: the AI reviewer of an approval step.
    case 'TASK_DECIDED_BY_AI': {
      const action = event.data.action === 'reject' ? 'rejected' : 'approved'
      const confidence = num(event.data.confidence)
      return `Decided by AI reviewer: ${action} ${label}${confidence !== undefined ? ` (confidence ${confidence})` : ''}`
    }
    case 'AI_REVIEW_QUEUED':
      return `AI reviewer started reviewing ${label}`
    case 'AI_REVIEW_SUGGESTED': {
      const suggestion = str(event.data.recommendation)
      return `AI reviewer suggests to ${suggestion ?? 'decide'} ${label}; a person decides`
    }
    case 'AI_REVIEW_UNAVAILABLE':
      return `AI reviewer could not review ${label}; a person decides`
    case 'DECISION_EVALUATED':
      return `${label}: ${outputsForTimeline(event.data.outputs)}`
    case 'EFFECT_SUCCEEDED': {
      // An AI agent step carries a business sentence built from its connection definition (S13).
      const summary = str(event.data.summary)
      if (summary) return summary
      // An adapter step (S73). In this build the only transport is the local test stub, so say so plainly.
      if (str(event.data.adapterKind)) {
        return event.data.outcome === 'stubbed'
          ? `${label}: accepted by the local test stub of the adapter ${str(event.data.adapterKey) ?? ''}; nothing was sent outside Orqix`.replace(' ;', ';')
          : `${label} completed through the adapter ${str(event.data.adapterKey) ?? ''}`.trimEnd()
      }
      const ref = str(event.data.remoteReference)
      return `${label} completed${ref ? ` (reference ${ref})` : ''}`
    }
    case 'EFFECT_FAILED': {
      const attempt = num(event.data.attempt)
      const max = num(event.data.maxAttempts)
      const next = retryTime(event.data.nextRetryAt, now)
      const attemptText = attempt !== undefined ? ` Attempt ${attempt}${max !== undefined ? ` of ${max}` : ''}.` : ''
      return `${failureText(classifyFailure(event.data), label, event.data)}${attemptText}${next ? ` Next retry at ${next}.` : ''}`
    }
    case 'EFFECT_DEFERRED': {
      // CG-W04 (S131): the call waited for its connection; no attempt was used.
      const next = retryTime(event.data.until, now)
      const why = str(event.data.reason) === 'RATE_LIMITED' ? 'the connection request limit was reached' : 'calls to the system are paused after repeated failures'
      return `${label} is waiting because ${why}. No attempt was used${next ? `; it tries again at ${next}` : ''}.`
    }
    case 'EFFECT_UNKNOWN':
    case 'RUN_NEEDS_ATTENTION':
      return `${label} needs attention — an operator has been alerted.`
    case 'OPERATOR_RETRY':
      return `${actorName(event, ctx)} retried ${label}`
    case 'STEP_FAILED':
      return `${label} took the workflow's failure path`
    case 'EFFECT_RECONCILED':
      return `${actorName(event, ctx)} confirmed the outcome of ${label}`
    case 'NOTIFICATION_CREATED':
      return 'The requester was notified'
    case 'ATTACHMENT_ADDED': {
      const name = str(event.data.name)
      return name ? `${actorName(event, ctx)} added the file ${name}` : `${actorName(event, ctx)} added a file`
    }
    case 'ATTACHMENT_REMOVED': {
      const name = str(event.data.name)
      return name ? `${actorName(event, ctx)} removed the file ${name}` : `${actorName(event, ctx)} removed a file`
    }
    case 'ATTACHMENT_QUARANTINED': {
      const name = str(event.data.name)
      return `${actorName(event, ctx)} quarantined ${name ? `the file ${name}` : 'a file'} for review`
    }
    case 'ATTACHMENT_RELEASED': {
      const name = str(event.data.name)
      return `${actorName(event, ctx)} released ${name ? `the file ${name}` : 'a file'} from quarantine`
    }
    case 'ATTACHMENT_PURGED':
      return 'A file was removed automatically at the end of its retention period'
    case 'STAGE_ENTERED': {
      const index = num(event.data.index)
      const total = num(event.data.total)
      return `Stage ${index !== undefined && total !== undefined ? `${index} of ${total}` : 'started'}: ${str(event.data.label) ?? 'next stage'}`
    }
    case 'STAGE_COMPLETED':
      return `Stage completed: ${str(event.data.label) ?? 'stage'}${event.data.late === true ? ' (after its time limit)' : ''}`
    case 'STAGE_OVERDUE':
      return `Overdue: the stage ${str(event.data.label) ?? ''} passed its time limit`.replace('stage  passed', 'stage passed')
    case 'RUN_COMPLETED':
      return `Finished: ${str(event.data.outcome) ?? 'Completed'}`
    case 'RUN_CANCELLED': {
      const reason = str(event.data.reason)
      return `${actorName(event, ctx)} cancelled the request${reason ? `: ${reason}` : ''}`
    }
    case 'RUN_SUSPENDED': {
      const reason = str(event.data.reason)
      return `${actorName(event, ctx)} suspended the request${reason ? `: ${reason}` : ''}`
    }
    case 'RUN_MIGRATED': {
      const reason = str(event.data.reason)
      return `${actorName(event, ctx)} moved the request from version ${String(event.data.fromReleaseNumber ?? '?')} to version ${String(event.data.toReleaseNumber ?? '?')}${reason ? `: ${reason}` : ''}`
    }
    case 'RUN_MOVE_REQUESTED': {
      const reason = str(event.data.reason)
      return `${actorName(event, ctx)} asked to ${moveText(event.data)}; a second operator must approve${reason ? `: ${reason}` : ''}`
    }
    case 'RUN_MOVED': {
      const reason = str(event.data.reason)
      const approved = event.data.approvedBy ? ' (approved by a second operator)' : ''
      const reused = Array.isArray(event.data.reusedResults) && event.data.reusedResults.length > 0 ? '. Recorded call results were reused; nothing was sent again' : ''
      return `${actorName(event, ctx)} ${moveText(event.data, true)}${approved}${reused}${reason ? `: ${reason}` : ''}`
    }
    case 'RUN_MOVE_REJECTED':
      return `${actorName(event, ctx)} ${event.data.state === 'withdrawn' ? 'withdrew' : 'rejected'} a requested move${str(event.data.note) ? `: ${str(event.data.note)}` : ''}`
    case 'RUN_MOVE_LAPSED':
      return `A requested move was not run: ${str(event.data.why) || 'the request changed'}`
    case 'RUN_PROJECTION_REPAIRED': {
      const reason = str(event.data.reason)
      return `${actorName(event, ctx)} rebuilt the open approval steps from the workflow engine${reason ? `: ${reason}` : ''}`
    }
    case 'RUN_RESUMED': {
      const reason = str(event.data.reason)
      return `${actorName(event, ctx)} resumed the request${reason ? `: ${reason}` : ''}`
    }
    default:
      return humanize(event.type)
  }
}

/**
 * The next allowed action for an operator, per attention reason (S5 section 6.3). The server's own message says what
 * happened; this says what to do. An unknown outcome is never described as a plain failure.
 */
export function attentionNextStep(reason: string): string {
  switch (reason) {
    case 'RETRIES_EXHAUSTED':
      return 'Retry the step once the other system is available again.'
    case 'EFFECT_UNKNOWN':
      return 'Check the other system, record what you found with Reconcile outcome, then continue.'
    case 'EFFECT_REJECTED':
      return 'Fix the connection or the details of the request, then retry the step.'
    case 'RESPONSE_INVALID':
      return 'Check what the other system answered and what the connection expects, then retry the step.'
    case 'CONNECTION_UNAVAILABLE':
      return 'Fix or enable the connection address, then retry the step.'
    case 'POLICY_DENIED':
      return 'Restore access, or cancel the request.'
    case 'RUN_WAITING_ON_NOTHING':
      return 'Inspect the request and engine history. Cancel it if it cannot safely continue.';
    default:
      return 'Ask engineering to look at it, then retry the step when it is fixed.'
  }
}

/**
 * Timeline entries for display. EFFECT_UNKNOWN and RUN_NEEDS_ATTENTION read the same in business language
 * ("... needs attention"), so the second one directly after the first is folded into it.
 */
export function timelineEvents(events: RunEvent[]): RunEvent[] {
  const shown: RunEvent[] = []
  for (const e of events) {
    const prev = shown[shown.length - 1]
    if (e.type === 'RUN_NEEDS_ATTENTION' && prev?.type === 'EFFECT_UNKNOWN' && prev.nodeId === e.nodeId) continue
    shown.push(e)
  }
  return shown
}
