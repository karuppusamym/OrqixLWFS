import { delay, http, HttpResponse, type HttpHandler, type JsonBodyType } from 'msw'
import type { Permission } from '@/api/types'
import { normalizeDefinition, type Definition } from '@/features/studio/model/types'
import { getCurrentUser } from '@/mocks/handlers'
import { GROUPS, WORKSPACES, clock, connectionDefinition, db, isMember, newId, permissionsOf, purchaseTemplate, userById, type MockWorkflow } from '@/mocks/state'
import { validateDefinition, type ValidationContext } from '@/mocks/validate'
import { DESCRIPTION_MAX, DESCRIPTION_MIN } from './limits'
import { FormPattern } from '@/lib/form/pattern'

/**
 * Mock-mode AI (S10): status (enabled), deterministic "generation" and "explanation" so the feature can be shown and
 * tested without a provider. Generation takes a few seconds in the browser, like the real thing.
 */
export const aiMock = { enabled: true, spentUsd: 0.0184, generationDelayMs: 2500, trippedAuto: false }

const B = '*/api/v1'

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  )
}

function guard(wsId: unknown, ...anyOf: Permission[]): { ok: true; perms: Permission[]; userId: string } | { ok: false; res: Response } {
  const id = typeof wsId === 'string' ? wsId : ''
  const user = getCurrentUser()
  if (!WORKSPACES.some((w) => w.id === id) || !isMember(user, id)) return { ok: false, res: problem(404, 'NOT_FOUND', 'Not found') }
  const perms = permissionsOf(user, id)
  if (anyOf.length > 0 && !anyOf.some((p) => perms.includes(p))) return { ok: false, res: problem(403, 'FORBIDDEN', 'Forbidden') }
  return { ok: true, perms, userId: user.id }
}

const PROFILES = [
  { id: 'economy', label: 'Economy', model: 'deepseek/deepseek-v3.2', variablePrice: false },
  { id: 'auto', label: 'Auto (Jev Router)', model: 'typesafe/jev-router', variablePrice: true },
]

function status() {
  return {
    enabled: aiMock.enabled,
    reason: aiMock.enabled ? null : 'AI is not configured: no provider key is set.',
    defaultProfile: aiMock.enabled ? 'economy' : null,
    profiles: aiMock.enabled ? PROFILES.filter((p) => !(aiMock.trippedAuto && p.id === 'auto')) : [],
    excludedModels: ['anthropic/claude-opus-*', 'anthropic/claude-fable-*', 'anthropic/claude-mythos-*', 'openai/*-pro*', '*:batch'],
    maxPrice: { promptPerMillion: 1.0, completionPerMillion: 3.0 },
    budget: { monthlyUsd: 10.0, spentUsd: aiMock.spentUsd },
    disabledProfiles: aiMock.trippedAuto
      ? [{ id: 'auto', label: 'Auto (Jev Router)', model: 'anthropic/claude-opus-9', reason: 'The router chose the excluded model anthropic/claude-opus-9. The profile is paused until 00:00 UTC.', until: new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate() + 1)).toISOString() }]
      : [],
  }
}

function context(wsId: string): ValidationContext {
  const ops = connectionDefinition.operations as Record<string, { requestSchema?: { required?: string[]; properties?: Record<string, unknown> }; responseSchema?: { properties?: Record<string, unknown> }; maxTimeoutSeconds: number }>
  const empty = wsId === WORKSPACES[2]?.id
  return {
    groups: empty ? [] : GROUPS.map((g) => g.key),
    isTestWorkspace: WORKSPACES.find((w) => w.id === wsId)?.isTest ?? false,
    connections: empty
      ? []
      : [
          {
            key: 'procurement',
            versions: [1],
            bound: !!db.bindings.get(`${wsId}:procurement`)?.enabled,
            operations: Object.fromEntries(
              Object.entries(ops).map(([name, op]) => [
                name,
                { requestRequired: op.requestSchema?.required ?? [], requestProps: Object.keys(op.requestSchema?.properties ?? {}), responseProps: Object.keys(op.responseSchema?.properties ?? {}), maxTimeoutSeconds: op.maxTimeoutSeconds },
              ]),
            ),
          },
        ],
  }
}

