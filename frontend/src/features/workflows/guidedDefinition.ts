import type { Definition, FieldSchemaDoc, StageEmail } from '@/features/studio/model/types'

export interface GuidedStep { label: string; group: string; minutes: number; conditionField?: string; threshold?: number }
export interface GuidedAnswers {
  businessOwner: string; suitabilityConfirmed: boolean
  name: string; key: string; purpose: string
  fields: Array<{ key: string; title: string; type: 'string' | 'number' | 'boolean'; required: boolean }>
  steps: GuidedStep[]
  calendar: { zone: string; revision: string; validFrom: string; weekdays: number[]; start: string; end: string; holidays: string[] }
  closure: 'REQUIRE_NO_OPEN_TASKS' | 'CANCEL_OPEN_TASKS'
  subject: string; body: string; gitCommit?: string
}
export function guidedDefinition(a: GuidedAnswers): Definition {
  if (!a.businessOwner.trim() || !a.suitabilityConfirmed) throw new Error('Record the business owner and confirm that requirements and Tier-1 suitability were reviewed.')
  if (!a.name.trim() || !a.purpose.trim() || !/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(a.key)) throw new Error('Supply a name, purpose and valid workflow key.')
  if (!a.steps.length || a.steps.length > 7 || new Set(a.steps.map(s => s.group)).size > 5) throw new Error('Choose one to seven approval steps and at most five groups.')
  const properties: Record<string, FieldSchemaDoc> = {}
  for (const f of a.fields) {
    if (!/^[a-z][a-zA-Z0-9_]{0,63}$/.test(f.key) || !f.title.trim() || properties[f.key]) throw new Error('Each question needs a unique field key and a title.')
    properties[f.key] = { type: f.type, title: f.title, ...(f.type === 'string' ? { maxLength: 500 } : {}) }
  }
  const mail = (recipients: StageEmail['recipients']): StageEmail => ({ subject: a.subject, body: a.body, recipients })
  const d: Definition = {
    tier1Assessment: { businessOwner: a.businessOwner.trim(), requirementsReviewed: true, regulatoryClassification: 'standard', expectedDurationDays: 30, peakConcurrentUsers: 50, storedCases: 10000 },
    schemaVersion: '0.1', acceptanceProfile: 'tier1', closurePolicy: a.closure,
    workflowKey: a.key, name: a.name.trim(), metadata: { description: a.purpose.trim() },
    inputSchema: { type: 'object', additionalProperties: false, properties, required: a.fields.filter(f => f.required).map(f => f.key) },
    outputSchema: { type: 'object', additionalProperties: false, properties: {} },
    triggers: ['manual', 'api'], nodes: [{ id: 'start', type: 'start', label: 'Request submitted', config: {} }],
    transitions: [], decisions: [], execution: { maxConcurrentRuns: 100, maxAttempts: 3, retryDelaySeconds: 5 },
    security: { selfApproval: 'deny' }, dependencies: { connections: [] }, outputMapping: {}, terminalNotification: mail(['requester']),
    ...(a.gitCommit?.trim() ? { provenance: { gitCommit: a.gitCommit.trim() } } : {}),
  }
  if (a.gitCommit && !/^([a-f0-9]{40}|[a-f0-9]{64})$/.test(a.gitCommit)) throw new Error('The source commit must be a full Git hash.')
  const entry = (i: number) => i >= a.steps.length ? 'approved' : a.steps[i]?.conditionField ? `gate${i + 1}` : `approval${i + 1}`
  const edge = (from: string, to: string, on: 'next' | 'approved' | 'rejected' | 'condition' | 'default') => {
    const t = { id: `flow${d.transitions.length + 1}`, from, to, on }
    d.transitions.push(t); return t
  }
  edge('start', entry(0), 'next')
  a.steps.forEach((s, i) => {
    if (!s.label.trim() || !s.group || !Number.isInteger(s.minutes) || s.minutes < 1 || s.minutes > 43200) throw new Error('Every task needs a label, approver group and 1–43200 business minutes.')
    const id = `approval${i + 1}`
    if (s.conditionField) {
      if (properties[s.conditionField]?.type !== 'number' || !a.fields.find(f => f.key === s.conditionField)?.required || !Number.isFinite(s.threshold)) throw new Error('A conditional step needs a required numeric field and a threshold.')
      d.nodes.push({ id: `gate${i + 1}`, type: 'branch', label: `Check ${s.label}`, config: {} })
      const t = edge(`gate${i + 1}`, id, 'condition')
      Object.assign(t, { condition: { fieldRef: `/input/${s.conditionField}`, operator: 'gte', value: s.threshold } })
      edge(`gate${i + 1}`, entry(i + 1), 'default')
    }
    d.nodes.push({ id, type: 'approval', label: s.label, config: {
      candidateGroupRef: s.group, rejectReasonRequired: true, dueAfterMinutes: s.minutes, calendar: structuredClone(a.calendar),
      notifications: { assignment: mail(['candidateGroup']), completion: mail(['requester']), rejection: mail(['requester']), overdue: mail(['operators']) },
    } })
    edge(id, entry(i + 1), 'approved'); edge(id, 'rejected', 'rejected')
  })
  d.nodes.push({ id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } },
    { id: 'rejected', type: 'end', label: 'Rejected', config: { businessOutcome: 'Rejected', outcomeKind: 'rejected' } })
  if (d.nodes.length > 10) throw new Error('This design exceeds 10 states. Reduce steps or conditional gates.')
  return d
}
