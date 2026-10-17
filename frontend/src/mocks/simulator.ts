import { isCalendarDate } from '@/lib/dates'
import { resolveInputAggregate } from '@/lib/form/aggregates'
import { conditionTerms, isAtomicCondition, type AtomicCondition, type Condition, type Definition, type Literal, type Transition, type WorkflowNode } from '@/features/studio/model/types'

/**
 * Mock-side port of the dry run ("Test this workflow", docs/build/S4-workflow-compiler.md section 13). The Java class
 * `com.orqix.compiler.internal.sim.SimulationRun` is the authority: this file reproduces it for the in-browser API, so
 * the studio can be demonstrated without a server. Both are checked against the same golden traces
 * (examples/fixtures/simulation/*.trace.json, `simulator.test.ts` here and `WorkflowSimulatorGoldenTest` in Java), so a
 * sentence cannot drift on one side only. Differences that remain, by design: numbers are JavaScript doubles instead of
 * BigDecimal, and form errors use the mock validator's wording.
 */

// ------------------------------------------------------------------------------------------------ public types

export interface SimulationScenario {
  approvals?: Record<string, { outcome?: 'approve' | 'reject' | 'timeout'; reason?: string }>
  calls?: Record<string, { failure?: 'rejected' | 'unavailable' | 'unknownOutcome'; result?: Record<string, unknown> }>
  children?: Record<string, { outcomes?: string[]; results?: Record<string, unknown> }>
  messages?: Record<string, 'arrives' | 'timeout'>
  simulateChildren?: boolean
  childScenarios?: Record<string, SimulationScenario>
}

export interface SimConnection {
  name: string
  kind?: string
  operations: Record<string, { label?: string; effectClass?: string; responseSchema?: Record<string, unknown> }>
}

export interface SimContext {
  /** Business names of the workspace groups by key. */
  groups: Record<string, string>
  /** The registered connection definition for a key, when it is registered. */
  connection: (key: string) => SimConnection | undefined
  /** The definition of the active release of the workflow with this key, when it has one. */
  childDefinition: (key: string) => Definition | undefined
  /** Today as YYYY-MM-DD (UTC). */
  today: string
  /** Validation issues of the definition (the mock validator); blockers outside the tolerated list stop the test. */
  issues: Array<{ code: string; severity: 'blocker' | 'warning'; message: string; path: string; nodeId?: string | null }>
  /** Form validation: field errors for the sample request, empty when valid. */
  validateInput: (def: Definition, input: Record<string, unknown>) => Array<{ pointer: string; code: string; message: string }>
}

type Json = null | boolean | number | string | Json[] | { [key: string]: Json }
type Entry = Record<string, Json>

export type SimulationOutcome =
  | { status: 'ok'; body: Record<string, Json> }
  | { status: 'invalidInput'; errors: Array<{ pointer: string; code: string; message: string }> }
  | { status: 'cannotRun'; issues: SimContext['issues'] }

export const TOLERATED = new Set(['WF_GROUP_UNBOUND', 'WF_CONNECTION_UNKNOWN', 'WF_CONNECTION_UNBOUND', 'WF_CHILD_UNKNOWN', 'WF_CHILD_NOT_PUBLISHED', 'WF_SELF_APPROVAL_NOT_ALLOWED'])
type Budget = {nodes:number;children:number}
export const MAX_ENTRIES = 400
export const MAX_CHILD_DEPTH = 3
export const LIMITS =
  'This is a dry run of the workflow as designed, not the workflow engine. It does not call real systems, check that connections work, that people are in the groups, or how long anything takes.'

// ------------------------------------------------------------------------------------------------ text

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const quote = (t: string) => `"${t}"`
const count = (n: number, unit: string) => `${n} ${unit}${n === 1 ? '' : 's'}`

function group(plain: string): string {
  const negative = plain.startsWith('-')
  const body = negative ? plain.slice(1) : plain
  const dot = body.indexOf('.')
  const whole = dot < 0 ? body : body.slice(0, dot)
  const fraction = dot < 0 ? '' : body.slice(dot)
  let grouped = ''
  for (let i = 0; i < whole.length; i++) {
    if (i > 0 && (whole.length - i) % 3 === 0) grouped += ','
    grouped += whole[i]
  }
  return (negative ? '-' : '') + grouped + fraction
}

/** Plain decimal text of a number without exponent and without trailing zeros. */
function plainNumber(v: number): string {
  if (Number.isInteger(v)) return String(v)
  const text = String(v)
  if (!/e/i.test(text)) return text
  return v.toFixed(20).replace(/0+$/, '').replace(/\.$/, '')
}

export const text = {
  number: (v: number) => group(plainNumber(v)),
  money: (v: number) => `USD ${group(Number.isInteger(v) ? String(v) : v.toFixed(2))}`,
  value(v: unknown, money: boolean): string {
    if (typeof v === 'number') return money ? text.money(v) : text.number(v)
    if (typeof v === 'boolean') return v ? 'yes' : 'no'
    if (Array.isArray(v)) return `${v.length} ${v.length === 1 ? 'item' : 'items'}`
    return v === null || v === undefined ? 'nothing' : String(v)
  },
  fieldLabel: (title: string, money: boolean) => (money ? title.replace(/\s*\([^)]*\)\s*$/, '') : title),
  date(iso: string): string {
    const [y, m, d] = iso.split('-').map(Number) as [number, number, number]
    return `${d} ${MONTHS[m - 1]} ${y}`
  },
}

const DURATION = /^P(?=\d|T\d)(?:(\d{1,3})D)?(?:T(?=\d)(?:(\d{1,4})H)?(?:(\d{1,5})M)?)?$/
function durationMinutes(value: string | undefined | null): number {
  if (!value) return -1
  const m = DURATION.exec(value)
  if (!m) return -1
  return Number(m[1] ?? 0) * 1440 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0)
}
function describeMinutes(total: number): string {
  const parts: string[] = []
  const add = (n: number, unit: string) => {
    if (n !== 0) parts.push(`${n} ${unit}${n === 1 ? '' : 's'}`)
  }
  add(Math.floor(total / 1440), 'day')
  add(Math.floor((total % 1440) / 60), 'hour')
  add(total % 60, 'minute')
  return parts.length === 0 ? '0 minutes' : parts.join(' ')
}

const OPERATOR_PHRASE: Record<string, string> = { eq: 'is', ne: 'is not', gte: 'is at least', gt: 'is greater than', lt: 'is less than', lte: 'is at most' }

