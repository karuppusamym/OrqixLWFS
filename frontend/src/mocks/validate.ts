import type { ValidationIssue } from '@/api/types'
import { isCalendarDate } from '@/lib/dates'
import { allFields, availableFields, parseRef } from '@/features/studio/model/fieldRefs'
import { hasCycle, incoming, nodeById, outgoing, reachableFrom, startNode } from '@/features/studio/model/graph'
import { conditionTerms, isAtomicCondition, type Condition, type Definition, type Literal, type WorkflowNode } from '@/features/studio/model/types'

/**
 * A faithful-enough subset of the compiler rule catalog (docs/build/S4-workflow-compiler.md section 5)
 * so mock mode shows real validation behavior: codes, severities, JSON Pointer paths and node ids.
 * The server remains the only authority in production.
 */
export interface ValidationContext {
  groups: string[]
  connections: Array<{
    key: string
    versions: number[]
    bound: boolean
    operations: Record<string, { requestRequired: string[]; requestProps: string[]; responseProps: string[]; maxTimeoutSeconds: number }>
  }>
  isTestWorkspace: boolean
  /** Keys of the workflows with an active release (they can be started as a child). */
  publishedWorkflows?: string[]
}

type Issue = ValidationIssue

function issue(code: string, severity: 'blocker' | 'warning', message: string, path: string, nodeId?: string, transitionId?: string): Issue {
  return { code, severity, message, path, nodeId: nodeId ?? null, transitionId: transitionId ?? null }
}

const PLACEHOLDER_LABEL = /^(step|task|node)\s*\d*$/i

/** Minutes of a day/hour/minute ISO duration, or null when it is not one. */
function durationMinutes(text: string): number | null {
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(text)
  if (!m || text === 'P' || text.endsWith('T')) return null
  return Number(m[1] ?? 0) * 1440 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0)
}

function isNumericRange(op: string): boolean {
  return op === 'gte' || op === 'lt'
}

function interval(c: Condition): { lo: number; loOpen: boolean; hi: number; hiOpen: boolean } | null {
  if (typeof c.value !== 'number') return null
  if (c.operator === 'eq') return { lo: c.value, loOpen: false, hi: c.value, hiOpen: false }
  if (c.operator === 'gte') return { lo: c.value, loOpen: false, hi: Infinity, hiOpen: true }
  return { lo: -Infinity, loOpen: true, hi: c.value, hiOpen: true }
}

function overlaps(a: Condition, b: Condition): boolean {
  if (!isAtomicCondition(a) || !isAtomicCondition(b)) {
    const termsA = conditionTerms(a), termsB = conditionTerms(b)
    return !termsA.some(x => termsB.some(y => x.fieldRef === y.fieldRef && !overlaps(x, y)))
  }
  if (a.fieldRef !== b.fieldRef) return false
  const ia = interval(a)
  const ib = interval(b)
  if (ia && ib) {
    const lo = Math.max(ia.lo, ib.lo)
    const hi = Math.min(ia.hi, ib.hi)
    if (lo < hi) return true
    if (lo > hi) return false
    const loClosed = (ia.lo === lo ? !ia.loOpen : true) && (ib.lo === lo ? !ib.loOpen : true)
    const hiClosed = (ia.hi === hi ? !ia.hiOpen : true) && (ib.hi === hi ? !ib.hiOpen : true)
    return loClosed && hiClosed
  }
  return a.operator === 'eq' && b.operator === 'eq' && a.value === b.value
}