function toKey(name: string): string {
  const key = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return /^[a-z]/.test(key) ? key.slice(0, 56).replace(/-+$/, '') : `ai-${key}`.slice(0, 56)
}

/** A two-level approval workflow; the second approver group may not exist (shown as missing configuration). */
function approvalDraft(name: string, firstGroup: string, secondGroup: string | null): Definition {
  const nodes = [
    { id: 'start', type: 'start', label: 'Request submitted', config: {} },
    { id: 'firstReview', type: 'approval', label: 'Review request', config: { candidateGroupRef: firstGroup, rejectReasonRequired: true, dueAfterHours: 24 } },
    ...(secondGroup ? [{ id: 'secondReview', type: 'approval', label: 'Second review', config: { candidateGroupRef: secondGroup, rejectReasonRequired: true, dueAfterHours: 48 } }] : []),
    { id: 'notifyApproved', type: 'notification', label: 'Tell requester it was approved', config: { channel: 'inApp', recipient: 'initiator', message: 'Your request was approved.' } },
    { id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } },
    { id: 'notifyRejected', type: 'notification', label: 'Tell requester it was rejected', config: { channel: 'inApp', recipient: 'initiator', message: 'Your request was rejected. Open it to see the reason.' } },
    { id: 'rejected', type: 'end', label: 'Rejected', config: { businessOutcome: 'Rejected', outcomeKind: 'rejected' } },
  ]
  const after = secondGroup ? 'secondReview' : 'notifyApproved'
  const transitions = [
    { id: 't1', from: 'start', to: 'firstReview', on: 'next' },
    { id: 't2', from: 'firstReview', to: after, on: 'approved' },
    { id: 't3', from: 'firstReview', to: 'notifyRejected', on: 'rejected' },
    ...(secondGroup
      ? [
          { id: 't4', from: 'secondReview', to: 'notifyApproved', on: 'approved' },
          { id: 't5', from: 'secondReview', to: 'notifyRejected', on: 'rejected' },
        ]
      : []),
    { id: 't6', from: 'notifyApproved', to: 'approved', on: 'next' },
    { id: 't7', from: 'notifyRejected', to: 'rejected', on: 'next' },
  ]
  const positions: Record<string, { x: number; y: number }> = {}
  const row = ['start', 'firstReview', ...(secondGroup ? ['secondReview'] : []), 'notifyApproved', 'approved']
  row.forEach((id, i) => (positions[id] = { x: 80 + i * 220, y: 200 }))
  positions.notifyRejected = { x: 520, y: 380 }
  positions.rejected = { x: 740, y: 380 }
  return normalizeDefinition({
    schemaVersion: '0.1',
    workflowKey: toKey(name),
    name,
    metadata: { description: `${name}: the request is reviewed${secondGroup ? ' in two steps' : ''} and the requester is told the outcome.` },
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      required: ['title', 'details'],
      properties: {
        title: { type: 'string', title: 'What do you need?', minLength: 1, maxLength: 120 },
        details: { type: 'string', title: 'Details', description: 'Why it is needed and anything the reviewer should know.', minLength: 1, maxLength: 2000 },
      },
    },
    inputUi: { order: ['title', 'details'], widgets: { details: 'textarea' }, currency: 'USD' },
    outputSchema: { type: 'object', additionalProperties: false, properties: {} },
    triggers: ['manual'],
    nodes,
    transitions,
    decisions: [],
    execution: { maxConcurrentRuns: 100, maxAttempts: 3, retryDelaySeconds: 30 },
    security: { selfApproval: 'deny' },
    dependencies: { connections: [] },
    outputMapping: {},
    uiLayout: { positions, viewport: { x: 0, y: 0, zoom: 0.8 } },
  })
}

