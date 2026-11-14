import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import type { Permission } from '@/api/types'
import { checkSkillDocument } from '@/features/aiskills/skillDocument'
import { getCurrentUser } from './handlers'
import { db, permissionsOf, userByName } from './state'

type S = components['schemas']

/**
 * UI double of the AiSkills API (docs/build/S134; Studio pages docs/build/S146 part D), mock mode and unit tests only.
 * In-memory and reset whenever the mock database is reseeded. It keeps the lifecycle rules the pages depend on (one
 * version in progress, only drafts are submitted, only submitted versions approved, four eyes on approval and
 * promotion decisions, optimistic policy version) and answers evaluation runs with fixed metrics after a short delay.
 * The real rules, the contract and the metrics are the backend's (AiTaskFlowTest); no model is ever called here.
 * Mock runs do not execute AI tasks, so run detail has no AI decisions.
 */
const B = '/api/v1/workspaces/:ws'
const NOW = '2026-11-05T09:00:00Z'
const DANA = userByName('dana.designer').id
const PAUL = userByName('paul.publisher').id

const TRIAGE_DOC = {
  schemaVersion: '1.0', kind: 'skill', key: 'dispute-triage', name: 'Dispute triage', mode: 'classify',
  instructions: 'Classify the dispute into exactly one label.', modelProfile: 'economy',
  input: { description: { type: 'string', required: true, classification: 'customer-text' }, amount: { type: 'number' } },
  labels: [{ key: 'pricing', description: 'Charged a different amount', risk: 'low' },
    { key: 'duplicate', description: 'Charged twice', risk: 'low' },
    { key: 'fraudSuspected', description: 'Did not authorize the purchase', risk: 'high', adverse: true }],
  evaluation: { suite: 'dispute-triage-suite', minAccuracy: 0.9, minRecall: { fraudSuspected: 0.95 }, minCases: 50 },
  promotion: { minShadowCases: 200, targetAgreement: 0.9 },
}

function version(skillKey: string, n: number, document: Record<string, unknown>, extra: Partial<S['AiSkillVersion']> = {}): S['AiSkillVersion'] {
  return {
    skillKey, version: n, status: 'draft', mode: document.mode as S['AiSkillVersion']['mode'], modelProfile: String(document.modelProfile),
    contentHash: n.toString(16).padStart(64, '0'), authorId: DANA, submittedAt: null, approverId: null, approvedAt: null,
    autonomyCeiling: 'suggest', driftFallback: false, driftReason: null, evaluationGate: { passing: false, runId: null },
    createdAt: NOW, updatedAt: NOW, document, ...extra,
  }
}

export const MOCK_AI_SKILL: S['AiSkill'] = {
  key: 'dispute-triage', name: 'Dispute triage', ownerId: DANA, createdAt: NOW, approvedVersion: 1, mode: 'classify',
  versions: [version('dispute-triage', 1, TRIAGE_DOC, {
    status: 'approved', submittedAt: NOW, approverId: PAUL, approvedAt: NOW,
    evaluationGate: { passing: true, runId: '00000000-0000-7000-8000-00000000a003' },
  })],
}

const REFUND_DOC = {
  schemaVersion: '1.0', kind: 'skill', key: 'refund-reason', name: 'Refund reason', mode: 'recommend',
  instructions: 'Recommend whether to refund the request in full, in part, or not at all.', modelProfile: 'economy',
  input: { reason: { type: 'string', required: true, classification: 'customer-text' } },
  outcomes: [{ key: 'refundFull', description: 'Refund the whole amount' }, { key: 'refundPartial', description: 'Refund part of it' },
    { key: 'decline', description: 'Do not refund', adverse: true }],
}