export function validateDefinition(def: Definition, ctx: ValidationContext): Issue[] {
  const issues: Issue[] = []
  const idxOf = (id: string) => def.nodes.findIndex((n) => n.id === id)
  const nodePath = (n: WorkflowNode, tail = '') => `/nodes/${idxOf(n.id)}${tail}`

  // Structure ---------------------------------------------------------------------------------------
  const ids = new Map<string, number>()
  for (const n of def.nodes) ids.set(n.id, (ids.get(n.id) ?? 0) + 1)
  for (const t of def.transitions) ids.set(t.id, (ids.get(t.id) ?? 0) + 1)
  for (const [id, count] of ids) if (count > 1) issues.push(issue('WF_DUPLICATE_ID', 'blocker', `The ID "${id}" is used more than once.`, '/nodes'))

  const starts = def.nodes.filter((n) => n.type === 'start')
  if (starts.length !== 1) issues.push(issue('WF_START_COUNT', 'blocker', 'A workflow needs exactly one request form (start step).', '/nodes'))
  if (!def.nodes.some((n) => n.type === 'end')) issues.push(issue('WF_NO_END', 'blocker', 'Add at least one Finish step.', '/nodes'))

  def.transitions.forEach((t, i) => {
    if (!nodeById(def, t.from) || !nodeById(def, t.to)) issues.push(issue('WF_TRANSITION_UNKNOWN_NODE', 'blocker', 'A connection points at a step that does not exist.', `/transitions/${i}`, undefined, t.id))
  })

  for (const n of def.nodes) {
    const out = outgoing(def, n.id)
    const count = (on: string) => out.filter((t) => t.on === on).length
    if (n.type === 'start' && incoming(def, n.id).length > 0) issues.push(issue('WF_START_INCOMING', 'blocker', 'Nothing can lead back into the request form.', nodePath(n), n.id))
    if (n.type === 'end' && out.length > 0) issues.push(issue('WF_END_OUTGOING', 'blocker', `"${n.label}" ends the workflow, so it cannot lead anywhere.`, nodePath(n), n.id))
    if (n.type === 'approval') {
      const limited = typeof n.config.timeoutAfter === 'string'
      if (count('approved') !== 1 || count('rejected') !== 1 || out.length !== (limited ? 3 : 2) || (limited && count('timedOut') !== 1))
        issues.push(issue(limited && count('timedOut') !== 1 ? 'WF_TIMEOUT_ROUTE' : 'WF_APPROVAL_ROUTES', 'blocker', limited ? `"${n.label}" needs one route each for Approved, Rejected and when nobody decides in time.` : `"${n.label}" needs exactly one route for Approved and one for Rejected.`, nodePath(n), n.id))
    } else if (n.type === 'branch') {
      if (count('default') !== 1) issues.push(issue('WF_BRANCH_DEFAULT', 'blocker', `"${n.label}" needs exactly one Otherwise route.`, nodePath(n), n.id))
      if (count('condition') < 1) issues.push(issue('WF_BRANCH_NO_CONDITION', 'blocker', `"${n.label}" needs at least one route with a condition.`, nodePath(n), n.id))
      if (out.some((t) => t.on !== 'condition' && t.on !== 'default')) issues.push(issue('WF_OUTGOING_INVALID', 'blocker', `"${n.label}" has a route that does not belong on a path choice.`, nodePath(n), n.id))
      const targets = new Set(out.map((t) => t.to))
      if (out.length > 1 && targets.size === 1) issues.push(issue('WF_BRANCH_SINGLE_TARGET', 'warning', `All routes of "${n.label}" lead to the same step, so the choice has no effect.`, nodePath(n), n.id))
    } else if (n.type === 'waitForMessage') {
      const limited = typeof n.config.timeoutAfter === 'string'
      if (count('next') !== 1 || out.length !== (limited ? 2 : 1) || (limited && count('timedOut') !== 1))
        issues.push(issue(limited && count('next') === 1 && count('timedOut') !== 1 ? 'WF_MESSAGE_TIMEOUT_ROUTE' : 'WF_OUTGOING_INVALID', 'blocker', limited ? `"${n.label}" needs a route for when the message arrives and one for when it does not arrive in time.` : `"${n.label}" needs exactly one route to the next step.`, nodePath(n), n.id))
    } else if (n.type === 'parallelSplit') {
      if (out.length < 2 || out.some((t) => t.on !== 'next')) issues.push(issue('WF_PARALLEL_BRANCHES', 'blocker', `"${n.label}" needs at least two paths to run in parallel.`, nodePath(n), n.id))
    } else if (n.type !== 'end') {
      if (out.length !== 1 || out[0]?.on !== 'next') issues.push(issue('WF_OUTGOING_INVALID', 'blocker', `"${n.label}" needs exactly one route to the next step.`, nodePath(n), n.id))
    }
  }

  const start = startNode(def)
  if (hasCycle(def)) issues.push(issue('WF_CYCLE', 'blocker', 'The workflow loops back on itself. Loops are not supported yet.', '/transitions'))
  if (start) {
    const reach = reachableFrom(def, start.id)
    for (const n of def.nodes) {
      if (n.id !== start.id && !reach.has(n.id)) issues.push(issue('WF_UNREACHABLE_NODE', 'blocker', `"${n.label}" can never be reached from the request form.`, nodePath(n), n.id))
    }
  }
  const ends = def.nodes.filter((n) => n.type === 'end').map((n) => n.id)
  for (const n of def.nodes) {
    if (n.type === 'end') continue
    const reach = reachableFrom(def, n.id)
    if (!ends.some((e) => reach.has(e))) issues.push(issue('WF_DEAD_END', 'blocker', `"${n.label}" cannot reach a Finish step.`, nodePath(n), n.id))
  }

  // Node configuration ---------------------------------------------------------------------------------
  const decisionsUsed = new Set<string>()
  for (const n of def.nodes) {
    if (/[$#]\{/.test(n.label)) issues.push(issue('WF_LABEL_EXPRESSION', 'blocker', 'Names cannot contain ${ or #{.', nodePath(n, '/label'), n.id))
    if (n.label.trim().length < 3 || PLACEHOLDER_LABEL.test(n.label.trim())) issues.push(issue('WF_LABEL_STYLE', 'warning', `Give "${n.label}" a descriptive name such as "Review request".`, nodePath(n, '/label'), n.id))
    const c = n.config
    switch (n.type) {
      case 'approval': {
        const group = c.candidateGroupRef as string | undefined
        if (!group) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs an approver group.`, nodePath(n, '/config/candidateGroupRef'), n.id))
        else if (!ctx.groups.includes(group)) issues.push(issue('WF_GROUP_UNBOUND', 'blocker', `The group "${group}" does not exist in this workspace.`, nodePath(n, '/config/candidateGroupRef'), n.id))
        break
      }
      case 'parallelSplit':
        if (!def.nodes.some((x) => x.type === 'parallelJoin' && x.id === c.joinRef))
          issues.push(issue('WF_PARALLEL_JOIN_UNKNOWN', 'blocker', `"${n.label}" needs a matching "Wait for all" step.`, nodePath(n, '/config/joinRef'), n.id))
        break
      case 'wait': {
        const hasDuration = c.duration !== undefined
        const hasDate = c.untilField !== undefined
        if (hasDuration === hasDate) {
          issues.push(issue('WF_WAIT_CHOICE', 'blocker', hasDuration ? `"${n.label}" has both a length of time and a date. Choose one.` : `"${n.label}" needs either a length of time or a date to wait until.`, nodePath(n, hasDuration ? '/config/untilField' : '/config/duration'), n.id))
          break
        }
        if (hasDate) {
          checkWaitDate(def, n, String(c.untilField), issues)
          if (c.atTime !== undefined && !/^([01]\d|2[0-3]):[0-5]\d$/.test(String(c.atTime))) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs a time of day written like 09:00.`, nodePath(n, '/config/atTime'), n.id))
          break
        }
        const minutes = durationMinutes(String(c.duration ?? ''))
        if (minutes === null) issues.push(issue('WF_DURATION_INVALID', 'blocker', `"${n.label}" has a time that is not valid. Use days, hours and minutes, for example PT30M or P2D.`, nodePath(n, '/config/duration'), n.id))
        else if (minutes < 1) issues.push(issue('WF_DURATION_TOO_SHORT', 'blocker', `"${n.label}" waits no time at all. The shortest time is 1 minute.`, nodePath(n, '/config/duration'), n.id))
        else if (minutes > 90 * 1440) issues.push(issue('WF_DURATION_TOO_LONG', 'blocker', `"${n.label}" waits longer than 90 days.`, nodePath(n, '/config/duration'), n.id))
        break
      }
      case 'waitForMessage': {
        const name = String(c.message ?? '')
        if (!/^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$/.test(name) || name.length < 2 || name.length > 64)
          issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs a message name such as payment-received (lowercase letters and digits joined with dashes).`, nodePath(n, '/config/message'), n.id))
        checkMessageField(def, n, String(c.correlationField ?? ''), issues)
        if (typeof c.timeoutAfter === 'string') {
          const minutes = durationMinutes(c.timeoutAfter)
          if (minutes === null) issues.push(issue('WF_DURATION_INVALID', 'blocker', `"${n.label}" has a time that is not valid. Use days, hours and minutes, for example PT30M or P2D.`, nodePath(n, '/config/timeoutAfter'), n.id))
          else if (minutes < 1) issues.push(issue('WF_DURATION_TOO_SHORT', 'blocker', `"${n.label}" waits no time at all. The shortest time is 1 minute.`, nodePath(n, '/config/timeoutAfter'), n.id))
          else if (minutes > 90 * 1440) issues.push(issue('WF_DURATION_TOO_LONG', 'blocker', `"${n.label}" waits longer than 90 days.`, nodePath(n, '/config/timeoutAfter'), n.id))
        }
        const seen = new Set<string>()
        ;(Array.isArray(c.payload) ? (c.payload as Array<{ name?: string }>) : []).forEach((f, i) => {
          if (f.name && seen.has(f.name)) issues.push(issue('WF_MESSAGE_PAYLOAD_DUPLICATE', 'blocker', `"${n.label}" declares the message value "${f.name}" more than once.`, nodePath(n, `/config/payload/${i}/name`), n.id))
          if (f.name) seen.add(f.name)
        })
        break
      }
      case 'startWorkflow': {
        const ref = c.workflowRef as string | undefined
        if (!ref) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs a workflow to start.`, nodePath(n, '/config/workflowRef'), n.id))
        else if (ref === def.workflowKey) issues.push(issue('WF_CHILD_CYCLE', 'blocker', `"${n.label}" starts this same workflow.`, nodePath(n, '/config/workflowRef'), n.id))
        else if (!(ctx.publishedWorkflows ?? []).includes(ref)) issues.push(issue('WF_CHILD_NOT_PUBLISHED', 'blocker', `"${ref}" is not a published workflow of this workspace.`, nodePath(n, '/config/workflowRef'), n.id))
        break
      }
      case 'decision': {
        const ref = c.decisionRef as string | undefined
        if (!ref) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs a rule table.`, nodePath(n, '/config/decisionRef'), n.id))
        else if (!def.decisions.some((d) => d.key === ref)) issues.push(issue('WF_DECISION_UNKNOWN', 'blocker', `The rule table "${ref}" does not exist.`, nodePath(n, '/config/decisionRef'), n.id))
        else decisionsUsed.add(ref)
        break
      }
      case 'notification':
        if (!(c.message as string | undefined)?.trim()) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs a message.`, nodePath(n, '/config/message'), n.id))
        else if (/[$#]\{/.test(c.message as string)) issues.push(issue('WF_LABEL_EXPRESSION', 'blocker', 'Messages cannot contain ${ or #{.', nodePath(n, '/config/message'), n.id))
        break
      case 'end':
        if (!(c.businessOutcome as string | undefined)) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs an outcome name.`, nodePath(n, '/config/businessOutcome'), n.id))
        else if (!/^[A-Z][A-Za-z ]{0,39}$/.test(c.businessOutcome as string)) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', 'The outcome name must start with a capital letter and use only letters and spaces.', nodePath(n, '/config/businessOutcome'), n.id))
        break
      case 'http': {
        const key = c.connectionRef as string | undefined
        const op = c.operation as string | undefined
        if (!key) {
          issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs a connection.`, nodePath(n, '/config/connectionRef'), n.id))
          break
        }
        if (!def.dependencies.connections.some((d) => d.key === key)) issues.push(issue('WF_CONNECTION_UNDECLARED', 'blocker', `The connection "${key}" is not declared as a dependency.`, '/dependencies/connections', n.id))
        const conn = ctx.connections.find((x) => x.key === key)
        if (!conn) {
          issues.push(issue('WF_CONNECTION_UNKNOWN', 'blocker', `The connection "${key}" is not registered in this workspace.`, nodePath(n, '/config/connectionRef'), n.id))
          break
        }
        if (!conn.bound) issues.push(issue('WF_CONNECTION_UNBOUND', 'blocker', `The connection "${key}" has no enabled address in this workspace. Ask a connection manager to set it up.`, nodePath(n, '/config/connectionRef'), n.id))
        const operation = op ? conn.operations[op] : undefined
        if (!op || !operation) {
          issues.push(issue(op ? 'WF_OPERATION_UNKNOWN' : 'WF_SCHEMA_INVALID', 'blocker', op ? `The connection has no operation "${op}".` : `"${n.label}" needs an operation.`, nodePath(n, '/config/operation'), n.id))
          break
        }
        const mapping = (c.inputMapping ?? {}) as Record<string, string>
        for (const k of Object.keys(mapping)) if (!operation.requestProps.includes(k)) issues.push(issue('WF_MAPPING_UNKNOWN_TARGET', 'blocker', `"${k}" is not an input of this operation.`, nodePath(n, `/config/inputMapping/${k}`), n.id))
        for (const req of operation.requestRequired) if (!(req in mapping)) issues.push(issue('WF_MAPPING_MISSING_REQUIRED', 'blocker', `"${n.label}" must fill the required input "${req}".`, nodePath(n, '/config/inputMapping'), n.id))
        for (const [k, ref] of Object.entries(mapping)) {
          checkRef(def, n, ref, nodePath(n, `/config/inputMapping/${k}`), issues)
        }
        const result = (c.resultMapping ?? {}) as Record<string, string>
        if (Object.keys(result).length === 0) issues.push(issue('WF_SCHEMA_INVALID', 'blocker', `"${n.label}" needs at least one result field to keep.`, nodePath(n, '/config/resultMapping'), n.id))
        for (const [k, pointer] of Object.entries(result)) {
          const key2 = pointer.split('/').filter(Boolean)[0]
          if (!key2 || !operation.responseProps.includes(key2)) issues.push(issue('WF_RESULT_POINTER_UNKNOWN', 'blocker', `The other system does not return "${pointer}".`, nodePath(n, `/config/resultMapping/${k}`), n.id))
        }
        const timeout = c.timeoutSeconds as number | undefined
        if (typeof timeout === 'number' && timeout > operation.maxTimeoutSeconds) issues.push(issue('WF_TIMEOUT_EXCEEDS_OPERATION', 'blocker', `The timeout is longer than the ${operation.maxTimeoutSeconds} seconds this operation allows.`, nodePath(n, '/config/timeoutSeconds'), n.id))
        break
      }
      default:
        break
    }
  }

  // Routes and conditions -------------------------------------------------------------------------------
  for (const n of def.nodes.filter((x) => x.type === 'branch')) {
    const conds = outgoing(def, n.id).filter((t) => t.on === 'condition' && t.condition)
    const fields = new Set(conds.map((t) => t.condition?.fieldRef))
    if (fields.size > 1) issues.push(issue('WF_BRANCH_MIXED_FIELDS', 'blocker', `All conditions of "${n.label}" must test the same value.`, nodePath(n), n.id))
    for (const t of outgoing(def, n.id).filter((x) => x.on === 'condition')) {
      const i = def.transitions.indexOf(t)
      if (!t.condition) {
        issues.push(issue('WF_SCHEMA_INVALID', 'blocker', 'This route needs a condition.', `/transitions/${i}/condition`, n.id, t.id))
        continue
      }
      checkCondition(def, n, t.condition, `/transitions/${i}/condition`, issues, t.id)
    }
    for (let a = 0; a < conds.length; a++) {
      for (let b = a + 1; b < conds.length; b++) {
        if (overlaps(conds[a]?.condition as Condition, conds[b]?.condition as Condition)) issues.push(issue('WF_BRANCH_OVERLAP', 'blocker', `Two conditions of "${n.label}" can both be true. Make them exclusive.`, `/transitions/${def.transitions.indexOf(conds[b] as never)}`, n.id, conds[b]?.id))
      }
    }
  }

  def.decisions.forEach((d, di) => {
    const node = def.nodes.find((n) => n.type === 'decision' && n.config.decisionRef === d.key)
    if (!decisionsUsed.has(d.key)) issues.push(issue('WF_DECISION_UNUSED', 'warning', `The rule table "${d.key}" is not used by any step.`, `/decisions/${di}`))
    const fields = new Set(d.rows.map((r) => r.when.fieldRef))
    if (fields.size > 1 && d.rows.every(r => isAtomicCondition(r.when))) issues.push(issue('WF_DECISION_MIXED_FIELDS', 'blocker', `All rows of "${d.key}" must test the same value.`, `/decisions/${di}`, node?.id))
    const shape = (o: Record<string, Literal>) => Object.keys(o).sort().join(',') + '|' + Object.keys(o).sort().map((k) => typeof o[k]).join(',')
    const defaultShape = shape(d.default ?? {})
    if (d.rows.some((r) => shape(r.then) !== defaultShape)) issues.push(issue('WF_DECISION_OUTPUT_SHAPE', 'blocker', `Every row and the Otherwise result of "${d.key}" must give the same result fields.`, `/decisions/${di}`, node?.id))
    d.rows.forEach((r, ri) => {
      if (node) checkCondition(def, node, r.when, `/decisions/${di}/rows/${ri}/when`, issues)
      for (const [k, v] of Object.entries(r.then)) if (typeof v === 'string' && !/^[A-Za-z0-9 _.,:/-]{0,100}$/.test(v)) issues.push(issue('WF_LITERAL_UNSAFE', 'blocker', `The value "${k}" contains characters that are not allowed.`, `/decisions/${di}/rows/${ri}/then/${k}`, node?.id))
    })
    for (let a = 0; a < d.rows.length; a++) {
      for (let b = a + 1; b < d.rows.length; b++) {
        if (overlaps((d.rows[a] as { when: Condition }).when, (d.rows[b] as { when: Condition }).when)) issues.push(issue('WF_DECISION_OVERLAP', 'blocker', `Rows ${a + 1} and ${b + 1} of "${d.key}" can both match. Make them exclusive.`, `/decisions/${di}/rows/${b}`, node?.id))
      }
    }
  })

  // Dependencies, layout, forms -------------------------------------------------------------------------
  for (const [i, dep] of def.dependencies.connections.entries()) {
    if (!def.nodes.some((n) => n.type === 'http' && n.config.connectionRef === dep.key)) issues.push(issue('WF_DEPENDENCY_UNUSED', 'warning', `The connection "${dep.key}" is declared but no step uses it.`, `/dependencies/connections/${i}`))
  }
  for (const id of Object.keys(def.uiLayout?.positions ?? {})) {
    if (!nodeById(def, id)) issues.push(issue('WF_LAYOUT_UNKNOWN_NODE', 'blocker', `A layout position refers to the missing step "${id}".`, `/uiLayout/positions/${id}`))
  }
  for (const key of def.inputSchema.required ?? []) {
    if (!(key in def.inputSchema.properties)) issues.push(issue('WF_FORM_INVALID', 'blocker', `The required field "${key}" does not exist.`, '/inputSchema/required'))
  }
  for (const [key, f] of Object.entries(def.inputSchema.properties)) {
    if (f.minLength !== undefined && f.maxLength !== undefined && f.minLength > f.maxLength) issues.push(issue('WF_FORM_INVALID', 'blocker', `"${f.title}" has a minimum length above its maximum.`, `/inputSchema/properties/${key}`))
    if (f.minimum !== undefined && f.maximum !== undefined && f.minimum > f.maximum) issues.push(issue('WF_FORM_INVALID', 'blocker', `"${f.title}" has a minimum above its maximum.`, `/inputSchema/properties/${key}`))
    for (const [member, text] of [['formatMinimum', f.formatMinimum], ['formatMaximum', f.formatMaximum]] as const) {
      if (text !== undefined && !isCalendarDate(text)) issues.push(issue('WF_FORM_INVALID', 'blocker', `"${f.title}" has a date limit that does not exist (${text}).`, `/inputSchema/properties/${key}/${member}`))
    }
    if (f.formatMinimum && f.formatMaximum && isCalendarDate(f.formatMinimum) && isCalendarDate(f.formatMaximum) && f.formatMinimum > f.formatMaximum) issues.push(issue('WF_FORM_INVALID', 'blocker', `"${f.title}" has an earliest date after its latest date.`, `/inputSchema/properties/${key}/formatMinimum`))
  }
  for (const [k, m] of Object.entries(def.outputMapping)) {
    const parsed = parseRef(m.fieldRef)
    if (!parsed || !allFields(def).some((f) => f.ref === m.fieldRef)) issues.push(issue('WF_FIELDREF_UNKNOWN', 'blocker', `The output "${k}" refers to a value that does not exist.`, `/outputMapping/${k}/fieldRef`))
  }
  if (def.security.selfApproval === 'allow') {
    issues.push(
      ctx.isTestWorkspace
        ? issue('WF_SELF_APPROVAL_ALLOWED', 'warning', 'Requesters can approve their own requests. Every such decision is flagged and audited.', '/security/selfApproval')
        : issue('WF_SELF_APPROVAL_NOT_ALLOWED', 'blocker', 'This workspace does not allow requesters to approve their own requests.', '/security/selfApproval'),
    )
  }

  return issues.sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'blocker' ? -1 : 1) || a.path.localeCompare(b.path) || a.code.localeCompare(b.code))
}