/** Same semantics as ConditionMatcher.matches: numbers compare numerically, other types only with is / is not. */
export function matches(operator: string, actual: unknown, literal: Literal): boolean {
  if (!(operator in OPERATOR_PHRASE)) throw new Error(`Unknown operator: ${operator}`)
  if (typeof actual === 'number' && typeof literal === 'number') {
    switch (operator) {
      case 'eq':
        return actual === literal
      case 'ne':
        return actual !== literal
      case 'gte':
        return actual >= literal
      case 'gt':
        return actual > literal
      case 'lt':
        return actual < literal
      default:
        return actual <= literal
    }
  }
  if (typeof actual !== typeof literal) throw new Error(`Cannot compare ${typeof actual} with ${typeof literal}`)
  if (operator !== 'eq' && operator !== 'ne') throw new Error(`Operator ${operator} compares numbers only`)
  return operator === 'eq' ? actual === literal : actual !== literal
}

function parsePointer(ref: string): string[] | null {
  if (ref === '') return []
  if (!ref.startsWith('/')) return null
  return ref
    .slice(1)
    .split('/')
    .map((t) => t.replace(/~1/g, '/').replace(/~0/g, '~'))
}

/** A deterministic sample document for a JSON schema object or scalar (twin of SimulationRun.sample). */
export function sample(schema: Record<string, unknown>, key: string): Json {
  const type = (schema.type as string | undefined) ?? 'string'
  switch (type) {
    case 'object': {
      const out: Record<string, Json> = {}
      const props = (schema.properties ?? {}) as Record<string, Record<string, unknown>>
      for (const name of Object.keys(props).sort()) out[name] = sample(props[name] as Record<string, unknown>, name)
      return out
    }
    case 'array':
      return []
    case 'boolean':
      return false
    case 'integer':
    case 'number': {
      let v = 1
      if (typeof schema.minimum === 'number' && schema.minimum > v) v = schema.minimum
      if (typeof schema.maximum === 'number' && schema.maximum < v) v = schema.maximum
      return v
    }
    default: {
      if (Array.isArray(schema.enum) && schema.enum.length > 0) return String(schema.enum[0])
      if (schema.format === 'date') return '2026-01-15'
      let t = `sample-${key}`
      if (typeof schema.maxLength === 'number' && t.length > schema.maxLength) t = t.slice(0, Math.max(0, schema.maxLength))
      return t
    }
  }
}

// ------------------------------------------------------------------------------------------------ the run

type Flow = 'arrived' | 'ended' | 'stalled'

interface ChildOutcome {
  label: string
  kind: string
}

class Run {
  private readonly nodes: WorkflowNode[]
  private readonly byId = new Map<string, WorkflowNode>()
  private readonly shadowed = new Set<WorkflowNode>()
  private readonly out = new Map<string, Transition[]>()
  private readonly resolved: Transition[] = []
  private readonly input: Record<string, unknown>
  private readonly steps = new Map<string, Record<string, unknown>>()
  private readonly trace: Entry[] = []
  private readonly visited = new Set<string>()
  private readonly taken = new Set<string>()
  private readonly matchedRules = new Set<string>()
  private readonly warnings: Entry[] = []
  private lane: string | null = null
  private endNode: WorkflowNode | null = null
  private attention: Entry | null = null

  private readonly def: Definition
  private readonly scenario: SimulationScenario
  private readonly ctx: SimContext
  private readonly depth: number
  private readonly budget: Budget
  private readonly tolerated: SimContext['issues']

  constructor(def: Definition, input: Record<string, unknown>, scenario: SimulationScenario, ctx: SimContext, depth: number, tolerated: SimContext['issues'], budget:Budget) {
    this.def = def
    this.scenario = scenario
    this.ctx = ctx
    this.depth = depth
    this.budget = budget
    this.tolerated = tolerated
    this.input = input
    this.nodes = def.nodes
    for (const n of def.nodes) {
      if (this.byId.has(n.id)) this.shadowed.add(n)
      else this.byId.set(n.id, n)
      this.out.set(n.id, [])
    }
    for (const t of def.transitions) {
      if (this.byId.has(t.from) && this.byId.has(t.to)) {
        this.resolved.push(t)
        this.out.get(t.from)?.push(t)
      }
    }
  }

  // -------------------------------------------------------------------------------------------- run

  execute(): Record<string, Json> {
    this.collectWarnings()
    const start = this.nodes.find((n) => n.type === 'start' && !this.shadowed.has(n)) as WorkflowNode
    this.walk(start, null)
    const outcome = this.outcome()
    const outputs = this.outputs()
    this.scenarioWarnings()
    return { outcome, outputs, trace: this.trace, warnings: this.warnings, coverage: this.coverage(), limits: LIMITS }
  }

  private outcome(): Entry {
    const end = this.endNode
    if (end) {
      const businessOutcome = String(end.config.businessOutcome)
      const outcomeKind = String(end.config.outcomeKind)
      return {
        status: 'finished',
        businessOutcome,
        outcomeKind,
        endStepId: end.id,
        attention: null,
        summary: `The request would finish with the outcome ${quote(businessOutcome)} (${kindWord(outcomeKind)}).`,
      }
    }
    return {
      status: 'needsAttention',
      businessOutcome: null,
      outcomeKind: null,
      endStepId: null,
      attention: this.attention,
      summary: `The request would stop and need attention${this.attention ? ` at ${quote(String(this.attention.stepLabel))}.` : '.'}`,
    }
  }

  private outputs(): Entry {
    const o: Entry = {}
    if (!this.endNode) return o
    for (const [key, entry] of Object.entries(this.def.outputMapping)) {
      const v = this.resolve(entry.fieldRef)
      if (v !== null && v !== undefined) o[key] = v as Json
    }
    return o
  }

  // -------------------------------------------------------------------------------------------- walking

  private walk(from: WorkflowNode, stopJoin: string | null): Flow {
    let node = from
    for (;;) {
      if (this.trace.length >= MAX_ENTRIES || ++this.budget.nodes > 1000) {
        this.stall(node, 'TEST_LIMIT', 'The test reached its bounded step budget (400 per workflow, 1000 across children).')
        return 'stalled'
      }
      if (node.type === 'parallelJoin' && node.id === stopJoin) return 'arrived'
      this.visited.add(node.id)
      const next = this.step(node)
      if (!next) return this.endNode ? 'ended' : 'stalled'
      this.taken.add(next.id)
      node = this.byId.get(next.to) as WorkflowNode
    }
  }

