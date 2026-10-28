import type { Definition, FieldSchemaDoc, Transition, WorkflowNode } from '@/features/studio/model/types'

/**
 * "Answer a few questions": the plain, deterministic way to start a workflow. Three answers (a name, what the
 * requester fills in, who approves) become an ordinary draft. No AI is involved and nothing here is Tier-1 specific;
 * deadlines, emails and anything else are added afterwards in the studio.
 */
export type QuestionType = 'text' | 'number' | 'yesNo' | 'date'

export interface QuickQuestion { label: string; type: QuestionType; required: boolean }
export interface QuickApproval { label: string; group: string; /** Index of a required number question; absent = always. */ whenQuestion?: number; atLeast?: number }
export interface QuickAnswers { name: string; purpose: string; questions: QuickQuestion[]; approvals: QuickApproval[] }

export const MAX_QUESTIONS = 15
export const MAX_APPROVALS = 5

export const QUESTION_TYPES: Array<{ value: QuestionType; label: string }> = [
  { value: 'text', label: 'Text' },
  { value: 'number', label: 'Number' },
  { value: 'yesNo', label: 'Yes or no' },
  { value: 'date', label: 'Date' },
]

/** Field keys are derived from the labels, so nobody has to invent one: "Cost center" becomes costCenter. */
export function fieldKeys(questions: QuickQuestion[]): string[] {
  const used = new Set<string>()
  return questions.map((q, i) => {
    const words = q.label.normalize('NFKD').replace(/\p{M}/gu, '').replace(/ß/g, 'ss').replace(/[^A-Za-z0-9]+/g, ' ').trim().split(/\s+/).filter(Boolean)
    let base = words.map((w, j) => (j === 0 ? w.toLowerCase() : w[0]!.toUpperCase() + w.slice(1).toLowerCase())).join('')
    if (!/^[a-z]/.test(base)) base = `field${base ? base[0]!.toUpperCase() + base.slice(1) : i + 1}`
    base = base.slice(0, 60)
    let key = base
    for (let n = 2; used.has(key); n++) key = `${base}${n}`
    used.add(key)
    return key
  })
}

/** The first problem with the answers of one step (1, 2 or 3), or null when the step is complete. */
export function stepProblem(step: number, a: QuickAnswers): string | null {
  if (step === 1 && !a.name.trim()) return 'Give the workflow a name.'
  if (step === 2) {
    if (a.questions.length === 0) return 'Add at least one question for the requester.'
    if (a.questions.some((q) => !q.label.trim())) return 'Every question needs a label.'
    const labels = a.questions.map((q) => q.label.trim().toLowerCase())
    if (new Set(labels).size !== labels.length) return 'Two questions have the same label. Make each one different.'
  }
  if (step === 3) {
    if (a.approvals.some((s) => !s.label.trim())) return 'Every approval needs a name.'
    if (a.approvals.some((s) => !s.group)) return 'Choose who approves each step.'
    for (const s of a.approvals) {
      if (s.whenQuestion === undefined) continue
      const q = a.questions[s.whenQuestion]
      if (!q || q.type !== 'number' || !q.required) return 'A step that runs "only when" needs a required number question.'
      if (!Number.isFinite(s.atLeast)) return 'Enter the number the answer must reach.'
    }
  }
  return null
}

const SCHEMA: Record<QuestionType, (title: string) => FieldSchemaDoc> = {
  text: (title) => ({ type: 'string', title, maxLength: 500 }),
  number: (title) => ({ type: 'number', title }),
  yesNo: (title) => ({ type: 'boolean', title }),
  date: (title) => ({ type: 'string', title, format: 'date' }),
}

/** Builds the draft: start, then each approval in order (a rejection ends the request), then the outcome. */
export function quickGuideDefinition(a: QuickAnswers, workflowKey: string): Definition {
  for (const step of [1, 2, 3]) {
    const problem = stepProblem(step, a)
    if (problem) throw new Error(problem)
  }
  const keys = fieldKeys(a.questions)
  const properties: Record<string, FieldSchemaDoc> = {}
  a.questions.forEach((q, i) => { properties[keys[i]!] = SCHEMA[q.type](q.label.trim()) })

  const nodes: WorkflowNode[] = [{ id: 'start', type: 'start', label: 'Request submitted', config: {} }]
  const transitions: Transition[] = []
  const edge = (from: string, to: string, on: Transition['on'], extra: Partial<Transition> = {}) =>
    transitions.push({ id: `t${transitions.length + 1}`, from, to, on, ...extra })
  const done = a.approvals.length > 0 ? 'approved' : 'finished'
  const entry = (i: number) => (i >= a.approvals.length ? done : a.approvals[i]!.whenQuestion !== undefined ? `check${i + 1}` : `approval${i + 1}`)

  edge('start', entry(0), 'next')
  a.approvals.forEach((s, i) => {
    const id = `approval${i + 1}`
    if (s.whenQuestion !== undefined) {
      const q = a.questions[s.whenQuestion]!
      nodes.push({ id: `check${i + 1}`, type: 'branch', label: `Does ${s.label.trim()} apply?`.slice(0, 80), config: {} })
      edge(`check${i + 1}`, id, 'condition', {
        label: `${q.label.trim()} at least ${s.atLeast}`.slice(0, 80),
        condition: { fieldRef: `/input/${keys[s.whenQuestion]}`, operator: 'gte', value: s.atLeast! },
      })
      edge(`check${i + 1}`, entry(i + 1), 'default')
    }
    nodes.push({ id, type: 'approval', label: s.label.trim(), config: { candidateGroupRef: s.group, rejectReasonRequired: true } })
    edge(id, entry(i + 1), 'approved')
    edge(id, 'rejected', 'rejected')
  })
  if (a.approvals.length > 0) {
    nodes.push({ id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } })
    nodes.push({ id: 'rejected', type: 'end', label: 'Rejected', config: { businessOutcome: 'Rejected', outcomeKind: 'rejected' } })
  } else {
    nodes.push({ id: 'finished', type: 'end', label: 'Finished', config: { businessOutcome: 'Completed', outcomeKind: 'success' } })
  }

  return {
    schemaVersion: '0.1',
    workflowKey,
    name: a.name.trim(),
    metadata: { description: a.purpose.trim() },
    inputSchema: { type: 'object', additionalProperties: false, properties, required: keys.filter((_, i) => a.questions[i]!.required) },
    inputUi: { order: keys, widgets: {}, currency: 'USD' },
    outputSchema: { type: 'object', additionalProperties: false, properties: {} },
    triggers: ['manual'],
    nodes,
    transitions,
    decisions: [],
    execution: { maxConcurrentRuns: 500, maxAttempts: 3, retryDelaySeconds: 30 },
    security: { selfApproval: 'deny' },
    dependencies: { connections: [] },
    outputMapping: {},
  }
}
