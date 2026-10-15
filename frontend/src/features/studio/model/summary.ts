import { formatCalendarDate, isCalendarDate } from '@/lib/dates'
import { chipFor, fieldInfo, type ConnectionLookup } from './fieldRefs'
import { nodeById } from './graph'
import { KINDS, ROUTE_LABEL } from './kinds'
import { conditionTerms, isAtomicCondition, type Condition, type Definition, type Operator, type Transition, type WorkflowNode } from './types'

export const OPERATOR_LABEL: Record<Operator, string> = { eq: 'is', ne: 'is not', gte: 'at least', gt: 'greater than', lt: 'less than', lte: 'at most' }

/** "Request → Amount (USD) is at least 5000" */
export function describeCondition(def: Definition, c: Condition, lookup?: ConnectionLookup): string {
  if (!isAtomicCondition(c)) return conditionTerms(c).map(term => `(${describeCondition(def, term, lookup)})`).join('all' in c ? ' and ' : ' or ')
  return `${chipFor(def, c.fieldRef, lookup)} ${OPERATOR_LABEL[c.operator]} ${String(c.value)}`
}

/** Short route text used on edges and in the Steps tab. */
export function routeText(def: Definition, t: Transition, lookup?: ConnectionLookup): string {
  switch (t.on) {
    case 'approved':
      return 'Approved'
    case 'rejected':
      return 'Rejected'
    case 'timedOut':
      return 'Timed out'
    case 'failed':
      return 'Failed'
    case 'default':
      return t.label ? `Otherwise (${t.label})` : 'Otherwise'
    case 'condition':
      return t.label || (t.condition && conditionTerms(t.condition).length ? `When ${describeCondition(def, t.condition, lookup)}` : 'When (condition not set)')
    case 'next':
      return t.label ?? ''
  }
}

/** Label used in a route select: "When approved", "Otherwise", "Next". */
export function routeSlotLabel(def: Definition, t: Transition | undefined, on: Transition['on'], lookup?: ConnectionLookup): string {
  if (on === 'approved') return 'When approved'
  if (on === 'rejected') return 'When rejected'
  if (on === 'default') return 'Otherwise'
  if (on === 'condition' && t) return t.label ? `When ${t.label}` : t.condition?.fieldRef ? `When ${describeCondition(def, t.condition, lookup)}` : 'When (condition not set)'
  return ROUTE_LABEL[on]
}

/** One-line subtitle for a node card. */
export function nodeSubtitle(def: Definition, node: WorkflowNode, groupNames: Record<string, string> = {}): string {
  const c = node.config
  switch (node.type) {
    case 'start': {
      const count = Object.keys(def.inputSchema.properties).length
      return `${count} ${count === 1 ? 'question' : 'questions'}`
    }
    case 'approval': {
      const group = c.candidateGroupRef as string | undefined
      return group ? `${groupNames[group] ?? group}` : 'No approver group yet'
    }
    case 'decision': {
      const table = def.decisions.find((d) => d.key === c.decisionRef)
      const ref = table?.rows[0]?.when.fieldRef
      return ref ? `Based on ${fieldInfo(def, ref)?.label ?? ref}` : 'Rule table'
    }
    case 'branch':
      return `${def.transitions.filter((t) => t.from === node.id).length} paths`
    case 'http':
      return c.operation ? `${c.connectionRef as string} · ${c.operation as string}` : 'No operation chosen'
    case 'generateDocument':
      return `${String(c.format ?? 'pdf').toUpperCase()} document ? ${String(c.fileName ?? 'No file name')}`
    case 'notification':
      return (c.message as string) || 'No message yet'
    case 'parallelSplit':
      return `${def.transitions.filter((t) => t.from === node.id).length} parallel branches`
    case 'parallelJoin':
      return 'Continue when all branches finish'
    case 'wait':
      return waitSummary(def, c)
    case 'waitForMessage':
      return messageSummary(def, c)
    case 'startWorkflow':
      return (c.workflowRef as string) || 'No workflow chosen'
    case 'end':
      return endSubtitle((c.businessOutcome as string) || 'No outcome', c.outcomeKind === 'rejected' ? 'rejected' : c.outcomeKind === 'failed' ? 'failed' : 'success')
  }
}

/** "Finish · Rejected": the outcome name, plus "Rejected" only when the name does not already say so. */
function endSubtitle(outcome: string, kind: 'success' | 'rejected' | 'failed'): string {
  return `Finish · ${outcome}${kind === 'rejected' && !/reject/i.test(outcome) ? ' · Rejected' : kind === 'failed' && !/fail/i.test(outcome) ? ' · Failed' : ''}`
}

/** Time of day (UTC) at which a "wait until a date" step ends when none is chosen (S12 section 10). */
export const DEFAULT_WAIT_TIME = '09:00'
/** The latest date a wait may be asked to wait for: one year from today. */
export const WAIT_HORIZON_DAYS = 366

/** "Waits until 14 Oct 2026, 09:00 UTC." for a chosen date, otherwise a description of the rule. */
export function dateWaitPreview(label: string | undefined, time: string, date?: string): string {
  const at = `${time || DEFAULT_WAIT_TIME} UTC`
  if (date && isCalendarDate(date)) return `Waits until ${formatCalendarDate(date)}, ${at}.`
  return `Waits until the date in "${label ?? 'the chosen question'}", at ${at}.`
}

/** "payment-received · Order number" on the card of a "Wait for a message" step. */
function messageSummary(def: Definition, c: Record<string, unknown>): string {
  const name = (c.message as string) || 'No message name'
  const field = c.correlationField as string | undefined
  return field ? `${name} · ${fieldInfo(def, field)?.label ?? field}` : `${name} · no field chosen`
}

/** "Waits until a message named payment-received arrives for this request's Order number." (S16) */
export function messageWaitPreview(def: Definition, c: Record<string, unknown>, lookup?: ConnectionLookup): string {
  const name = (c.message as string) || '(no name yet)'
  const field = c.correlationField as string | undefined
  const label = field ? fieldInfo(def, field, lookup)?.label ?? field : undefined
  const subject = label ? `this request's ${label}` : 'the request (choose which field identifies it)'
  const limit = typeof c.timeoutAfter === 'string' && c.timeoutAfter ? ` If nothing arrives in time, the request follows Timed out.` : ''
  return `Waits until a message named ${name} arrives for ${subject}.${limit}`
}

function waitSummary(def: Definition, c: Record<string, unknown>): string {
  const until = c.untilField as string | undefined
  if (until) return `Until ${fieldInfo(def, until)?.label ?? 'a date'}, ${(c.atTime as string) || DEFAULT_WAIT_TIME} UTC`
  return (c.duration as string) || 'No duration chosen'
}

/** Accessible name of a node, e.g. "Approval: Review request, 2 issues". */
export function nodeAriaLabel(node: WorkflowNode, issueCount: number): string {
  const issues = issueCount === 0 ? '' : `, ${issueCount} ${issueCount === 1 ? 'issue' : 'issues'}`
  return `${KINDS[node.type].caption}: ${node.label}${issues}`
}

export function targetLabel(def: Definition, id: string): string {
  return nodeById(def, id)?.label ?? id
}