  private step(node: WorkflowNode): Transition | null {
    if ((node.type as string) === 'waitForMessage') return this.waitForMessage(node)
    if ((node.type as string) === 'externalTask') return this.externalTask(node)
    switch (node.type) {
      case 'start': {
        const t = this.next(node, 'next')
        this.add(node, 'The request is submitted with the answers you entered.', {}, null, t)
        return t
      }
      case 'approval':
        return this.approval(node)
      case 'decision':
        return this.decision(node)
      case 'branch':
        return this.branch(node)
      case 'http':
        return this.http(node)
      case 'generateDocument': {
        const mapping = (node.config.inputMapping ?? {}) as Record<string, string>
        if (Object.values(mapping).some((ref) => this.resolve(ref) == null)) {
          this.stall(node, 'TEST_DOCUMENT_FIELD_MISSING', 'A mapped document field is missing from this sample request.')
          return null
        }
        const t = this.next(node, 'next')
        this.add(node, `The workflow would generate a ${String(node.config.format).toUpperCase()} document from its pinned template. This test creates no file.`, { format: String(node.config.format), mappedFieldCount: Object.keys(mapping).length }, null, t)
        return t
      }
      case 'notification': {
        const message = String(node.config.message ?? '')
        const t = this.next(node, 'next')
        this.add(node, `${quote(node.label)}: the requester would get this in-app message: ${quote(message)}`, { channel: String(node.config.channel), recipient: String(node.config.recipient), message }, null, t)
        return t
      }
      case 'parallelSplit':
        return this.split(node)
      case 'parallelJoin': {
        const t = this.next(node, 'next')
        this.add(node, 'The parallel work is joined here and the request continues.', {}, null, t)
        return t
      }
      case 'wait':
        return this.wait(node)
      case 'startWorkflow':
        return this.startWorkflow(node)
      case 'end': {
        this.endNode = node
        const businessOutcome = String(node.config.businessOutcome)
        const outcomeKind = String(node.config.outcomeKind)
        this.add(node, `The request finishes: ${quote(businessOutcome)} (${kindWord(outcomeKind)}).`, { businessOutcome, outcomeKind }, null, null)
        return null
      }
      default:
        this.stallWith(node, `This kind of step (${node.type}) cannot be tested yet.`, {}, 'UNSUPPORTED_STEP', 'The test does not know how to run this kind of step.')
        return null
    }
  }

  private next(n: WorkflowNode, on: Transition['on']): Transition | null {
    return (this.out.get(n.id) ?? []).find((t) => t.on === on) ?? null
  }

  // -------------------------------------------------------------------------------------------- approval