const METRICS = {
  caseCount: 60, correct: 56, invalid: 1, accuracy: 0.9333,
  confusion: { pricing: { pricing: 22, duplicate: 1 }, duplicate: { duplicate: 18, pricing: 2 }, fraudSuspected: { fraudSuspected: 16, pricing: 1 } },
  perLabel: { pricing: { precision: 0.88, recall: 0.9565, support: 23 }, duplicate: { precision: 0.9474, recall: 0.9, support: 20 }, fraudSuspected: { precision: 1, recall: 0.9412, support: 17 } },
  costUsd: 0.0186, costPerCaseUsd: 0.00031, p95LatencyMs: 1840,
  thresholds: [0.5, 0.6, 0.7, 0.8, 0.9, 0.95].map((t) => ({ threshold: t, autoShare: Math.round((1.05 - t) * 1000) / 1000, autoAccuracy: Math.min(1, Math.round((0.85 + t / 8) * 1000) / 1000) })),
  failures: ['Recall of fraudSuspected is 0.9412, below 0.95.'],
  minCases: 50, minAccuracy: 0.9,
}
const CALIBRATION = Array.from({ length: 10 }, (_, b) => {
  const cases = b < 5 ? 0 : [3, 6, 12, 18, 21][b - 5]!
  const correct = Math.round(cases * (0.5 + b / 20))
  return { from: b / 10, to: (b + 1) / 10, cases, correct, accuracy: cases === 0 ? 0 : Math.round((correct / cases) * 10000) / 10000 }
})

function shadow(skillKey: string, v: S['AiSkillVersion']): S['AiShadowDashboard'] {
  return {
    skillKey, version: v.version, decisions: 312, shadowDecisions: 240, compared: 228, agreed: 209, agreement: 0.9167,
    statuses: { shadow: 240, decided: 52, lowConfidence: 20 },
    confusion: { pricing: { pricing: 90, duplicate: 4 }, duplicate: { duplicate: 70, pricing: 6 }, fraudSuspected: { fraudSuspected: 49, pricing: 9 } },
    perLabel: { pricing: { precision: 0.957, recall: 0.857, support: 105 }, duplicate: { precision: 0.921, recall: 0.946, support: 74 }, fraudSuspected: { precision: 0.845, recall: 1, support: 49 } },
    costPerCaseUsd: 0.00029, p95LatencyMs: 1720, minShadowCases: 200, targetAgreement: 0.9, autonomyCeiling: v.autonomyCeiling, driftFallback: v.driftFallback,
  }
}

interface Store {
  skills: S['AiSkill'][]
  suites: Array<S['AiEvalSuite'] & { document: Record<string, unknown> }>
  runs: Array<S['AiEvaluation'] & { finishAt: number }>
  promotions: S['AiSkillPromotion'][]
  queue: S['AiReviewQueue']['items']
  candidates: S['AiEvalCandidateList']['items']
  policy: S['AiPolicy']
}

