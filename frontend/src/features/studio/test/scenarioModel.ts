import type { SimulationAssertions, SimulationScenario } from '@/api/types'
import type { Definition, WorkflowNode } from '../model/types'

/**
 * A mocked answer of a call (STU03): an HTTP status, the full response body as JSON text, and how long it takes. Text
 * as typed, so a half-written body survives; {@link toScenario} sends only what parses.
 */
export interface CallMock {
  status: string
  body: string
  delaySeconds: string
}

export type CallResult = 'works' | 'rejected' | 'unavailable' | 'unknownOutcome' | 'timeout'

/** What the designer chooses for each step that a person or another system would decide (business wording in the UI). */
export interface TestSettings {
  approvals: Record<string, { outcome: 'approve' | 'reject' | 'timeout'; reason: string }>
  calls: Record<string, { result: CallResult; answers: Record<string, string>; mock?: CallMock }>
  children: Record<string, { outcomes: string[] }>
  messages: Record<string, 'arrives' | 'timeout'>
  /** Payload values and delay of a message (STU03). */
  messageMocks: Record<string, { answers: Record<string, string>; delaySeconds: string }>
  /** External workers (STU03): their result, or a failure. */
  workers: Record<string, { result: 'works' | 'failed'; answers: Record<string, string>; delaySeconds: string }>
  /** Adapter steps (STU03): delivered (with rows for a database read), or a failure. */
  adapters: Record<string, { result: 'works' | 'unavailable' | 'unknownOutcome'; rowCount: string }>
  simulateChildren: boolean
}

export const emptySettings = (): TestSettings => ({ approvals: {}, calls: {}, children: {}, messages: {}, messageMocks: {}, workers: {}, adapters: {}, simulateChildren: false })

export interface ApprovalControl {
  id: string
  label: string
  groupRef: string
  hasTimeout: boolean
}
export interface CallControl {
  id: string
  label: string
  connection: string
  /** The pinned version of the connection, for its response schema. */
  connectionVersion: number | null
  operation: string
  timeoutSeconds: number
  /** Result fields the workflow keeps, with the pointer into the answer of the system. */
  results: Array<{ key: string; pointer: string }>
}
export interface PayloadControl {
  id: string
  label: string
  fields: Array<{ name: string; type: string; title: string }>
}
export interface AdapterControl {
  id: string
  label: string
  kind: string
  adapter: string
}
export interface ChildControl {
  id: string
  label: string
  workflowRef: string
  /** Most occurrences: the repeat bound or the largest collection; 1 for a single start. */
  max: number
  mode: 'once' | 'repeat' | 'each'
  untilOutcome: string | null
}
export interface MessageControl {
  id: string
  label: string
  fields: PayloadControl['fields']
}

export interface StepControls {
  approvals: ApprovalControl[]
  calls: CallControl[]
  children: ChildControl[]
  messages: MessageControl[]
  workers: PayloadControl[]
  adapters: AdapterControl[]
  /** Decision steps, for rule assertions. */
  decisions: Array<{ id: string; label: string; decisionRef: string; rows: number }>
}

const payloadFields = (raw: unknown): PayloadControl['fields'] =>
  (Array.isArray(raw) ? raw : []).filter((f): f is Record<string, unknown> => typeof f === 'object' && f !== null).map((f) => ({ name: String(f.name ?? ''), type: String(f.type ?? 'string'), title: String(f.title ?? f.name ?? '') }))