function generate(description: string, requestedName: string | undefined): { definition: Definition; assumptions: string[] } | null {
  const text = description.toLowerCase()
  if (/\b(poem|joke|recipe|song|story)\b/.test(text)) return null
  if (/\b(purchase|buy|order|equipment)\b/.test(text)) {
    const definition = purchaseTemplate()
    definition.name = requestedName ?? 'Purchase approval'
    return {
      definition,
      assumptions: [
        'Finance reviews purchases of USD 5,000 or more; smaller purchases only need the manager.',
        'The purchase order is created in the procurement system after all approvals.',
        'Rejected requests end with an in-app message to the requester.',
      ],
    }
  }
  const second = /\bsecurity\b/.test(text) ? 'security-team' : /\blegal\b/.test(text) ? 'legal-reviewers' : /\bfinance\b/.test(text) ? 'finance-approvers' : null
  const name = requestedName ?? (/\baccess\b/.test(text) ? 'Access request' : 'Approval request')
  return {
    definition: approvalDraft(name, 'managers', second),
    assumptions: [
      'The first review is done by the managers group.',
      ...(second ? [`A second review by the ${second} group follows the first approval.`] : []),
      'Parallel reviews are not supported yet, so reviews happen one after the other.',
    ],
  }
}

function workflowView(wf: MockWorkflow, perms: Permission[]) {
  const owner = userById(wf.ownerId)
  return {
    id: wf.id,
    key: wf.key,
    name: wf.name,
    description: wf.description,
    owner: owner ? { id: owner.id, displayName: owner.displayName } : { id: newId(), displayName: 'Unknown' },
    draftRevision: 1,
    activeRelease: null,
    canStart: false,
    updatedAt: wf.updatedAt,
    version: wf.version,
    createdAt: wf.createdAt,
    permissions: perms,
  }
}

function explanation(def: Definition) {
  const approvals = def.nodes.filter((n) => n.type === 'approval')
  const groups = approvals.map((n) => String(n.config.candidateGroupRef ?? 'a group'))
  const steps = def.nodes.map((n) => {
    switch (n.type) {
      case 'start':
        return { nodeId: n.id, text: 'The requester fills in the request form and submits it.' }
      case 'approval':
        return { nodeId: n.id, text: `Someone in the ${String(n.config.candidateGroupRef ?? '')} group approves or rejects the request. A rejection needs a reason.` }
      case 'decision':
        return { nodeId: n.id, text: 'A rule table checks the request (for example the amount) to decide which path comes next.' }
      case 'branch':
        return { nodeId: n.id, text: 'The request takes one of the paths depending on the result of the rules.' }
      case 'http':
        return { nodeId: n.id, text: `Orqix calls the ${String(n.config.connectionRef ?? 'connected')} system to complete the work.` }
      case 'notification':
        return { nodeId: n.id, text: `The requester gets an in-app message: "${String(n.config.message ?? '')}"` }
      default:
        return { nodeId: n.id, text: `The request finishes with the outcome ${String(n.config.businessOutcome ?? n.label)}.` }
    }
  })
  return {
    summary: `${def.name} lets people submit a request that is reviewed by ${groups.length === 0 ? 'nobody' : groups.join(', then ')}. Each reviewer can approve or reject; a rejection ends the request and the requester is told why. When everything is approved the workflow finishes with a success outcome.`,
    steps,
    generation: { profile: 'economy', model: 'deepseek/deepseek-v3.2', promptVersion: 'explain-v1', attempts: 1, escalated: false, inputTokens: 2100, outputTokens: 380, costUsd: 0.0007, durationMs: 1800 },
  }
}

interface MockJob {
  id: string
  wsId: string
  userId: string
  input: { description?: string; name?: string; profile?: string }
  profile: string
  hash: string
  startedAt: number
  finishedAt: number | null
  outcome: { result: Record<string, unknown> } | { problem: Record<string, unknown> } | null
}

const jobs = new Map<string, MockJob>()
const jobsByKey = new Map<string, MockJob>()