function fresh(): Store {
  const triage = structuredClone(MOCK_AI_SKILL)
  const refund: S['AiSkill'] = {
    key: 'refund-reason', name: 'Refund reason', ownerId: DANA, createdAt: NOW, approvedVersion: null, mode: 'recommend',
    versions: [version('refund-reason', 1, REFUND_DOC, { status: 'submitted', submittedAt: NOW })],
  }
  const cases = Array.from({ length: 60 }, (_, i) => ({
    id: `case-${i + 1}`, input: { description: `Synthetic dispute ${i + 1}`, amount: 10 + i }, expected: { label: ['pricing', 'duplicate', 'fraudSuspected'][i % 3] }, source: 'synthetic',
  }))
  const suiteDoc = { schemaVersion: '1.0', kind: 'evaluationSuite', key: 'dispute-triage-suite', name: 'Dispute triage cases', skillKey: 'dispute-triage', cases }
  return {
    skills: [triage, refund],
    suites: [{ key: 'dispute-triage-suite', skillKey: 'dispute-triage', name: 'Dispute triage cases', caseCount: 60, revision: 3, contentHash: 'c'.repeat(64), updatedAt: NOW, document: suiteDoc }],
    runs: [{
      id: '00000000-0000-7000-8000-00000000a003', skillKey: 'dispute-triage', version: 1, suiteKey: 'dispute-triage-suite', suiteHash: 'c'.repeat(64),
      modelProfile: 'economy', status: 'failed', caseCount: 60, metrics: METRICS, calibration: CALIBRATION, failure: null, startedBy: DANA,
      startedAt: NOW, finishedAt: NOW, finishAt: 0,
    }],
    promotions: [{
      id: '00000000-0000-7000-8000-00000000b001', skillKey: 'dispute-triage', version: 1, fromAutonomy: 'suggest', toAutonomy: 'autoWithReview',
      status: 'pending', requestedBy: DANA, requestedAt: NOW, reason: 'Shadow agreement is above target for four weeks.', evidence: shadow('dispute-triage', triage.versions[0]!),
      decidedBy: null, decidedAt: null, decisionReason: null,
    }],
    queue: [{ decisionId: '00000000-0000-7000-8000-00000000c001', runId: '00000000-0000-7000-8000-00000000c101', nodeId: 'triage', skillKey: 'dispute-triage', skillVersion: 1, status: 'decided', aiValue: 'duplicate', confidence: 0.93, createdAt: NOW }],
    candidates: [{ id: '00000000-0000-7000-8000-00000000d001', skillKey: 'dispute-triage', decisionId: '00000000-0000-7000-8000-00000000c002', input: null, expected: { label: 'fraudSuspected' }, createdAt: NOW }],
    policy: {
      regulated: false, residency: [], inputRetention: 'digest', retentionDays: 365, lowRiskLabels: ['dispute-triage:duplicate'], allowAutoWithReview: true,
      classificationActions: { public: 'send', internal: 'send', 'customer-text': 'mask', personal: 'hash', sensitive: 'drop' }, version: 0, updatedAt: null,
      endpoints: [{ id: 'openrouter', kind: 'openaiCompatible', residency: [] }, { id: 'bedrock-eu', kind: 'bedrock', residency: ['eu'] }, { id: 'azure-us', kind: 'azureOpenai', residency: ['us'] }],
    },
  }
}

let owner: unknown = null
let store: Store = fresh()
function st(): Store {
  if (owner !== db) {
    owner = db
    store = fresh()
  }
  return store
}