/** The steps whose outcome the designer can choose, in document order. */
export function stepControls(def: Definition): StepControls {
  const controls: StepControls = { approvals: [], calls: [], children: [], messages: [], workers: [], adapters: [], decisions: [] }
  for (const n of def.nodes as WorkflowNode[]) {
    if (n.type === 'approval') {
      controls.approvals.push({ id: n.id, label: n.label, groupRef: String(n.config.candidateGroupRef ?? ''), hasTimeout: typeof n.config.timeoutAfter === 'string' })
    } else if (n.type === 'decision') {
      const table = def.decisions.find((d) => d.key === n.config.decisionRef)
      controls.decisions.push({ id: n.id, label: n.label, decisionRef: String(n.config.decisionRef ?? ''), rows: table?.rows.length ?? 0 })
    } else if (n.type === 'externalTask') {
      controls.workers.push({ id: n.id, label: n.label, fields: payloadFields(n.config.result) })
    } else if (n.type === 'sendAdapter') {
      controls.adapters.push({ id: n.id, label: n.label, kind: String(n.config.kind ?? ''), adapter: String(n.config.adapter ?? '') })
    } else if (n.type === 'http') {
      const mapping = (n.config.resultMapping ?? {}) as Record<string, string>
      const connection = String(n.config.connectionRef ?? '')
      const pinned = (def.dependencies?.connections ?? []).find((c) => c.key === connection)
      controls.calls.push({
        id: n.id,
        label: n.label,
        connection,
        connectionVersion: pinned?.definitionVersion ?? null,
        timeoutSeconds: typeof n.config.timeoutSeconds === 'number' && n.config.timeoutSeconds > 0 ? n.config.timeoutSeconds : 30,
        operation: String(n.config.operation ?? ''),
        results: Object.keys(mapping)
          .sort()
          .map((key) => ({ key, pointer: String(mapping[key]) })),
      })
    } else if (n.type === 'startWorkflow') {
      const repeat = n.config.repeat as { maxIterations?: number; untilOutcome?: string } | undefined
      const each = n.config.forEach as { maxItems?: number } | undefined
      controls.children.push({
        id: n.id,
        label: n.label,
        workflowRef: String(n.config.workflowRef ?? ''),
        max: each?.maxItems ?? repeat?.maxIterations ?? 1,
        mode: each ? 'each' : repeat ? 'repeat' : 'once',
        untilOutcome: repeat?.untilOutcome ?? null,
      })
    } else if (n.type === 'waitForMessage') {
      controls.messages.push({ id: n.id, label: n.label, fields: payloadFields(n.config.payload) })
    }
  }
  return controls
}

function typed(text: string): unknown {
  const t = text.trim()
  if (t === 'true') return true
  if (t === 'false') return false
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t)
  return text
}

/** Builds the answer of a system from "result field" answers: {"/id": "PO-1"} becomes {"id": "PO-1"}. */
export function answerBody(results: CallControl['results'], answers: Record<string, string>): Record<string, unknown> | null {
  let body: Record<string, unknown> | null = null
  for (const r of results) {
    const text = answers[r.key]
    if (text === undefined || text.trim() === '') continue
    body ??= {}
    const tokens = r.pointer.split('/').slice(1).map((t) => t.replace(/~1/g, '/').replace(/~0/g, '~'))
    let target: Record<string, unknown> = body
    tokens.forEach((token, i) => {
      if (i === tokens.length - 1) target[token] = typed(text)
      else target = (target[token] ??= {}) as Record<string, unknown>
    })
  }
  return body
}