/** Forget every job (tests and persona switches). */
export function resetAiJobs(): void {
  jobs.clear()
  jobsByKey.clear()
}

function problemBody(status: number, code: string, title: string, detail: string, extra: Record<string, unknown> = {}) {
  return { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, detail, correlationId: newId(), ...extra }
}

/** Creates the workflow when the generation "finishes", as the server does when the model answered. */
function finish(job: MockJob, perms: Permission[]): void {
  const description = (job.input.description ?? '').trim()
  if (/\(unavailable\)/i.test(description)) {
    job.outcome = { problem: problemBody(503, 'AI_PROVIDER_UNAVAILABLE', 'The AI provider is unavailable; nothing was created', 'The AI provider is unavailable. Nothing was created; try again shortly.') }
    return
  }
  const generated = generate(description, job.input.name?.trim() || undefined)
  if (!generated) {
    job.outcome = { problem: problemBody(422, 'AI_OUTPUT_INVALID', 'The AI could not produce a valid workflow', 'The description does not describe a business workflow: it asks for something that is not a process.', { issues: [] }) }
    return
  }
  let wfKey = toKey(generated.definition.name)
  for (let n = 2; [...db.workflows.values()].some((w) => w.wsId === job.wsId && w.key === wfKey); n++) wfKey = `${toKey(generated.definition.name)}-${n}`
  generated.definition.workflowKey = wfKey
  const now = clock.iso()
  const wf: MockWorkflow = {
    id: newId(),
    wsId: job.wsId,
    key: wfKey,
    name: generated.definition.name,
    description: generated.definition.metadata.description || null,
    ownerId: job.userId,
    drafts: [{ revision: 1, savedAt: now, savedById: job.userId, definition: generated.definition }],
    releases: [],
    activeReleaseId: null,
    version: 1,
    createdAt: now,
    updatedAt: now,
  }
  db.workflows.set(wf.id, wf)
  const issues = validateDefinition(generated.definition, context(job.wsId))
  const binding = new Set(['WF_GROUP_UNBOUND', 'WF_CONNECTION_UNDECLARED', 'WF_CONNECTION_UNKNOWN', 'WF_CONNECTION_UNBOUND', 'WF_OPERATION_UNKNOWN'])
  const missingConfiguration = issues
    .filter((i) => i.severity === 'blocker' && binding.has(i.code))
    .map((i) => {
      const node = generated.definition.nodes.find((n) => n.id === i.nodeId)
      const group = node?.config.candidateGroupRef
      const message = i.code === 'WF_GROUP_UNBOUND' && typeof group === 'string' ? `Create the group "${group}" in Settings > Access, or choose an existing group for this approval step.` : i.message
      return { message, nodeId: i.nodeId ?? null, code: i.code }
    })
  const cost = job.profile === 'auto' ? 0.0143 : 0.0031
  aiMock.spentUsd = Math.round((aiMock.spentUsd + cost) * 10_000) / 10_000
  job.outcome = {
    result: {
      workflow: workflowView(wf, perms),
      draft: { workflowId: wf.id, revision: 1, savedAt: now, savedBy: { id: job.userId, displayName: userById(job.userId)?.displayName ?? 'Unknown' }, definition: generated.definition },
      report: { revision: 1, compilerVersion: '0.1.0-mock', publishable: !issues.some((i) => i.severity === 'blocker'), semanticHash: null, issues },
      assumptions: generated.assumptions,
      missingConfiguration,
      generation: {
        profile: job.profile,
        model: job.profile === 'auto' ? 'openai/gpt-6-luna' : 'deepseek/deepseek-v3.2',
        promptVersion: 'draft-v1',
        attempts: 1,
        escalated: false,
        inputTokens: 9800,
        outputTokens: 2400,
        costUsd: cost,
        durationMs: aiMock.generationDelayMs,
      },
    },
  }
}