/** The date of a "wait until a date" step must be an existing date question that always has a value (S12 section 10). */
function checkWaitDate(def: Definition, n: WorkflowNode, ref: string, issues: Issue[]): void {
  const path = `/nodes/${def.nodes.findIndex((x) => x.id === n.id)}/config/untilField`
  const known = allFields(def).find((f) => f.ref === ref)
  if (!known) {
    issues.push(issue('WF_FIELDREF_UNKNOWN', 'blocker', ref ? `"${ref}" does not exist.` : `"${n.label}" needs a date to wait until.`, path, n.id))
    return
  }
  if (known.type !== 'date') {
    issues.push(issue('WF_WAIT_FIELD_TYPE', 'blocker', `"${n.label}" waits until the date in ${ref}, but that is not a date. Choose a date field.`, path, n.id))
    return
  }
  const input = /^\/input\/(.+)$/.exec(ref)?.[1]
  if (input) {
    if (!(def.inputSchema.required ?? []).includes(input)) issues.push(issue('WF_WAIT_FIELD_OPTIONAL', 'blocker', `"${n.label}" waits until the date in "${known.label}", but that field is optional on the request form, so there may be no date to wait for. Make the field required.`, path, n.id))
  } else if (!availableFields(def, n.id).some((f) => f.ref === ref)) {
    issues.push(issue('WF_FIELDREF_NOT_GUARANTEED', 'blocker', `"${known.group} → ${known.label}" is not always available before "${n.label}".`, path, n.id))
  }
}