const json = (body: unknown, status = 200) => HttpResponse.json(body as never, { status })
function problem(status: number, code: string, detail: string, fieldErrors?: Array<{ pointer: string; code: string; message: string }>) {
  return HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase()}`, title: detail, status, code, detail, correlationId: crypto.randomUUID(), ...(fieldErrors ? { fieldErrors } : {}) },
    { status, headers: { 'Content-Type': 'application/problem+json' } })
}
const may = (ws: string, ...wanted: Permission[]) => wanted.some((p) => permissionsOf(getCurrentUser(), ws).includes(p))
const forbidden = () => problem(403, 'FORBIDDEN', 'You do not have permission for this.')
const notFound = () => problem(404, 'NOT_FOUND', 'Not found.')
const readable = (ws: string) => may(ws, 'workflow.read', 'ai.evaluate', 'ai.approve-skill')
const nowIso = () => new Date().toISOString()

function checked(document: unknown) {
  const doc = (document ?? {}) as Record<string, unknown>
  const problems = checkSkillDocument(doc)
  const entries = Object.entries(problems)
  return entries.length === 0 ? null : problem(400, 'VALIDATION_FAILED', 'The document does not match the AI skill contract.',
    entries.map(([pointer, message]) => ({ pointer: `/document${pointer}`, code: 'invalid', message })))
}

function findVersion(key: string, n: number) {
  const skill = st().skills.find((s) => s.key === key)
  return { skill, version: skill?.versions.find((v) => v.version === n) }
}

/** A mock run finishes a few seconds after it starts. */
function settle(run: Store['runs'][number]): S['AiEvaluation'] {
  if (run.status === 'running' && Date.now() >= run.finishAt) {
    const passed = run.caseCount >= 50
    Object.assign(run, { status: passed ? 'passed' : 'failed', metrics: { ...METRICS, failures: passed ? [] : ['The suite has too few cases.'] }, calibration: CALIBRATION, finishedAt: nowIso() })
    const { version: v } = findVersion(run.skillKey, run.version)
    if (v && passed) v.evaluationGate = { passing: true, runId: run.id }
  }
  const { finishAt: _f, ...view } = run
  return view
}

export function createAiSkillHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/ai-skills`, ({ params }) => (readable(String(params.ws)) ? json({ items: st().skills } satisfies S['AiSkillList']) : forbidden())),
    http.post(`${B}/ai-skills`, async ({ params, request }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const { document } = (await request.json()) as { document: Record<string, unknown> }
      const bad = checked(document)
      if (bad) return bad
      if (st().skills.some((s) => s.key === document.key)) return problem(409, 'DUPLICATE_KEY', 'An AI skill with this key exists already.')
      const skill: S['AiSkill'] = { key: String(document.key), name: String(document.name), ownerId: getCurrentUser().id, createdAt: nowIso(), approvedVersion: null,
        mode: document.mode as S['AiSkill']['mode'], versions: [version(String(document.key), 1, document, { authorId: getCurrentUser().id, createdAt: nowIso(), updatedAt: nowIso() })] }
      st().skills.push(skill)
      return json(skill, 201)
    }),
    http.get(`${B}/ai-skills/:key`, ({ params }) => {
      if (!readable(String(params.ws))) return forbidden()
      const skill = st().skills.find((s) => s.key === params.key)
      return skill ? json(skill) : notFound()
    }),
    http.post(`${B}/ai-skills/:key/versions`, async ({ params, request }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const skill = st().skills.find((s) => s.key === params.key)
      if (!skill) return notFound()
      const { document } = (await request.json()) as { document: Record<string, unknown> }
      const bad = checked(document)
      if (bad) return bad
      if (document.key !== skill.key) return problem(400, 'VALIDATION_FAILED', `The document key must stay ${skill.key}.`, [{ pointer: '/document/key', code: 'invalid', message: `The document key must stay ${skill.key}.` }])
      if (skill.versions.some((v) => v.status === 'draft' || v.status === 'submitted')) return problem(409, 'AI_SKILL_STATE_CONFLICT', 'This skill already has a version in progress. Change it, or withdraw it first.')
      const v = version(skill.key, Math.max(...skill.versions.map((x) => x.version)) + 1, document, { authorId: getCurrentUser().id, createdAt: nowIso(), updatedAt: nowIso() })
      skill.versions.push(v)
      return json(v, 201)
    }),
    http.put(`${B}/ai-skills/:key/versions/:v`, async ({ params, request }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const { skill, version: v } = findVersion(String(params.key), Number(params.v))
      if (!skill || !v) return notFound()
      if (v.status !== 'draft' && v.status !== 'submitted') return problem(409, 'AI_SKILL_STATE_CONFLICT', 'Approved versions do not change. Create a new version instead.')
      const { document } = (await request.json()) as { document: Record<string, unknown> }
      const bad = checked(document)
      if (bad) return bad
      if (document.key !== skill.key) return problem(400, 'VALIDATION_FAILED', `The document key must stay ${skill.key}.`, [{ pointer: '/document/key', code: 'invalid', message: `The document key must stay ${skill.key}.` }])
      Object.assign(v, { document, mode: document.mode, modelProfile: String(document.modelProfile), status: 'draft', submittedAt: null, authorId: getCurrentUser().id, updatedAt: nowIso() })
      skill.name = String(document.name)
      skill.mode = document.mode as S['AiSkill']['mode']
      return json(v)
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/submit`, ({ params }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const { version: v } = findVersion(String(params.key), Number(params.v))
      if (!v) return notFound()
      if (v.status !== 'draft') return problem(409, 'AI_SKILL_STATE_CONFLICT', 'Only a draft can be submitted for approval.')
      Object.assign(v, { status: 'submitted', submittedAt: nowIso(), updatedAt: nowIso() })
      return json(v)
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/approve`, async ({ params, request }) => {
      if (!may(String(params.ws), 'ai.approve-skill')) return forbidden()
      const { skill, version: v } = findVersion(String(params.key), Number(params.v))
      if (!skill || !v) return notFound()
      const { reason } = (await request.json()) as { reason?: string }
      if (!reason?.trim()) return problem(400, 'REASON_REQUIRED', 'Say why this skill version may be used.')
      if (v.status !== 'submitted') return problem(409, 'AI_SKILL_STATE_CONFLICT', 'Only a submitted version can be approved.')
      if (v.authorId === getCurrentUser().id) return problem(403, 'SELF_APPROVAL_DENIED', 'A skill version must be approved by someone other than its author.')
      Object.assign(v, { status: 'approved', approverId: getCurrentUser().id, approvedAt: nowIso(), updatedAt: nowIso() })
      skill.approvedVersion = Math.max(skill.approvedVersion ?? 0, v.version)
      return json(v)
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/retire`, ({ params }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const { skill, version: v } = findVersion(String(params.key), Number(params.v))
      if (!skill || !v) return notFound()
      if (v.status === 'retired') return problem(409, 'AI_SKILL_STATE_CONFLICT', 'This version is already retired.')
      v.status = 'retired'
      const approved = skill.versions.filter((x) => x.status === 'approved').map((x) => x.version)
      skill.approvedVersion = approved.length ? Math.max(...approved) : null
      return json(v)
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/evaluations`, async ({ params, request }) => {
      if (!may(String(params.ws), 'ai.evaluate')) return forbidden()
      const { version: v } = findVersion(String(params.key), Number(params.v))
      if (!v) return notFound()
      const body = (await request.json().catch(() => ({}))) as { suiteKey?: string }
      const suiteKey = body.suiteKey ?? String((v.document?.evaluation as { suite?: string } | undefined)?.suite ?? '')
      const suite = st().suites.find((s) => s.key === suiteKey)
      if (!suite) return problem(400, 'VALIDATION_FAILED', `There is no evaluation suite ${suiteKey}.`, [{ pointer: '/suiteKey', code: 'invalid', message: `There is no evaluation suite ${suiteKey}.` }])
      if (st().runs.some((r) => r.skillKey === v.skillKey && r.version === v.version && settle(r).status === 'running')) return problem(409, 'AI_SKILL_STATE_CONFLICT', 'An evaluation of this version is running.')
      const run = { id: crypto.randomUUID(), skillKey: v.skillKey, version: v.version, suiteKey, suiteHash: suite.contentHash, modelProfile: v.modelProfile, status: 'running' as const,
        caseCount: suite.caseCount, metrics: null, calibration: null, failure: null, startedBy: getCurrentUser().id, startedAt: nowIso(), finishedAt: null, finishAt: Date.now() + 3000 }
      st().runs.unshift(run)
      return json(settle(run), 202)
    }),
    http.get(`${B}/ai-skills/:key/versions/:v/evaluations`, ({ params }) => {
      if (!readable(String(params.ws))) return forbidden()
      return json({ items: st().runs.filter((r) => r.skillKey === params.key && r.version === Number(params.v)).map(settle) } satisfies S['AiEvaluationList'])
    }),
    http.get(`${B}/ai-evaluations/:id`, ({ params }) => {
      if (!readable(String(params.ws))) return forbidden()
      const run = st().runs.find((r) => r.id === params.id)
      return run ? json(settle(run)) : notFound()
    }),
    http.get(`${B}/ai-eval-suites`, ({ params }) => (readable(String(params.ws))
      ? json({ items: st().suites.map(({ document: _d, ...s }) => s) } satisfies S['AiEvalSuiteList']) : forbidden())),
    http.get(`${B}/ai-eval-suites/:key`, ({ params }) => {
      if (!readable(String(params.ws))) return forbidden()
      const suite = st().suites.find((s) => s.key === params.key)
      return suite ? json(suite) : notFound()
    }),
    http.put(`${B}/ai-eval-suites/:key`, async ({ params, request }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const { document } = (await request.json()) as { document: Record<string, unknown> }
      const cases = Array.isArray(document.cases) ? document.cases : []
      if (document.key !== params.key || cases.length === 0) return problem(400, 'VALIDATION_FAILED', 'The suite does not match the evaluation suite contract.', [{ pointer: '/document/cases', code: 'invalid', message: 'Add at least one case.' }])
      const existing = st().suites.find((s) => s.key === params.key)
      const next = { key: String(params.key), skillKey: String(document.skillKey), name: String(document.name), caseCount: cases.length, revision: (existing?.revision ?? 0) + 1,
        contentHash: crypto.randomUUID().replace(/-/g, '').padEnd(64, '0'), updatedAt: nowIso(), document }
      st().suites = [...st().suites.filter((s) => s.key !== params.key), next]
      return json(next)
    }),
    http.get(`${B}/ai-skills/:key/versions/:v/shadow`, ({ params }) => {
      if (!readable(String(params.ws))) return forbidden()
      const { version: v } = findVersion(String(params.key), Number(params.v))
      return v ? json(shadow(v.skillKey, v)) : notFound()
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/promotions`, async ({ params, request }) => {
      if (!may(String(params.ws), 'workflow.edit')) return forbidden()
      const { version: v } = findVersion(String(params.key), Number(params.v))
      if (!v) return notFound()
      const body = (await request.json()) as S['AiSkillPromotionRequest']
      if (!body.reason?.trim()) return problem(400, 'REASON_REQUIRED', 'Say why this skill may decide with more autonomy.')
      if (v.status !== 'approved') return problem(409, 'AI_SKILL_STATE_CONFLICT', 'Only an approved version can be promoted.')
      if (st().promotions.some((p) => p.skillKey === v.skillKey && p.version === v.version && p.status === 'pending')) return problem(409, 'AI_SKILL_STATE_CONFLICT', 'A promotion of this version is pending.')
      const promotion: S['AiSkillPromotion'] = { id: crypto.randomUUID(), skillKey: v.skillKey, version: v.version, fromAutonomy: v.autonomyCeiling, toAutonomy: body.toAutonomy,
        status: 'pending', requestedBy: getCurrentUser().id, requestedAt: nowIso(), reason: body.reason, evidence: shadow(v.skillKey, v), decidedBy: null, decidedAt: null, decisionReason: null }
      st().promotions.unshift(promotion)
      return json(promotion, 201)
    }),
    http.get(`${B}/ai-skills/:key/promotions`, ({ params }) => (readable(String(params.ws))
      ? json({ items: st().promotions.filter((p) => p.skillKey === params.key) } satisfies S['AiSkillPromotionList']) : forbidden())),
    http.post(`${B}/ai-skill-promotions/:id/decision`, async ({ params, request }) => {
      if (!may(String(params.ws), 'ai.approve-skill')) return forbidden()
      const promotion = st().promotions.find((p) => p.id === params.id)
      if (!promotion) return notFound()
      const body = (await request.json()) as S['AiSkillPromotionDecision']
      if (!body.reason?.trim()) return problem(400, 'REASON_REQUIRED', 'Give a reason for the decision.')
      if (promotion.status !== 'pending') return problem(409, 'AI_SKILL_STATE_CONFLICT', 'This promotion was already decided.')
      if (promotion.requestedBy === getCurrentUser().id) return problem(403, 'SELF_APPROVAL_DENIED', 'A promotion must be decided by someone other than the person who asked for it.')
      Object.assign(promotion, { status: body.decision === 'approve' ? 'approved' : 'rejected', decidedBy: getCurrentUser().id, decidedAt: nowIso(), decisionReason: body.reason })
      const { version: v } = findVersion(promotion.skillKey, promotion.version)
      if (v && body.decision === 'approve') v.autonomyCeiling = promotion.toAutonomy
      return json(promotion)
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/drift-check`, ({ params }) => {
      if (!may(String(params.ws), 'ai.evaluate')) return forbidden()
      const { version: v } = findVersion(String(params.key), Number(params.v))
      if (!v) return notFound()
      return json({ skillKey: v.skillKey, version: v.version, cases: 64, agreement: 0.9062, baseline: 0.9167, maxAgreementDrop: 0.1, drifted: false, fellBackToSuggest: false } satisfies S['AiDriftCheck'])
    }),
    http.post(`${B}/ai-skills/:key/versions/:v/clear-drift`, async ({ params, request }) => {
      if (!may(String(params.ws), 'ai.approve-skill')) return forbidden()
      const { version: v } = findVersion(String(params.key), Number(params.v))
      if (!v) return notFound()
      const { reason } = (await request.json()) as { reason?: string }
      if (!reason?.trim()) return problem(400, 'REASON_REQUIRED', 'Say why the skill may decide automatically again.')
      if (!v.driftFallback) return problem(409, 'AI_SKILL_STATE_CONFLICT', 'This version has not fallen back.')
      Object.assign(v, { driftFallback: false, driftReason: null })
      return json(v)
    }),
    http.get(`${B}/ai-review-queue`, ({ params }) => (may(String(params.ws), 'ai.evaluate', 'run.operate') ? json({ items: st().queue } satisfies S['AiReviewQueue']) : forbidden())),
    http.get(`${B}/ai-eval-candidates`, ({ params }) => (may(String(params.ws), 'ai.evaluate') ? json({ items: st().candidates } satisfies S['AiEvalCandidateList']) : forbidden())),
    http.post(`${B}/ai-eval-candidates/:id/review`, async ({ params, request }) => {
      if (!may(String(params.ws), 'ai.evaluate')) return forbidden()
      const candidate = st().candidates.find((c) => c.id === params.id)
      if (!candidate) return problem(409, 'AI_SKILL_STATE_CONFLICT', 'This candidate was already reviewed.')
      const body = (await request.json()) as S['AiEvalCandidateReview']
      if (body.decision === 'accept') {
        const suite = st().suites.find((s) => s.key === body.suiteKey)
        if (!suite) return problem(400, 'VALIDATION_FAILED', `There is no evaluation suite ${body.suiteKey}.`, [{ pointer: '/suiteKey', code: 'invalid', message: 'Choose a suite.' }])
        const cases = suite.document.cases as unknown[]
        cases.push({ id: body.caseId ?? `override-${candidate.id.slice(0, 8)}`, input: body.input ?? {}, expected: body.expected ?? {}, source: 'override' })
        Object.assign(suite, { caseCount: cases.length, revision: suite.revision + 1, updatedAt: nowIso() })
      }
      st().candidates = st().candidates.filter((c) => c.id !== candidate.id)
      return json({ id: candidate.id, status: body.decision === 'accept' ? 'accepted' : 'discarded' } satisfies S['AiEvalCandidateReviewResult'])
    }),
    http.get(`${B}/ai-policy`, ({ params }) => (may(String(params.ws), 'workflow.read', 'ai.evaluate', 'ai.approve-skill', 'workspace.manage-access') ? json(st().policy) : forbidden())),
    http.put(`${B}/ai-policy`, async ({ params, request }) => {
      if (!may(String(params.ws), 'workspace.manage-access')) return forbidden()
      const body = (await request.json()) as S['AiPolicyUpdate']
      const current = st().policy
      if (body.expectedVersion !== current.version) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'The AI policy changed since you loaded it.')
      const regulated = body.regulated ?? current.regulated
      const actions = body.classificationActions ?? current.classificationActions
      if (regulated && actions.sensitive === 'send') return problem(400, 'VALIDATION_FAILED', 'A regulated workspace never sends sensitive fields.', [{ pointer: '/classificationActions/sensitive', code: 'invalid', message: 'A regulated workspace never sends sensitive fields.' }])
      store.policy = { ...current, ...body, regulated, classificationActions: actions, version: current.version + 1, updatedAt: nowIso(), endpoints: current.endpoints } as S['AiPolicy']
      delete (store.policy as Partial<S['AiPolicyUpdate']>).expectedVersion
      return json(store.policy)
    }),
    http.get(`${B}/runs/:runId/ai-decisions`, () => json({ valuesVisible: false, items: [] } satisfies S['AiDecisionList'])),
  ]
}

/** Test helper: the mock store (reset with the mock database). */
export function mockAiStore(): Store {
  return st()
}