/** The job as the API shows it: queued, then running, then (after the mock delay) succeeded or failed. */
function jobView(job: MockJob, perms: Permission[]) {
  const age = Date.now() - job.startedAt
  if (!job.outcome && age >= aiMock.generationDelayMs) {
    finish(job, perms)
    job.finishedAt = Date.now()
  }
  const base = { id: job.id, startedAt: new Date(job.startedAt).toISOString(), profile: job.profile }
  if (!job.outcome) return { ...base, status: age < Math.min(400, aiMock.generationDelayMs / 4) ? 'queued' : 'running' }
  const finishedAt = new Date(job.finishedAt ?? Date.now()).toISOString()
  return 'result' in job.outcome ? { ...base, status: 'succeeded', finishedAt, result: job.outcome.result } : { ...base, status: 'failed', finishedAt, problem: job.outcome.problem }
}

export function createAiHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/workspaces/:ws/ai/status`, async ({ params }) => {
      if (db.latencyMs > 0) await delay(db.latencyMs)
      const g = guard(params.ws, 'workflow.read', 'workflow.edit', 'workspace.manage-access')
      return g.ok ? HttpResponse.json(status()) : g.res
    }),
    http.post(`${B}/workspaces/:ws/ai/drafts`, async ({ params, request }) => {
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const key = request.headers.get('Idempotency-Key')
      if (!key || !/^[A-Za-z0-9._:-]{8,128}$/.test(key)) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', 'An Idempotency-Key header is required')
      const input = (await request.json().catch(() => ({}))) as { description?: string; name?: string; profile?: string }
      const description = (input.description ?? '').trim()
      if (description.length < DESCRIPTION_MIN || description.length > DESCRIPTION_MAX) {
        return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { fieldErrors: [{ pointer: '/description', code: 'minLength', message: 'Invalid value' }] })
      }
      if (!aiMock.enabled) return problem(422, 'AI_NOT_CONFIGURED', 'AI assistance is not configured', { detail: 'AI is not configured: no provider key is set.' })
      const stored = `${g.userId}|generateAiDraft|${key}`
      const hash = JSON.stringify([params.ws, input])
      const prior = jobsByKey.get(stored)
      if (prior) {
        if (prior.hash !== hash) return problem(409, 'IDEMPOTENCY_KEY_REUSED', 'The Idempotency-Key was used for a different request')
        return HttpResponse.json(jobView(prior, g.perms) as JsonBodyType, { status: 200, headers: { 'Idempotent-Replayed': 'true' } })
      }
      const profile = input.profile ?? 'economy'
      if (!PROFILES.some((p) => p.id === profile)) return problem(422, 'AI_MODEL_NOT_ALLOWED', 'The requested AI model or profile is not allowed', { detail: 'Unknown AI profile.' })
      const job: MockJob = { id: newId(), wsId: String(params.ws), userId: g.userId, input, profile, hash, startedAt: Date.now(), finishedAt: null, outcome: null }
      jobsByKey.set(stored, job)
      jobs.set(job.id, job)
      return HttpResponse.json(jobView(job, g.perms) as JsonBodyType, { status: 202, headers: { Location: `/api/v1/workspaces/${String(params.ws)}/ai/generations/${job.id}` } })
    }),
    http.get(`${B}/workspaces/:ws/ai/generations/:id`, async ({ params }) => {
      if (db.latencyMs > 0) await delay(Math.min(db.latencyMs, 150))
      const g = guard(params.ws, 'workflow.read', 'workflow.edit')
      if (!g.ok) return g.res
      const job = jobs.get(String(params.id))
      if (!job || job.wsId !== params.ws || (job.userId !== g.userId && !g.perms.includes('workflow.edit'))) return problem(404, 'NOT_FOUND', 'Not found')
      return HttpResponse.json(jobView(job, g.perms) as JsonBodyType)
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/explain`, async ({ params, request }) => {
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = db.workflows.get(String(params.id))
      if (!wf || wf.wsId !== params.ws) return problem(404, 'NOT_FOUND', 'Not found')
      const input = (await request.json().catch(() => ({}))) as { revision?: number }
      if (!aiMock.enabled) return problem(422, 'AI_NOT_CONFIGURED', 'AI assistance is not configured')
      let def: Definition | undefined
      if (input.revision !== undefined) def = wf.drafts.find((d) => d.revision === input.revision)?.definition
      else def = wf.releases.find((r) => r.id === wf.activeReleaseId)?.definition
      if (!def) return input.revision !== undefined ? problem(404, 'NOT_FOUND', 'Not found') : problem(422, 'NO_ACTIVE_RELEASE', 'The workflow has no active release')
      if (db.latencyMs > 0) await delay(Math.max(db.latencyMs, 900))
      return HttpResponse.json(explanation(def) as JsonBodyType)
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/ai/form-suggestions`, async ({ params, request }) => {
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const wf = db.workflows.get(String(params.id))
      if (!wf || wf.wsId !== params.ws) return problem(404, 'NOT_FOUND', 'Not found')
      const input = (await request.json().catch(() => ({}))) as { description?: string; inputSchema?: { properties?: Record<string, unknown>; required?: string[] } }
      const description = (input.description ?? '').trim()
      if (description.length < 10 || description.length > 2000 || typeof input.inputSchema?.properties !== 'object') {
        return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { fieldErrors: [{ pointer: description.length < 10 ? '/description' : '/inputSchema', code: 'minLength', message: 'Invalid value' }] })
      }
      if (!aiMock.enabled) return problem(422, 'AI_NOT_CONFIGURED', 'AI assistance is not configured')
      if (db.latencyMs > 0) await delay(Math.max(db.latencyMs, 600))
      return HttpResponse.json(formSuggestion(description, input.inputSchema.properties ?? {}, input.inputSchema.required ?? []) as JsonBodyType)
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/ai/draft-edits`, async ({ params, request }) => {
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const wf = db.workflows.get(String(params.id))
      if (!wf || wf.wsId !== params.ws) return problem(404, 'NOT_FOUND', 'Not found')
      const input = (await request.json().catch(() => ({}))) as { instruction?: string; revision?: number; mode?: string; history?: unknown[] }
      const instruction = (input.instruction ?? '').trim()
      if (instruction.length < 2 || instruction.length > 2000 || !Number.isInteger(input.revision) || (input.history?.length ?? 0) > 8 || (input.mode !== undefined && input.mode !== 'edit' && input.mode !== 'regenerate')) {
        return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { fieldErrors: [{ pointer: '/instruction', code: 'minLength', message: 'Invalid value' }] })
      }
      if (!aiMock.enabled) return problem(422, 'AI_NOT_CONFIGURED', 'AI assistance is not configured', { detail: 'AI is not configured: no provider key is set.' })
      const latest = wf.drafts.reduce((a, b) => (b.revision > a.revision ? b : a))
      if (latest.revision !== input.revision) {
        return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The workflow changed since you loaded it', { detail: `The draft was saved again (revision ${latest.revision}). Reload the studio, then ask again.`, currentRevision: latest.revision })
      }
      if (db.latencyMs > 0) await delay(Math.max(db.latencyMs, 800))
      return HttpResponse.json(draftEditProposal(instruction, latest.definition, latest.revision, input.mode === 'regenerate' ? 'regenerate' : 'edit', String(params.ws)) as JsonBodyType)
    }),
  ]
}