  private approval(n: WorkflowNode): Transition | null {
    const s = this.scenario.approvals?.[n.id] ?? {}
    let choice = s.outcome ?? 'approve'
    if (!['approve', 'reject', 'timeout'].includes(choice)) throw new ScenarioError(`approvals.${n.id}.outcome must be approve, reject or timeout`)
    const ref = n.config.candidateGroupRef as string | undefined
    const groupName = ref && this.ctx.groups[ref] ? (this.ctx.groups[ref] as string) : !ref || ref.trim() === '' ? 'nobody (no group is chosen)' : ref
    const d: Entry = { group: ref ?? null, groupName, dueAfterHours: typeof n.config.dueAfterHours === 'number' ? n.config.dueAfterHours : null }
    const out: Record<string, unknown> = {}
    let t: Transition | null
    let sentence = `${quote(n.label)} goes to ${groupName}.`
    let note: string | null = null
    if (choice === 'timeout' && !this.next(n, 'timedOut')) {
      note = 'This step has no time limit, so the test treats it as approved.'
      choice = 'approve'
    }
    if (choice === 'reject') {
      const reason = s.reason ?? (n.config.rejectReasonRequired === true ? 'Sample rejection reason' : null)
      out.decision = 'rejected'
      if (reason !== null) out.reason = reason
      t = this.next(n, 'rejected')
      sentence += ` In this test they reject it${reason === null ? '.' : ` with the reason ${quote(reason)}.`}`
    } else if (choice === 'timeout') {
      out.decision = 'timedOut'
      t = this.next(n, 'timedOut')
      const minutes = durationMinutes(n.config.timeoutAfter as string | undefined)
      sentence += ` In this test nobody answers within ${minutes > 0 ? describeMinutes(minutes) : 'the time limit'}, so the time limit fires and the request takes the timed-out route.`
    } else {
      out.decision = 'approved'
      t = this.next(n, 'approved')
      sentence += ' In this test they approve.'
    }
    if (out.decision !== 'timedOut') out.decidedBy = 'Test approver'
    d.decision = out.decision as string
    if ('reason' in out) d.reason = String(out.reason)
    if (note !== null) {
      d.note = note
      sentence += ` ${note}`
    }
    this.steps.set(n.id, out)
    this.add(n, sentence, d, out, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- decision

  private decision(n: WorkflowNode): Transition | null {
    const table = this.def.decisions.find((c) => c.key === n.config.decisionRef)
    const d: Entry = {}
    if (!table) {
      const message = `The decision table ${quote(String(n.config.decisionRef))} does not exist.`
      this.stallWith(n, message, d, 'INTERNAL_ERROR', message)
      return null
    }
    d.decisionKey = table.key
    d.version = table.version
    const values = new Map<string, unknown>()
    const inputs: Json[] = []
    for (const term of table.rows.flatMap(row => conditionTerms(row.when))) {
      const ref = term.fieldRef
      if (!values.has(ref)) {
        const v = this.resolve(ref)
        values.set(ref, v ?? null)
        const fi = this.info(ref)
        inputs.push({ fieldRef: ref, label: fi.label, value: (v ?? null) as Json, shown: v === null || v === undefined ? 'no value' : text.value(v, fi.money) })
      }
    }
    d.inputs = inputs
    let matchedRow: number | null = null
    let failure: string | null = null
    try {
      for (const [i, row] of table.rows.entries()) {
        if (conditionTerms(row.when).some(term => values.get(term.fieldRef) == null)) throw new Error('Missing decision input')
        if (this.evaluate(row.when, [])) {
          matchedRow = i
          break
        }
      }
    } catch (e) {
      failure = (e as Error).message
    }
    if (failure !== null) {
      const missing = [...values.entries()].find(([, v]) => v === null || v === undefined)?.[0]
      const message = `${quote(n.label)} needs ${missing === undefined ? 'a value it does not have' : this.info(missing).label}, but the test has no usable value for it.`
      this.stallWith(n, message, d, 'INTERNAL_ERROR', message)
      return null
    }
    const rows: Json[] = []
    let firstMatch: number | null = null
    for (const [i, row] of table.rows.entries()) {
      let m: boolean | null
      try { m = this.evaluate(row.when, []) } catch { m = null }
      if (m === true && firstMatch === null) firstMatch = i
      rows.push({ row: i + 1, condition: this.conditionText(row.when, false, undefined), matched: m, selected: matchedRow === i, outputs: sortedLiterals(row.then) })
    }
    d.rows = rows
    d.usedDefault = matchedRow === null
    d.matchedRow = matchedRow === null ? null : matchedRow + 1
    const output = sortedLiterals(matchedRow === null ? table.default : (table.rows[matchedRow] as { then: Record<string, Literal> }).then)
    d.outputs = output
    this.matchedRules.add(`${table.key}#${matchedRow === null ? 'default' : matchedRow}`)
    this.steps.set(n.id, { ...output })
    const outputsText = outputsTextOf(output)
    let sentence: string
    if (matchedRow === null) sentence = `${quote(n.label)}: no rule applies, so the "otherwise" result is used: ${outputsText}.`
    else {
      const row = table.rows[matchedRow] as { when: AtomicCondition }
      sentence = `${quote(n.label)}: rule ${matchedRow + 1} applies because ${this.conditionText(row.when, true, isAtomicCondition(row.when) ? values.get(row.when.fieldRef) : undefined)}, so ${outputsText}.`
    }
    const t = this.next(n, 'next')
    this.add(n, sentence, d, output, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- branch

  private branch(n: WorkflowNode): Transition | null {
    const d: Entry = {}
    const routes: Json[] = []
    d.routes = routes
    let chosen: Transition | null = null
    let error: string | null = null
    for (const t of this.out.get(n.id) ?? []) {
      if (t.on !== 'condition' || !t.condition) continue
      const r: Entry = { transitionId: t.id, label: this.routeName(t), condition: this.conditionText(t.condition, false, undefined) }
      routes.push(r)
      if (chosen !== null || error !== null) {
        r.matched = null
        continue
      }
      try {
        const terms: Json[] = []
        const matched = this.evaluate(t.condition, terms)
        r.matched = matched
        if (!isAtomicCondition(t.condition)) r.terms = terms
        if (matched) chosen = t
      } catch (e) {
        r.matched = null
        error = (e as Error).message
      }
    }
    if (error !== null) {
      const message = `${quote(n.label)} cannot choose a path: ${error}`
      this.stallWith(n, message, d, 'INTERNAL_ERROR', message)
      return null
    }
    let sentence: string
    if (chosen !== null) {
      const t = chosen as Transition
      sentence = `${quote(n.label)}: takes the route ${quote(this.routeName(t))} because ${this.conditionText(t.condition as Condition, true, undefined)}.`
    } else {
      chosen = this.next(n, 'default')
      sentence = `${quote(n.label)}: takes the route ${quote(chosen ? this.routeName(chosen) : 'Otherwise')} because none of the other routes applies.`
    }
    const picked = chosen as Transition | null
    d.chosen = picked ? picked.id : null
    if (!picked) {
      this.stallWith(n, sentence, d, 'INTERNAL_ERROR', 'The step has no route for this case.')
      return null
    }
    this.add(n, sentence, d, null, picked)
    return picked
  }

  private evaluate(c: Condition, terms: Json[]): boolean {
    if (isAtomicCondition(c)) return this.atom(c, null)
    const all = 'all' in c
    const list = (all ? c.all : c.any) ?? []
    let result = all
    let decided = false
    for (const term of list) {
      const tn: Entry = { text: this.conditionText(term, false, undefined) }
      terms.push(tn)
      if (decided) {
        tn.matched = null
        continue
      }
      const m = this.atom(term, tn)
      tn.matched = m
      if (all && !m) {
        result = false
        decided = true
      } else if (!all && m) {
        result = true
        decided = true
      }
    }
    return result
  }

  private atom(c: AtomicCondition, record: Entry | null): boolean {
    const actual = this.resolve(c.fieldRef)
    if (actual === null || actual === undefined) throw new Error(`${this.info(c.fieldRef).label} has no value in this test.`)
    if (record) record.value = actual as Json
    try {
      return matches(c.operator, actual, c.value)
    } catch {
      throw new Error(`${this.info(c.fieldRef).label} cannot be compared like that.`)
    }
  }

  private routeName(t: Transition): string {
    if (t.label && t.label.trim() !== '') return t.label
    if (t.on === 'default') return 'Otherwise'
    return t.condition ? this.conditionText(t.condition, false, undefined) : t.id
  }

  // -------------------------------------------------------------------------------------------- http

  private http(n: WorkflowNode): Transition | null {
    const s = this.scenario.calls?.[n.id] ?? {}
    const failure = s.failure ?? null
    if (failure !== null && !['rejected', 'unavailable', 'unknownOutcome'].includes(failure)) throw new ScenarioError(`calls.${n.id}.failure must be rejected, unavailable or unknownOutcome`)
    const connKey = String(n.config.connectionRef ?? '')
    const operation = String(n.config.operation ?? '')
    const conn = this.ctx.connection(connKey)
    const op = conn?.operations[operation]
    const connName = conn ? conn.name : connKey
    const agent = conn?.kind === 'agent'
    const opLabel = op ? (op.label ?? operation) : operation
    const effect = op ? (op.effectClass ?? 'nonIdempotent') : 'nonIdempotent'
    const d: Entry = { connection: connKey, connectionName: connName, operation, operationLabel: opLabel, agent, effectClass: effect }
    const request: Entry = {}
    const inputMapping = (n.config.inputMapping ?? {}) as Record<string, string>
    for (const field of Object.keys(inputMapping).sort()) request[field] = (this.resolve(inputMapping[field] as string) ?? null) as Json
    d.request = request
    const callText = `${quote(n.label)}${agent ? ' asks the assistant ' : ' calls '}${connName} (${opLabel}).`
    const maxAttempts = this.def.execution.maxAttempts
    const retryDelay = this.def.execution.retryDelaySeconds
    if (failure !== null) {
      let reason: string
      let what: string
      let consequence: string
      if (failure === 'rejected') {
        reason = 'EFFECT_REJECTED'
        what = 'In this test the system refuses the request.'
        consequence = 'Nothing was done, so the request stops here and needs attention. Someone must fix the cause and retry.'
      } else if (failure === 'unavailable') {
        reason = 'RETRIES_EXHAUSTED'
        what = 'In this test the system does not answer.'
        consequence = `Orqix would try ${count(maxAttempts, 'time')}, ${count(retryDelay, 'second')} apart, and then the request needs attention until someone retries.`
      } else if (effect === 'readOnly') {
        reason = 'RESPONSE_INVALID'
        what = 'In this test the system answers with something that cannot be used.'
        consequence = 'The request needs attention. Someone must fix the connection and retry.'
      } else {
        reason = 'EFFECT_UNKNOWN'
        what = 'In this test the answer is lost, so nobody knows whether it was done.'
        consequence = 'The request needs attention. Someone must check the other system before retrying.'
      }
      d.failure = failure
      this.stallWith(n, `${callText} ${what} ${consequence}`, d, reason, `${what} ${consequence}`)
      return null
    }
    let response: unknown = s.result && typeof s.result === 'object' ? s.result : null
    const synthesized = response === null
    if (synthesized) response = op?.responseSchema && typeof op.responseSchema === 'object' ? sample(op.responseSchema, '') : null
    const out: Record<string, unknown> = {}
    let missing = false
    const resultMapping = (n.config.resultMapping ?? {}) as Record<string, string>
    for (const key of Object.keys(resultMapping).sort()) {
      if (response === null) {
        out[key] = `sample-${key}`
        continue
      }
      const value = pointer(response, resultMapping[key] as string)
      if (value === undefined || value === null || typeof value === 'object') missing = true
      else out[key] = value
    }
    if (missing) {
      const readOnly = effect === 'readOnly'
      const what = 'In this test the system answers without all the information this step needs.'
      const consequence = readOnly ? 'The request needs attention. Someone must fix the connection and retry.' : 'The request needs attention. Someone must check the other system before retrying.'
      d.failure = 'unknownOutcome'
      this.stallWith(n, `${callText} ${what} ${consequence}`, d, readOnly ? 'RESPONSE_INVALID' : 'EFFECT_UNKNOWN', `${what} ${consequence}`)
      return null
    }
    d.sampleAnswer = synthesized
    d.result = out as Json
    this.steps.set(n.id, out)
    const t = this.next(n, 'next')
    const returns = Object.keys(out).length === 0 ? 'It succeeds.' : `In this test it succeeds and returns ${outputsTextOf(out)}.`
    this.add(n, `${callText} ${returns}`, d, out, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- wait

  private wait(n: WorkflowNode): Transition | null {
    const d: Entry = {}
    let sentence: string
    const until = n.config.untilField as string | undefined
    if (until) {
      const value = this.resolve(until)
      const time = (n.config.atTime as string | undefined) ?? '09:00'
      d.untilField = until
      d.atTime = time
      if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && isCalendarDate(value)) {
        d.until = `${value} ${time} UTC`
        sentence = `${quote(n.label)}: would wait until ${text.date(value)}, ${time} UTC. The test skips the wait.`
        if (value < this.ctx.today) sentence = `${quote(n.label)}: the date ${text.date(value)} has already passed, so the request would not wait.`
      } else {
        sentence = `${quote(n.label)}: would wait until a date that the test does not have. The test skips the wait.`
      }
    } else {
      const duration = n.config.duration as string | undefined
      const minutes = durationMinutes(duration)
      d.duration = duration ?? null
      sentence = `${quote(n.label)}: would wait ${minutes > 0 ? describeMinutes(minutes) : String(duration)}. The test skips the wait.`
    }
    const t = this.next(n, 'next')
    this.add(n, sentence, d, null, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- message wait

  /** A worker is not simulated: the test settings may give its result, otherwise declared fields get sample values. */
  private externalTask(n: WorkflowNode): Transition | null {
    const given = ((this.scenario as { workers?: Record<string, { result?: Record<string, unknown> }> }).workers ?? {})[n.id]?.result
    const out: Record<string, unknown> = {}
    for (const field of (n.config.result ?? []) as Array<{ name: string; type: string }>) {
      const supplied = given?.[field.name]
      out[field.name] = supplied !== undefined && supplied !== null ? supplied
        : field.type === 'number' || field.type === 'integer' ? 1 : field.type === 'boolean' ? false : field.type === 'date' ? '2026-01-15' : `sample-${field.name}`
    }
    const topic = String(n.config.topic ?? '')
    const t = this.next(n, 'next')
    this.steps.set(n.id, out)
    this.add(n, `${quote(n.label)}: a worker for the topic ${quote(topic)} takes the job and returns its result. This test calls no worker; the result ${given ? 'comes from the test settings' : 'uses sample values'}.`, { topic, result: { ...out } as Json }, out, t)
    return t
  }

  private waitForMessage(n: WorkflowNode): Transition | null {
    let choice = this.scenario.messages?.[n.id] ?? 'arrives'
    if (choice !== 'arrives' && choice !== 'timeout') throw new ScenarioError(`messages.${n.id} must be arrives or timeout`)
    const message = String(n.config.message ?? '')
    const correlationField = String(n.config.correlationField ?? '')
    const f = this.info(correlationField)
    const key = this.resolve(correlationField)
    const correlation = `${f.label} (${key === null || key === undefined ? 'no value' : text.value(key, f.money)})`
    const d: Entry = { message, correlationField, correlation }
    let note: string | null = null
    if (choice === 'timeout' && !this.next(n, 'timedOut')) {
      note = 'This step has no time limit, so the test treats the message as arrived.'
      choice = 'arrives'
    }
    const out: Record<string, unknown> = {}
    let t: Transition | null
    let sentence: string
    if (choice === 'timeout') {
      const minutes = durationMinutes(n.config.timeoutAfter as string | undefined)
      d.arrived = false
      t = this.next(n, 'timedOut')
      sentence = `${quote(n.label)}: the message ${quote(message)} does not arrive within ${minutes > 0 ? describeMinutes(minutes) : 'the time limit'}, so the time limit fires and the request takes the timed-out route.`
    } else {
      for (const field of (n.config.payload ?? []) as Array<{ name: string; type: string }>) {
        out[field.name] = field.type === 'number' || field.type === 'integer' ? 1 : field.type === 'boolean' ? false : field.type === 'date' ? '2026-01-15' : `sample-${field.name}`
      }
      d.arrived = true
      d.payload = { ...out } as Json
      t = this.next(n, 'next')
      sentence = `${quote(n.label)}: the message ${quote(message)} arrives for ${correlation}.${Object.keys(out).length === 0 ? '' : ` In this test it carries ${outputsTextOf(out)}.`}`
    }
    if (note !== null) {
      d.note = note
      sentence += ` ${note}`
    }
    this.steps.set(n.id, out)
    this.add(n, sentence, d, out, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- parallel

  private split(n: WorkflowNode): Transition | null {
    const branches = this.out.get(n.id) ?? []
    const joinRef = String(n.config.joinRef ?? '')
    const d: Entry = { joinRef, branches: branches.length }
    this.add(n, `${quote(n.label)}: starts ${branches.length === 1 ? '1 branch' : `${branches.length} branches`} at the same time.`, d, null, null)
    const saved = this.lane
    let ended = false
    let stalled = false
    let index = 0
    for (const t of branches) {
      index++
      if (ended) {
        d.remainingBranchesStopped = true
        break
      }
      this.taken.add(t.id)
      this.lane = saved === null ? String(index) : `${saved}.${index}`
      const flow = this.walk(this.byId.get(t.to) as WorkflowNode, joinRef)
      if (flow === 'ended') ended = true
      else if (flow === 'stalled') stalled = true
    }
    this.lane = saved
    if (ended || stalled) return null
    const join = this.byId.get(joinRef)
    if (!join) return null
    this.visited.add(join.id)
    const t = this.next(join, 'next')
    this.add(join, `${quote(join.label)}: all ${branches.length} branches have finished, so the request continues.`, {}, null, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- child workflows

  private startWorkflow(n: WorkflowNode): Transition | null {
    const s = this.scenario.children?.[n.id] ?? {}
    const ref = String(n.config.workflowRef ?? '')
    const child = this.ctx.childDefinition(ref)
    const childName = child ? child.name : ref
    const outcomes: ChildOutcome[] = []
    if (child) {
      for (const node of child.nodes) {
        if (node.type === 'end') {
          const o = { label: String(node.config.businessOutcome), kind: String(node.config.outcomeKind) }
          if (!outcomes.some((x) => x.label === o.label && x.kind === o.kind)) outcomes.push(o)
        }
      }
    }
    const repeat = n.config.repeat as { maxIterations: number; untilOutcome: string } | undefined
    const forEach = n.config.forEach as { itemsRef: string; itemInput: string; maxItems: number } | undefined
    const untilOutcome = repeat?.untilOutcome ?? null
    let defaultOutcome: ChildOutcome = { label: 'Fulfilled', kind: 'success' }
    if (outcomes.length > 0) {
      defaultOutcome = outcomes.find((o) => o.kind === 'success') ?? (outcomes[0] as ChildOutcome)
      if (untilOutcome !== null) {
        for (const o of outcomes) if (o.label === untilOutcome) defaultOutcome = o
      }
    }
    let items: unknown[] = []
    let planned = 1
    if (forEach) {
      const list = this.resolve(forEach.itemsRef)
      items = Array.isArray(list) ? [...list] : []
      planned = Math.min(items.length, forEach.maxItems)
    } else if (repeat) planned = repeat.maxIterations
    const outcomeList = Array.isArray(s.outcomes) ? s.outcomes : null
    const simulate = this.scenario.simulateChildren === true && child !== undefined && this.depth < MAX_CHILD_DEPTH
    const d: Entry = { workflow: ref, workflowName: childName, simulated: simulate }
    const occurrences: Json[] = []
    d.occurrences = occurrences
    const results: Record<string, unknown>[] = []
    const reached: ChildOutcome[] = []
    let allSucceeded = true
    const inputMapping = (n.config.inputMapping ?? {}) as Record<string, string>
    for (let i = 0; i < planned; i++) {
      let chosen = defaultOutcome
      if (outcomeList && i < outcomeList.length) {
        const label = String(outcomeList[i])
        if (outcomes.length === 0) chosen = { label, kind: 'success' }
        for (const o of outcomes) if (o.label === label) chosen = o
      }
      const childOutputs: Record<string, unknown> = {}
      const occ: Entry = { occurrence: i + 1 }
      occurrences.push(occ)
      if (simulate && child) {
        if (++this.budget.children > 25) {
          this.stallWith(n,'The test reached its 25 child-run budget.',d,'TEST_LIMIT','Nested child tests are bounded to 25 occurrences.')
          return null
        }
        const childInput: Record<string, unknown> = {}
        for (const field of Object.keys(inputMapping).sort()) {
          const v = this.resolve(inputMapping[field] as string)
          if (v !== null && v !== undefined) childInput[field] = v
        }
        if (forEach) childInput[forEach.itemInput] = items[i]
        const r = simulateDefinition(child, childInput, this.scenario.childScenarios?.[n.id] ?? {}, this.ctx, this.depth + 1, this.budget)
        if (r.status !== 'ok') {
          const message = `${quote(n.label)}: ${quote(childName)} could not be tested${r.status === 'invalidInput' && r.errors[0] ? `: ${r.errors[0].message}` : '.'}`
          this.stallWith(n, message, d, 'INTERNAL_ERROR', message)
          return null
        }
        const childOutcome = r.body.outcome as Entry
        occ.trace = r.body.trace as Json
        if (childOutcome.status !== 'finished') {
          occ.outcome = 'Needs attention'
          this.stallWith(n, `${quote(n.label)}: ${quote(childName)} would stop and need attention, so this request keeps waiting for it.`, d, 'CHILD_NEEDS_ATTENTION', 'The other workflow stops and needs attention.')
          return null
        }
        chosen = { label: String(childOutcome.businessOutcome), kind: String(childOutcome.outcomeKind) }
        Object.assign(childOutputs, r.body.outputs)
      } else if (child) {
        const props = (child.outputSchema.properties ?? {}) as unknown as Record<string, Record<string, unknown>>
        for (const name of Object.keys(props).sort()) childOutputs[name] = sample(props[name] as Record<string, unknown>, name)
      }
      if (s.results && typeof s.results === 'object') Object.assign(childOutputs, s.results)
      childOutputs.outcome = chosen.label
      childOutputs.outcomeKind = chosen.kind
      childOutputs.reference = `ORQ-TEST-${i + 1}`
      occ.outcome = chosen.label
      occ.outcomeKind = chosen.kind
      results.push(childOutputs)
      reached.push(chosen)
      allSucceeded = allSucceeded && chosen.kind === 'success'
      if (repeat && chosen.label === untilOutcome) break
    }
    const iterations = results.length
    const lastReached = reached[reached.length - 1]
    const limitReached = repeat !== undefined && (reached.length === 0 || (lastReached as ChildOutcome).label !== untilOutcome)
    const out: Record<string, unknown> = {}
    if (results.length > 0) {
      const last = results[results.length - 1] as Record<string, unknown>
      out.outcome = last.outcome
      out.outcomeKind = last.outcomeKind
      out.reference = last.reference
      const resultMapping = (n.config.resultMapping ?? {}) as Record<string, string>
      for (const name of Object.keys(resultMapping).sort()) {
        const v = last[resultMapping[name] as string]
        if (v !== undefined && v !== null) out[name] = v
      }
    }
    out.iterations = iterations
    out.limitReached = limitReached
    out.allSucceeded = allSucceeded
    const collection = (n.config.collectionMapping ?? {}) as Record<string, string>
    for (const name of Object.keys(collection).sort()) out[name] = results.map((r) => r[collection[name] as string] ?? null)
    d.iterations = iterations
    d.limitReached = limitReached
    d.allSucceeded = allSucceeded
    this.steps.set(n.id, out)
    const last = lastReached ? quote(lastReached.label) : 'nothing'
    const sentence =
      `${quote(n.label)}: starts ${quote(childName)}${iterations === 1 ? '' : ` ${count(iterations, 'time')}`}. ` +
      `${simulate ? 'In this test it runs to its end and finishes with ' : 'In this test it ends with '}${last}` +
      `${repeat && limitReached ? `, and the limit of ${count(repeat.maxIterations, 'attempt')} is reached.` : '.'}`
    const t = this.next(n, 'next')
    this.add(n, sentence, d, out, t)
    return t
  }

  // -------------------------------------------------------------------------------------------- trace plumbing

  private add(n: WorkflowNode, summary: string, details: Entry, wrote: Record<string, unknown> | null, taken: Transition | null): void {
    const e: Entry = { seq: this.trace.length + 1, stepId: n.id, stepLabel: n.label, kind: n.type, kindLabel: KIND_LABEL[n.type] ?? n.type }
    if (this.lane !== null) e.branch = this.lane
    e.summary = summary
    e.details = details
    if (wrote && Object.keys(wrote).length > 0) e.wrote = wrote as Json
    e.transitionId = taken ? taken.id : null
    e.nextStepId = taken ? taken.to : null
    this.trace.push(e)
  }

  private stallWith(n: WorkflowNode, summary: string, details: Entry, reason: string, message: string): void {
    details.attention = { reason, message }
    this.add(n, summary, details, null, null)
    this.stall(n, reason, message)
  }

  private stall(n: WorkflowNode, reason: string, message: string): void {
    this.attention ??= { stepId: n.id, stepLabel: n.label, reason, message }
  }

  // -------------------------------------------------------------------------------------------- values

  private info(ref: string): { label: string; money: boolean } {
    const tokens = parsePointer(ref)
    if (!tokens) return { label: ref, money: false }
    if (tokens[0] === 'input' && tokens.length > 2) {
      const list = this.def.inputSchema.properties[tokens[1] ?? '']
      const title = list?.title ?? tokens[1]
      if (tokens[2] === 'count') return { label: `${title}: count`, money: false }
      const column = list?.items?.properties?.[tokens[3] ?? '']
      if (tokens[2] === 'sum') return { label: `${title}: total ${column?.title ?? tokens[3]}`, money: false }
    }
    if (tokens.length === 2 && tokens[0] === 'input') {
      const key = tokens[1] as string
      const field = this.def.inputSchema.properties[key]
      const money = this.def.inputUi?.widgets?.[key] === 'money'
      return { label: text.fieldLabel(field ? field.title : key, money), money }
    }
    if (tokens.length === 3 && tokens[0] === 'steps') {
      const step = this.byId.get(tokens[1] as string)
      return { label: `${step ? step.label : tokens[1]} / ${tokens[2]}`, money: false }
    }
    return { label: ref, money: false }
  }

  private resolve(ref: string): unknown {
    const aggregate = resolveInputAggregate(this.input, ref)
    if (aggregate !== undefined) return aggregate
    const tokens = parsePointer(ref)
    if (!tokens) return null
    if (tokens.length === 2 && tokens[0] === 'input') return this.input[tokens[1] as string] ?? null
    if (tokens.length === 3 && tokens[0] === 'steps') return this.steps.get(tokens[1] as string)?.[tokens[2] as string] ?? null
    return null
  }

  private conditionText(c: Condition, withValue: boolean, known: unknown): string {
    if (!isAtomicCondition(c)) {
      const all = 'all' in c
      const parts = ((all ? c.all : c.any) ?? []).map((t) => this.conditionText(t, withValue, undefined))
      return `${all ? 'all of these are true: ' : 'at least one of these is true: '}${parts.join('; ')}`
    }
    const f = this.info(c.fieldRef)
    const actual = known !== undefined && known !== null ? known : withValue ? this.resolve(c.fieldRef) : null
    const left = withValue && actual !== null && actual !== undefined ? `${f.label} (${text.value(actual, f.money)})` : f.label
    return `${left} ${OPERATOR_PHRASE[c.operator]} ${text.value(c.value, f.money)}`
  }

  // -------------------------------------------------------------------------------------------- warnings and coverage

  private warn(code: string, stepId: string | null, message: string): void {
    this.warnings.push({ code, stepId, message })
  }

  private collectWarnings(): void {
    for (const issue of this.tolerated) this.warn(issue.code, issue.nodeId ?? null, issue.message)
    for (const issue of this.ctx.issues) if (issue.severity === 'warning') this.warn(issue.code, issue.nodeId ?? null, issue.message)
    for (const table of this.def.decisions) {
      for (const [i, row] of table.rows.entries()) {
        const why = isAtomicCondition(row.when) ? this.impossible(row.when) : null
        if (why !== null) this.warn('RULE_NEVER_MATCHES', this.decisionStep(table.key), `Rule ${i + 1} of ${quote(table.key)} can never apply: ${why}`)
      }
    }
  }

  private decisionStep(key: string): string | null {
    return this.nodes.find((n) => n.type === 'decision' && n.config.decisionRef === key)?.id ?? null
  }

  private impossible(c: AtomicCondition): string | null {
    const tokens = parsePointer(c.fieldRef)
    if (!tokens || tokens.length !== 2 || tokens[0] !== 'input') return null
    const field = this.def.inputSchema.properties[tokens[1] as string]
    if (!field) return null
    const f = this.info(c.fieldRef)
    if (typeof c.value === 'number') {
      const v = c.value
      const min = field.minimum
      const max = field.maximum
      let never = false
      switch (c.operator) {
        case 'eq':
          never = (min !== undefined && v < min) || (max !== undefined && v > max)
          break
        case 'gte':
          never = max !== undefined && v > max
          break
        case 'gt':
          never = max !== undefined && v >= max
          break
        case 'lt':
          never = min !== undefined && v <= min
          break
        case 'lte':
          never = min !== undefined && v < min
          break
        default:
          never = false
      }
      if (never) {
        return `${f.label} ${OPERATOR_PHRASE[c.operator]} ${text.value(v, f.money)}, but the request form only allows ${min === undefined ? '' : `at least ${text.value(min, f.money)}`}${min !== undefined && max !== undefined ? ' and ' : ''}${max === undefined ? '' : `at most ${text.value(max, f.money)}`}.`
      }
    }
    if (typeof c.value === 'string' && c.operator === 'eq' && field.enum && !field.enum.includes(c.value)) return `${f.label} is never ${quote(c.value)}: the request form only offers ${field.enum.join(', ')}.`
    return null
  }

  private scenarioWarnings(): void {
    const groups: Array<[keyof SimulationScenario, string]> = [
      ['approvals', 'approval'],
      ['calls', 'http'],
      ['children', 'startWorkflow'],
      ['messages', 'waitForMessage'],
      ['workers' as keyof SimulationScenario, 'externalTask'],
    ]
    for (const [group, kind] of groups) {
      for (const id of Object.keys((this.scenario[group] as Record<string, unknown> | undefined) ?? {})) {
        const n = this.byId.get(id)
        if (!n || n.type !== kind) this.warn('SCENARIO_UNKNOWN_STEP', null, `The test settings mention the step ${quote(id)}, which is not a matching step of this workflow. They were ignored.`)
      }
    }
  }

  private coverage(): Entry {
    const real = this.nodes.filter((n) => !this.shadowed.has(n))
    const seen = real.filter((n) => this.visited.has(n.id)).length
    const notVisited = real.filter((n) => !this.visited.has(n.id)).map((n) => ({ id: n.id, label: n.label }))
    let total = 0
    let tested = 0
    const notTaken: Json[] = []
    for (const t of this.resolved) {
      const from = this.byId.get(t.from) as WorkflowNode
      if ((this.out.get(from.id) ?? []).length < 2 || from.type === 'parallelSplit') continue
      total++
      if (this.taken.has(t.id)) tested++
      else notTaken.push({ transitionId: t.id, from: t.from, to: t.to, description: this.routeDescription(t) })
    }
    let ruleTotal = 0
    let ruleHit = 0
    const notMatched: Json[] = []
    for (const table of this.def.decisions) {
      if (this.decisionStep(table.key) === null) continue
      for (const [i, row] of table.rows.entries()) {
        ruleTotal++
        if (this.matchedRules.has(`${table.key}#${i}`)) ruleHit++
        else notMatched.push({ decision: table.key, row: `Rule ${i + 1}`, condition: this.conditionText(row.when, false, undefined) })
      }
      ruleTotal++
      if (this.matchedRules.has(`${table.key}#default`)) ruleHit++
      else notMatched.push({ decision: table.key, row: 'Otherwise', condition: null })
    }
    return {
      steps: { visited: seen, total: real.length, notVisited },
      routes: { notTaken, taken: tested, total },
      rules: { notMatched, matched: ruleHit, total: ruleTotal },
      summary: `Tested ${seen} of ${real.length} steps, ${tested} of ${total} routes and ${ruleHit} of ${ruleTotal} decision rules.`,
    }
  }

  private routeDescription(t: Transition): string {
    const from = this.byId.get(t.from) as WorkflowNode
    const to = this.byId.get(t.to) as WorkflowNode
    const when = { approved: 'when approved', rejected: 'when rejected', timedOut: 'when the time limit fires', failed: 'when the step fails', default: 'otherwise', condition: `when ${this.routeName(t)}`, next: 'next' }[t.on]
    return `${from.label}, ${when}, goes to ${to.label}`
  }
}

const KIND_LABEL: Record<string, string> = {
  start: 'Request form',
  approval: 'Ask for approval',
  decision: 'Make a decision',
  branch: 'Choose a path',
  http: 'Call a system',
  notification: 'Notify someone',
  generateDocument: 'Generate a document',
  parallelSplit: 'Run in parallel',
  parallelJoin: 'Wait for all',
  wait: 'Wait',
  waitForMessage: 'Wait for a message',
  startWorkflow: 'Start another workflow',
  end: 'Finish',
}

function kindWord(kind: string): string {
  return kind === 'success' ? 'successful' : kind
}

function sortedLiterals(map: Record<string, Literal>): Record<string, Json> {
  const out: Record<string, Json> = {}
  for (const key of Object.keys(map).sort()) out[key] = map[key] as Json
  return out
}

function outputsTextOf(out: Record<string, unknown>): string {
  return Object.entries(out)
    .map(([k, v]) => `${k} = ${text.value(v, false)}`)
    .join(', ')
}

function pointer(body: unknown, ref: string): unknown {
  const tokens = parsePointer(ref)
  let current: unknown = body
  for (const token of tokens ?? []) {
    current = current !== null && typeof current === 'object' && !Array.isArray(current) ? (current as Record<string, unknown>)[token] : undefined
  }
  return current
}

/** A scenario member of the wrong type; the API answers 422 with the message. */
function validateScenario(scenario:SimulationScenario,level=0):void {
  if(!scenario || typeof scenario!=='object' || Array.isArray(scenario) || Object.keys(scenario).length>20 || level>MAX_CHILD_DEPTH || new TextEncoder().encode(JSON.stringify(scenario)).length>64*1024) throw new ScenarioError('Scenario settings exceed their bounded shape')
  for(const group of ['approvals','calls','children','messages','childScenarios'] as const) {
    const entries=scenario[group]
    if(entries===undefined) continue
    if(!entries || typeof entries!=='object' || Array.isArray(entries) || Object.keys(entries).length>100) throw new ScenarioError(`${group} must contain at most 100 step settings`)
    if(group==='childScenarios') for(const value of Object.values(entries)) validateScenario(value as SimulationScenario,level+1)
    if(group==='children') for(const value of Object.values(entries)) { const outcomes=(value as {outcomes?:unknown})?.outcomes; if(outcomes!==undefined && (!Array.isArray(outcomes) || outcomes.length>25)) throw new ScenarioError('Child outcomes are bounded to 25 occurrences') }
  }
}

export class ScenarioError extends Error {}

// ------------------------------------------------------------------------------------------------ entry point

/**
 * Dry run of a definition. Throws {@link ScenarioError} for a malformed scenario.
 */
export function simulateDefinition(def: Definition, input: Record<string, unknown>, scenario: SimulationScenario, ctx: SimContext, depth = 0, budget:Budget={nodes:0,children:0}): SimulationOutcome {
  validateScenario(scenario)
  const blockers = ctx.issues.filter((i) => i.severity === 'blocker' && !TOLERATED.has(i.code))
  if (blockers.length > 0 || !def.nodes.some((n) => n.type === 'start')) return { status: 'cannotRun', issues: blockers }
  const errors = ctx.validateInput(def, input)
  if (errors.length > 0) return { status: 'invalidInput', errors }
  const tolerated = ctx.issues.filter((i) => i.severity === 'blocker' && TOLERATED.has(i.code))
  // A child workflow is tested with its own validation issues.
  const run = new Run(def, input, scenario, depth === 0 ? ctx : { ...ctx, issues: [] }, depth, depth === 0 ? tolerated : [],budget)
  return { status: 'ok', body: run.execute() }
}
