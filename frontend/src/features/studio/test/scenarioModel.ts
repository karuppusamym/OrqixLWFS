import type { SimulationScenario } from '@/api/types'
import type { Definition, WorkflowNode } from '../model/types'

/** What the designer chooses for each step that a person or another system would decide (business wording in the UI). */
export interface TestSettings {
  approvals: Record<string, { outcome: 'approve' | 'reject' | 'timeout'; reason: string }>
  calls: Record<string, { result: 'works' | 'rejected' | 'unavailable' | 'unknownOutcome'; answers: Record<string, string> }>
  children: Record<string, { outcomes: string[] }>
  messages: Record<string, 'arrives' | 'timeout'>
  simulateChildren: boolean
}

export const emptySettings = (): TestSettings => ({ approvals: {}, calls: {}, children: {}, messages: {}, simulateChildren: false })

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
  operation: string
  /** Result fields the workflow keeps, with the pointer into the answer of the system. */
  results: Array<{ key: string; pointer: string }>
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
}

export interface StepControls {
  approvals: ApprovalControl[]
  calls: CallControl[]
  children: ChildControl[]
  messages: MessageControl[]
}

/** The steps whose outcome the designer can choose, in document order. */
export function stepControls(def: Definition): StepControls {
  const controls: StepControls = { approvals: [], calls: [], children: [], messages: [] }
  for (const n of def.nodes as WorkflowNode[]) {
    if (n.type === 'approval') {
      controls.approvals.push({ id: n.id, label: n.label, groupRef: String(n.config.candidateGroupRef ?? ''), hasTimeout: typeof n.config.timeoutAfter === 'string' })
    } else if (n.type === 'http') {
      const mapping = (n.config.resultMapping ?? {}) as Record<string, string>
      controls.calls.push({
        id: n.id,
        label: n.label,
        connection: String(n.config.connectionRef ?? ''),
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
      controls.messages.push({ id: n.id, label: n.label })
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
    if (s.result !== 'works') calls[c.id] = { failure: s.result }
    else {
      const body = answerBody(c.results, s.answers)
      if (body) calls[c.id] = { result: body }
    }
  }
  if (Object.keys(calls).length > 0) scenario.calls = calls
  const children: NonNullable<SimulationScenario['children']> = {}
  for (const c of controls.children) {
    const outcomes = (settings.children[c.id]?.outcomes ?? []).slice(0, c.max)
    // Empty entries mean "the usual outcome", but trailing ones need no mention.
    while (outcomes.length > 0 && (outcomes[outcomes.length - 1] ?? '').trim() === '') outcomes.pop()
    if (outcomes.length > 0 && outcomes.some((o) => o.trim() !== '')) children[c.id] = { outcomes: outcomes.map((o) => o.trim()) }
  }
  if (Object.keys(children).length > 0) scenario.children = children
  const messages: NonNullable<SimulationScenario['messages']> = {}
  for (const c of controls.messages) if (settings.messages[c.id] === 'timeout') messages[c.id] = 'timeout'
  if (Object.keys(messages).length > 0) scenario.messages = messages
  if (settings.simulateChildren && controls.children.length > 0) scenario.simulateChildren = true
  return scenario
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