/** The scenario the API takes: only what differs from the defaults, and only for steps that still exist. */
export function toScenario(def: Definition, settings: TestSettings): SimulationScenario {
  const controls = stepControls(def)
  const scenario: SimulationScenario = {}
  const approvals: NonNullable<SimulationScenario['approvals']> = {}
  for (const c of controls.approvals) {
    const s = settings.approvals[c.id]
    if (!s || s.outcome === 'approve') continue
    if (s.outcome === 'timeout' && !c.hasTimeout) continue
    approvals[c.id] = s.outcome === 'reject' && s.reason.trim() !== '' ? { outcome: 'reject', reason: s.reason.trim() } : { outcome: s.outcome }
  }
  if (Object.keys(approvals).length > 0) scenario.approvals = approvals
  const calls: NonNullable<SimulationScenario['calls']> = {}
  for (const c of controls.calls) {
    const s = settings.calls[c.id]
    if (!s) continue
    const mock = mockOf(s.mock)
    if (s.result !== 'works') calls[c.id] = { failure: s.result, ...(mock.delaySeconds !== undefined ? { delaySeconds: mock.delaySeconds } : {}) }
    else if (mock.status !== undefined || mock.body !== undefined || mock.delaySeconds !== undefined) {
      const entry = { ...mock } as NonNullable<SimulationScenario['calls']>[string]
      // Answers for result fields still apply when no full body is given.
      const partial = mock.body === undefined ? answerBody(c.results, s.answers) : null
      if (partial) entry.result = partial
      calls[c.id] = entry
    } else {
      const body = answerBody(c.results, s.answers)
      if (body) calls[c.id] = { result: body }
    }
  }
  if (Object.keys(calls).length > 0) scenario.calls = calls
  const workers: NonNullable<SimulationScenario['workers']> = {}
  for (const c of controls.workers) {
    const s = settings.workers[c.id]
    if (!s) continue
    const delay = wholeNumber(s.delaySeconds)
    if (s.result === 'failed') workers[c.id] = { failure: 'failed' }
    else {
      const result = typedAnswers(c.fields, s.answers)
      if (result || delay !== undefined) workers[c.id] = { ...(result ? { result } : {}), ...(delay !== undefined ? { delaySeconds: delay } : {}) }
    }
  }
  if (Object.keys(workers).length > 0) scenario.workers = workers
  const adapters: NonNullable<SimulationScenario['adapters']> = {}
  for (const c of controls.adapters) {
    const s = settings.adapters[c.id]
    if (!s) continue
    if (s.result !== 'works') adapters[c.id] = { failure: s.result }
    else if (c.kind === 'jdbc' && wholeNumber(s.rowCount) !== undefined) adapters[c.id] = { rowCount: wholeNumber(s.rowCount) as number }
  }
  if (Object.keys(adapters).length > 0) scenario.adapters = adapters
  const children: NonNullable<SimulationScenario['children']> = {}
  for (const c of controls.children) {
    const outcomes = (settings.children[c.id]?.outcomes ?? []).slice(0, c.max)
    // Empty entries mean "the usual outcome", but trailing ones need no mention.
    while (outcomes.length > 0 && (outcomes[outcomes.length - 1] ?? '').trim() === '') outcomes.pop()
    if (outcomes.length > 0 && outcomes.some((o) => o.trim() !== '')) children[c.id] = { outcomes: outcomes.map((o) => o.trim()) }
  }
  if (Object.keys(children).length > 0) scenario.children = children
  const messages: NonNullable<SimulationScenario['messages']> = {}
  for (const c of controls.messages) {
    const outcome = settings.messages[c.id] === 'timeout' ? 'timeout' : 'arrives'
    const m = settings.messageMocks[c.id]
    const payload = m && outcome === 'arrives' ? typedAnswers(c.fields, m.answers) : null
    const delay = m ? wholeNumber(m.delaySeconds) : undefined
    if (payload || delay !== undefined) messages[c.id] = { outcome, ...(payload ? { payload } : {}), ...(delay !== undefined ? { delaySeconds: delay } : {}) }
    else if (outcome === 'timeout') messages[c.id] = 'timeout'
  }
  if (Object.keys(messages).length > 0) scenario.messages = messages
  if (settings.simulateChildren && controls.children.length > 0) scenario.simulateChildren = true
  return scenario
}

const wholeNumber = (text: string | undefined): number | undefined => (text !== undefined && /^\d{1,7}$/.test(text.trim()) ? Number(text.trim()) : undefined)

/** The JSON body of a mock, or an error message; empty text is "no body". */
export function parseBody(text: string): { body?: unknown; error?: string } {
  if (text.trim() === '') return {}
  try {
    const body: unknown = JSON.parse(text)
    if (body === null || typeof body !== 'object') return { error: 'The answer must be a JSON object or list.' }
    return { body }
  } catch {
    return { error: 'This is not valid JSON.' }
  }
}

function mockOf(mock: CallMock | undefined): { status?: number; body?: unknown; delaySeconds?: number } {
  if (!mock) return {}
  const out: { status?: number; body?: unknown; delaySeconds?: number } = {}
  const status = wholeNumber(mock.status)
  if (status !== undefined && status >= 200 && status <= 599) out.status = status
  const parsed = parseBody(mock.body)
  if (parsed.body !== undefined) out.body = parsed.body
  const delay = wholeNumber(mock.delaySeconds)
  if (delay !== undefined) out.delaySeconds = delay
  return out
}

/** Typed values for declared fields: numbers and true/false where declared, text otherwise; empty answers left out. */
function typedAnswers(fields: PayloadControl['fields'], answers: Record<string, string>): Record<string, unknown> | null {
  let out: Record<string, unknown> | null = null
  for (const f of fields) {
    const text = answers[f.name]
    if (text === undefined || text.trim() === '') continue
    out ??= {}
    const t = text.trim()
    out[f.name] = (f.type === 'number' || f.type === 'integer') && /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : f.type === 'boolean' && (t === 'true' || t === 'false') ? t === 'true' : text
  }
  return out
}