/** The field that identifies the request of a "Wait for a message" step (S16): text or a whole number that always has a value. */
function checkMessageField(def: Definition, n: WorkflowNode, ref: string, issues: Issue[]): void {
  const path = `/nodes/${def.nodes.findIndex((x) => x.id === n.id)}/config/correlationField`
  const known = allFields(def).find((f) => f.ref === ref)
  if (!known) {
    issues.push(issue('WF_MESSAGE_CORRELATION_UNKNOWN', 'blocker', ref ? `"${n.label}" identifies the request by ${ref}, but that field does not exist.` : `"${n.label}" needs a field that identifies the request.`, path, n.id))
    return
  }
  if (known.type !== 'string' && known.type !== 'integer') {
    issues.push(issue('WF_MESSAGE_CORRELATION_TYPE', 'blocker', `"${n.label}" identifies the request by ${ref}, but that is not text or a whole number. Choose a field such as an order number.`, path, n.id))
    return
  }
  const input = /^\/input\/(.+)$/.exec(ref)?.[1]
  if (input) {
    if (!(def.inputSchema.required ?? []).includes(input)) issues.push(issue('WF_MESSAGE_CORRELATION_OPTIONAL', 'blocker', `"${n.label}" identifies the request by "${known.label}", but that field is optional on the request form. Make the field required.`, path, n.id))
  } else if (!availableFields(def, n.id).some((f) => f.ref === ref)) {
    issues.push(issue('WF_FIELDREF_NOT_GUARANTEED', 'blocker', `"${known.group} → ${known.label}" is not always available before "${n.label}".`, path, n.id))
  }
}