function freeTransitionId(def: Definition): string {
  const used = new Set([...def.nodes.map((n) => n.id), ...def.transitions.map((t) => t.id)])
  for (let i = def.transitions.length + 1; ; i++) if (!used.has(`t${i}`)) return `t${i}`
}

/**
 * Deterministic mock of proposeDraftEdits (STU02, docs/build/S95): keyword-driven, applied to a candidate copy with the
 * same operation shapes the server returns; changes the mock compiler would refuse are listed as rejected.
 */
function draftEditProposal(instruction: string, current: Definition, revision: number, mode: 'edit' | 'regenerate', wsId: string) {
  const text = instruction.toLowerCase()
  const generation = { profile: 'economy', model: 'deepseek/deepseek-v3.2', promptVersion: 'draft-edit-v1', attempts: 1, escalated: false, inputTokens: 3200, outputTokens: 520, costUsd: 0.0009, durationMs: 1500 }
  const group = context(wsId).groups[0] ?? 'managers'
  if (/\b(poem|joke|recipe|song|weather)\b/.test(text)) {
    return { revision, mode, changes: [], rejected: [], notes: [], notAnEdit: 'This is not about the workflow, so nothing is proposed.', generation }
  }
  if (mode === 'regenerate') {
    const def = structuredClone(current)
    const start = def.nodes.find((n) => n.type === 'start') ?? { id: 'start', type: 'start' as const, label: 'Request submitted', config: {} }
    def.nodes = [start, { id: 'review', type: 'approval', label: 'Review request', config: { candidateGroupRef: group, rejectReasonRequired: true } }, { id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } }, { id: 'declined', type: 'end', label: 'Declined', config: { businessOutcome: 'Declined', outcomeKind: 'rejected' } }]
    def.transitions = [{ id: 't1', from: start.id, to: 'review', on: 'next' }, { id: 't2', from: 'review', to: 'approved', on: 'approved' }, { id: 't3', from: 'review', to: 'declined', on: 'rejected' }]
    def.decisions = []
    def.outputMapping = {}
    def.outputSchema = { type: 'object', additionalProperties: false, properties: {} }
    def.dependencies = { connections: [] }
    delete def.stages
    delete def.subprocesses
    def.uiLayout = { positions: { [start.id]: { x: 80, y: 200 }, review: { x: 300, y: 200 }, approved: { x: 520, y: 140 }, declined: { x: 520, y: 340 } } }
    const operations = [
      ...def.nodes.slice(1).map((n) => ({ op: 'addStep', id: n.id, kind: n.type, label: n.label, config: n.config })),
      ...def.transitions.map((t) => ({ op: 'connect', transitionId: t.id, from: t.from, to: t.to, on: t.on })),
    ]
    return { revision, mode, message: 'A new draft with one review, built from your request form.', changes: [{ id: 'c1', summary: 'One review with an approved and a declined outcome', operations, dependsOn: [] }], rejected: [], notes: ['Mock proposal: review it before replacing the draft.'], definition: def, generation }
  }
  const changes: Array<Record<string, unknown>> = []
  const rejected: Array<{ summary: string; reasons: string[] }> = []
  const candidate = structuredClone(current)
  const approval = candidate.nodes.find((n) => n.type === 'approval')
  if (/\b(review|approval|finance|legal)\b/.test(text) && approval) {
    const onApproved = candidate.transitions.find((t) => t.from === approval.id && t.on === 'approved')
    const onRejected = candidate.transitions.find((t) => t.from === approval.id && t.on === 'rejected')
    if (onApproved && onRejected) {
      const id = candidate.nodes.some((n) => n.id === 'secondReview') ? `secondReview${candidate.nodes.length}` : 'secondReview'
      const operations: Array<Record<string, unknown>> = [{ op: 'disconnect', transitionId: onApproved.id, before: structuredClone(onApproved) }]
      candidate.transitions = candidate.transitions.filter((t) => t.id !== onApproved.id)
      const config = { candidateGroupRef: group, rejectReasonRequired: true }
      operations.push({ op: 'addStep', id, kind: 'approval', label: 'Second review', config })
      candidate.nodes.push({ id, type: 'approval', label: 'Second review', config })
      for (const [from, to, on] of [[approval.id, id, 'approved'], [id, onApproved.to, 'approved'], [id, onRejected.to, 'rejected']] as const) {
        const transitionId = freeTransitionId(candidate)
        candidate.transitions.push({ id: transitionId, from, to, on })
        operations.push({ op: 'connect', transitionId, from, to, on })
      }
      changes.push({ id: `c${changes.length + 1}`, summary: `A second review after “${approval.label}”`, operations, dependsOn: [] })
    }
  }
  if (/\brename\b/.test(text) && approval) {
    const before = structuredClone(candidate.nodes.find((n) => n.id === approval.id))
    changes.push({ id: `c${changes.length + 1}`, summary: 'Clearer name for the first review', operations: [{ op: 'renameStep', id: approval.id, label: 'Manager review', before }], dependsOn: [] })
  }
  if (/\b(question|cost code|form)\b/.test(text)) {
    if ('costCode' in candidate.inputSchema.properties) rejected.push({ summary: 'Ask for a cost code', reasons: ['A question with the key "costCode" already exists.'] })
    else changes.push({ id: `c${changes.length + 1}`, summary: 'Ask for a cost code', operations: [{ op: 'changeFormField', action: 'add', key: 'costCode', field: { type: 'string', title: 'Cost code', maxLength: 20 }, required: true }], dependsOn: [] })
  }
  if (changes.length === 0 && rejected.length === 0) {
    rejected.push({ summary: 'Add a step with no route', reasons: ['“New step” has no outgoing route. Connect it to the next step.'] })
  }
  return { revision, mode, message: changes.length > 0 ? 'Here is what I propose. Tick the changes you want.' : 'I could not find a change that passes the checks.', changes, rejected, notes: ['Mock proposal: review every change before applying it.'], generation }
}