// ------------------------------------------------------------------------------------------------ assertions (STU03)

export type OutputOp = 'equals' | 'contains' | 'range'

/** What a saved case checks besides its expected outcome; text as typed, converted by {@link toAssertions}. */
export interface CaseAssertions {
  outputs: Array<{ field: string; op: OutputOp; value: string; min: string; max: string }>
  path: { steps: string[]; match: 'exact' | 'inOrder' } | null
  rules: Array<{ stepId: string; rule: string }>
  approvals: Array<{ stepId: string; decision: 'approved' | 'rejected' | 'timedOut' }>
}

export const emptyAssertions = (): CaseAssertions => ({ outputs: [], path: null, rules: [], approvals: [] })

export type ApiAssertions = SimulationAssertions

const typedValue = (text: string): string | number | boolean => {
  const t = text.trim()
  if (t === 'true') return true
  if (t === 'false') return false
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t)
  return text
}

/** The assertions the API takes: the expected outcome plus the complete items (half-filled rows are left out). */
export function toAssertions(a: CaseAssertions, expect: string | null): ApiAssertions | undefined {
  const out: ApiAssertions = {}
  if (expect) out.outcome = expect
  const outputs = a.outputs
    .filter((o) => o.field.trim() !== '')
    .flatMap((o): Array<NonNullable<ApiAssertions['outputs']>[number]> => {
      if (o.op === 'range') {
        const min = o.min.trim() === '' ? undefined : Number(o.min)
        const max = o.max.trim() === '' ? undefined : Number(o.max)
        if ((min === undefined || Number.isNaN(min)) && (max === undefined || Number.isNaN(max))) return []
        return [{ field: o.field.trim(), op: o.op, ...(min !== undefined && !Number.isNaN(min) ? { min } : {}), ...(max !== undefined && !Number.isNaN(max) ? { max } : {}) }]
      }
      return o.value === '' ? [] : [{ field: o.field.trim(), op: o.op, value: typedValue(o.value) }]
    })
  if (outputs.length > 0) out.outputs = outputs
  if (a.path && a.path.steps.length > 0) out.path = { steps: a.path.steps, match: a.path.match }
  const rules = a.rules.filter((r) => r.stepId !== '' && (r.rule === 'otherwise' || /^\d+$/.test(r.rule))).map((r) => ({ stepId: r.stepId, rule: r.rule === 'otherwise' ? ('otherwise' as const) : Number(r.rule) }))
  if (rules.length > 0) out.rules = rules
  const approvals = a.approvals.filter((r) => r.stepId !== '')
  if (approvals.length > 0) out.approvals = approvals
  return Object.keys(out).length === 0 ? undefined : out
}

export const countAssertions = (a: CaseAssertions, expect: string | null): number => {
  const api = toAssertions(a, expect)
  return api ? (api.outcome ? 1 : 0) + (api.outputs?.length ?? 0) + (api.path ? 1 : 0) + (api.rules?.length ?? 0) + (api.approvals?.length ?? 0) : 0
}

/** Does the run end the way the designer expects? `expect` is a business outcome or {@link NEEDS_ATTENTION}. */
export const NEEDS_ATTENTION = '__needsAttention__'

export function meetsExpectation(outcome: { status: string; businessOutcome?: string | null }, expectation: string | null): boolean {
  if (!expectation) return true
  if (expectation === NEEDS_ATTENTION) return outcome.status === 'needsAttention'
  return outcome.status === 'finished' && outcome.businessOutcome === expectation
}

export function expectationLabel(expectation: string | null): string {
  if (!expectation) return 'No expectation'
  return expectation === NEEDS_ATTENTION ? 'Needs attention' : expectation
}

/** Business outcomes the Finish steps can produce. */
export function finishOutcomes(def: Definition): string[] {
  const seen: string[] = []
  for (const n of def.nodes) if (n.type === 'end' && typeof n.config.businessOutcome === 'string' && !seen.includes(n.config.businessOutcome)) seen.push(n.config.businessOutcome)
  return seen
}