function checkRef(def: Definition, at: WorkflowNode, ref: string, path: string, issues: Issue[]): void {
  const known = allFields(def).find((f) => f.ref === ref)
  if (!known) {
    issues.push(issue('WF_FIELDREF_UNKNOWN', 'blocker', `"${ref}" does not exist.`, path, at.id))
    return
  }
  if (!availableFields(def, at.id).some((f) => f.ref === ref)) {
    issues.push(issue('WF_FIELDREF_NOT_GUARANTEED', 'blocker', `"${known.group} → ${known.label}" is not always available before "${at.label}".`, path, at.id))
  }
}

function checkCondition(def: Definition, at: WorkflowNode, c: Condition, path: string, issues: Issue[], transitionId?: string): void {
  if (!isAtomicCondition(c)) {
    const group = 'all' in c ? 'all' : 'any'
    for (const [index, term] of (c[group] ?? []).entries()) {
      checkCondition(def, at, term, `${path}/${group}/${index}`, issues, transitionId)
    }
    return
  }
  const known = allFields(def).find((f) => f.ref === c.fieldRef)
  if (!known) {
    issues.push(issue('WF_FIELDREF_UNKNOWN', 'blocker', `"${c.fieldRef}" does not exist.`, `${path}/fieldRef`, at.id, transitionId))
    return
  }
  if (!availableFields(def, at.id).some((f) => f.ref === c.fieldRef)) {
    issues.push(issue('WF_FIELDREF_NOT_GUARANTEED', 'blocker', `"${known.group} → ${known.label}" is not always available before "${at.label}".`, `${path}/fieldRef`, at.id, transitionId))
  }
  if (known.type === 'date') {
    issues.push(issue('WF_OPERATOR_TYPE', 'blocker', `${c.fieldRef} is a date. Dates can be used to wait until a day, but not (yet) to choose a path or in a decision table.`, `${path}/operator`, at.id, transitionId))
    return
  }
  if (isNumericRange(c.operator) && known.type !== 'number' && known.type !== 'integer') {
    issues.push(issue('WF_OPERATOR_TYPE', 'blocker', '"at least" and "less than" only work on numbers.', `${path}/operator`, at.id, transitionId))
  }
  const literalOk = typeof c.value === 'number' ? known.type === 'number' || known.type === 'integer' : typeof c.value === known.type
  if (!literalOk) issues.push(issue('WF_LITERAL_TYPE', 'blocker', 'The value does not match the type of the field.', `${path}/value`, at.id, transitionId))
  else if (c.operator === 'eq' && known.enumValues && !known.enumValues.includes(String(c.value))) {
    issues.push(issue('WF_LITERAL_NOT_IN_DOMAIN', 'blocker', `"${String(c.value)}" is never a possible value of this field.`, `${path}/value`, at.id, transitionId))
  }
  if (typeof c.value === 'string' && !/^[A-Za-z0-9 _.,:/-]{0,100}$/.test(c.value)) {
    issues.push(issue('WF_LITERAL_UNSAFE', 'blocker', 'The value contains characters that are not allowed.', `${path}/value`, at.id, transitionId))
  }
}