/** Deterministic mock of suggestFormChanges (S90): keyword-driven, validated with the same pattern engine. */
function formSuggestion(description: string, properties: Record<string, unknown>, required: string[]) {
  const text = description.toLowerCase()
  const changes: Array<Record<string, unknown>> = []
  const rejected: Array<{ op: string; key: string; reasons: string[] }> = []
  const add = (key: string, field: Record<string, unknown>, isRequired: boolean, reason: string) => {
    if (key in properties) rejected.push({ op: 'add', key, reasons: [`A question with the key "${key}" already exists.`] })
    else if (typeof field.pattern === 'string' && FormPattern.problem(field.pattern)) rejected.push({ op: 'add', key, reasons: [`The pattern of "${String(field.title)}" cannot be used: ${FormPattern.problem(field.pattern)}`] })
    else changes.push({ op: 'add', key, field, required: isRequired, reason })
  }
  if (text.includes('cost code')) add('costCode', { type: 'string', title: 'Cost code', maxLength: 7, pattern: '^[A-Z]{2}-\\d{4}$', patternMessage: 'Enter a code like AB-1234.' }, true, 'Finance books purchases by cost code.')
  if (text.includes('phone')) add('phone', { type: 'string', title: 'Phone number', maxLength: 20, pattern: '^\\+?[0-9 ]{6,19}$', patternMessage: 'Enter digits and spaces, optionally starting with +.' }, false, 'A phone number lets approvers call back.')
  if (text.includes('date') || text.includes('deadline')) add('neededBy', { type: 'string', title: 'Needed by', format: 'date' }, false, 'Approvers see when the purchase is needed.')
  if (text.includes('backreference')) add('broken', { type: 'string', title: 'Broken', pattern: '^(a)\\1$' }, false, 'Shows a refused pattern.')
  const removable = Object.keys(properties).find(key => text.includes(`remove ${key.toLowerCase()}`))
  if (removable) changes.push({ op: 'remove', key: removable, required: false, reason: 'You asked to remove it.', before: properties[removable], wasRequired: required.includes(removable) })
  if (changes.length === 0 && rejected.length === 0) add('additionalNotes', { type: 'string', title: 'Additional notes', maxLength: 2000 }, false, 'A free-text question for anything else.')
  return {
    changes, rejected,
    notes: ['Mock suggestion: review every change before applying it.'],
    notAForm: null,
    generation: { profile: 'economy', model: 'deepseek/deepseek-v3.2', promptVersion: 'form-v1', attempts: 1, escalated: false, inputTokens: 900, outputTokens: 210, costUsd: 0.0003, durationMs: 1200 },
  }
}
