import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { clock, db, GROUPS, groupMembers, isMember, newId, permissionsOf, promotionApprovers, userById, WORKSPACES, WS_FACILITIES, type MockUser } from './state'

/**
 * Mock of the promotion API (PL02, docs/build/S54 and S74). A simplified double of the server: stages, a compatibility
 * report from the release's own connection and group references, the approval gate with separation of duties, and
 * history. It does not compile the definition; the compiler is the authority on the real server.
 */
type S = components['schemas']
type Stage = S['WorkspaceEnvironment']['stage']
type Report = S['PromotionReport']
type Env = { stage: Stage; version: number; requireApproval: boolean; explicit: boolean }
type Request = S['PromotionRequest'] & { ws: string; sourceWs: string; sourceWorkflowId: string; releaseId: string }
type History = S['PromotionRecord'] & { sourceWs: string; targetWs: string; workflowId: string; targetWorkflowId: string }

const B = '/api/v1/workspaces/:ws'
const STAGES: Stage[] = ['development', 'test', 'staging', 'production']
let environments = new Map<string, Env>()
let requests: Request[] = []
let records: History[] = []

export function resetMockPromotion(): void {
  environments = new Map()
  requests = []
  records = []
  promotionApprovers.clear()
}

/** Test helpers: set up the stage of a workspace and who holds the approval permission. */
export function setMockStage(ws: string, stage: Stage, requireApproval = false): void {
  environments.set(ws, { stage, version: (environments.get(ws)?.version ?? 0) + 1, requireApproval, explicit: true })
}
export function grantMockApprover(ws: string, userId: string): void {
  promotionApprovers.add(`${ws}:${userId}`)
}
export function mockPromotionRequests(): readonly S['PromotionRequest'][] {
  return requests
}

function envOf(ws: string): Env {
  const found = environments.get(ws)
  if (found) return found
  const workspace = WORKSPACES.find((w) => w.id === ws)
  return { stage: workspace?.isTest ? 'test' : 'production', version: 0, requireApproval: false, explicit: false }
}

const failure = (status: number, code: string, detail: string, extra: Record<string, unknown> = {}) =>
  HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title: detail, status, code, correlationId: newId(), detail, ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } })

export function createPromotionHandlers(getUser: () => MockUser): HttpHandler[] {
  type Gate = { ok: true; user: MockUser; perms: string[] } | { ok: false; res: Response }
  const gate = (wsParam: unknown, ...anyOf: string[]): Gate => {
    const ws = String(wsParam)
    const user = getUser()
    if (!WORKSPACES.some((w) => w.id === ws) || !isMember(user, ws)) return { ok: false, res: failure(404, 'NOT_FOUND', 'Not found') }
    const perms: string[] = permissionsOf(user, ws)
    if (anyOf.length > 0 && !anyOf.some((p) => perms.includes(p))) return { ok: false, res: failure(403, 'FORBIDDEN', 'You are not allowed to do this') }
    return { ok: true, user, perms }
  }

  const view = (r: Request): S['PromotionRequest'] => {
    const { ws: _ws, sourceWs: _sw, sourceWorkflowId: _wf, releaseId: _rel, ...rest } = r
    return rest
  }

  function build(sourceWs: string, workflowId: string, releaseId: string, targetWs: string): { report: Report } | { error: Response } {
    const wf = db.workflows.get(workflowId)
    const release = wf && wf.wsId === sourceWs ? wf.releases.find((r) => r.id === releaseId) : undefined
    if (!wf || !release) return { error: failure(404, 'NOT_FOUND', 'Not found') }
    const sourceStage = envOf(sourceWs).stage
    const target = envOf(targetWs)
    if (STAGES.indexOf(target.stage) <= STAGES.indexOf(sourceStage)) {
      return { error: failure(422, 'VALIDATION_FAILED', `Promotion moves to a later stage: the target is ${target.stage} and the source is ${sourceStage}.`) }
    }
    const issues: Report['issues'] = []
    const connections = release.definition.dependencies.connections.map((c) => {
      const binding = db.bindings.get(`${targetWs}:${c.key}`)
      const status = binding?.enabled ? ('ready' as const) : ('bindingMissing' as const)
      if (status !== 'ready') issues.push({ code: 'CONNECTION_NOT_BOUND', severity: 'blocker', message: `The connection ${c.key} has no address configured in the target.`, path: '/dependencies/connections' })
      return { key: c.key, definitionVersion: c.definitionVersion, status }
    })
    const targetGroups = targetWs === WS_FACILITIES ? [] : GROUPS.map((g) => g.key)
    const groupKeys = [...new Set(release.definition.nodes.filter((n) => n.type === 'approval').map((n) => n.config.candidateGroupRef as string))]
    const groups = groupKeys.map((key) => {
      const exists = targetGroups.includes(key)
      if (!exists) issues.push({ code: 'WF_GROUP_UNKNOWN', severity: 'blocker', message: `The approval group ${key} does not exist in the target.`, path: '/nodes' })
      return { key, exists, memberCount: exists ? groupMembers(key).length : 0 }
    })
    const existing = [...db.workflows.values()].find((w) => w.wsId === targetWs && w.key === wf.key)
    const report: Report = {
      compatible: !issues.some((i) => i.severity === 'blocker'),
      sourceStage,
      targetStage: target.stage,
      approvalRequired: target.stage === 'production' && target.requireApproval,
      targetWorkflow: existing ? { exists: true, id: existing.id, draftRevision: existing.drafts[existing.drafts.length - 1]!.revision } : { exists: false, id: null, draftRevision: null },
      connections,
      groups,
      workflows: [],
      issues,
    }
    return { report }
  }

  /** Common checks of check/promote/request; returns the report or a response. */
  function prepare(wsParam: unknown, workflowId: string, releaseId: string, targetWs: string): { user: MockUser; report: Report; wfKey: string } | { error: Response } {
    const g = gate(wsParam, 'workflow.export')
    if (!g.ok) return { error: g.res }
    const ws = String(wsParam)
    if (targetWs === ws) return { error: failure(422, 'VALIDATION_FAILED', 'Choose a different workspace as the promotion target.') }
    const t = gate(targetWs, 'workflow.edit')
    if (!t.ok) return { error: t.res }
    const built = build(ws, workflowId, releaseId, targetWs)
    if ('error' in built) return built
    return { user: g.user, report: built.report, wfKey: db.workflows.get(workflowId)!.key }
  }

  function writeDraft(user: MockUser, workflowId: string, releaseId: string, targetWs: string, report: Report, expected: number | null | undefined): { promotion: S['ReleasePromotion'] } | { error: Response } {
    const wf = db.workflows.get(workflowId)!
    const release = wf.releases.find((r) => r.id === releaseId)!
    let created = false
    let draftRevision = 1
    let targetId: string
    if (report.targetWorkflow.exists) {
      const existing = db.workflows.get(report.targetWorkflow.id!)!
      const current = existing.drafts[existing.drafts.length - 1]!.revision
      if (expected === undefined || expected === null) return { error: failure(422, 'VALIDATION_FAILED', `The target already has this workflow; send expectedDraftRevision (${current}).`) }
      if (expected !== current) return { error: failure(412, 'STALE_REVISION', 'The target draft was saved by someone else.', { currentRevision: current }) }
      draftRevision = current + 1
      existing.drafts.push({ revision: draftRevision, savedAt: clock.iso(), savedById: user.id, definition: structuredClone(release.definition) })
      targetId = existing.id
    } else {
      if (expected !== undefined && expected !== null) return { error: failure(422, 'VALIDATION_FAILED', 'The target workflow does not exist yet, so there is no draft revision to expect.') }
      created = true
      targetId = newId()
      db.workflows.set(targetId, { id: targetId, wsId: targetWs, key: wf.key, name: wf.name, description: wf.description, ownerId: user.id, drafts: [{ revision: 1, savedAt: clock.iso(), savedById: user.id, definition: structuredClone(release.definition) }], releases: [], activeReleaseId: null, version: 1, createdAt: clock.iso(), updatedAt: clock.iso() })
    }
    return { promotion: { id: newId(), targetWorkspaceId: targetWs, targetWorkflowId: targetId, draftRevision, createdWorkflow: created, report } }
  }

  function remember(user: MockUser, approver: MockUser | null, ws: string, workflowId: string, releaseId: string, promotion: S['ReleasePromotion']): void {
    const wf = db.workflows.get(workflowId)!
    const release = wf.releases.find((r) => r.id === releaseId)!
    records.unshift({ id: promotion.id, direction: 'incoming', sourceWorkspaceKey: WORKSPACES.find((w) => w.id === ws)!.key, targetWorkspaceKey: WORKSPACES.find((w) => w.id === promotion.targetWorkspaceId)!.key, workflowKey: wf.key, releaseNumber: release.releaseNumber, targetDraftRevision: promotion.draftRevision, createdWorkflow: promotion.createdWorkflow, promotedAt: clock.iso(), promotedBy: user.displayName, approvedBy: approver?.displayName ?? null, sourceWs: ws, targetWs: promotion.targetWorkspaceId, workflowId, targetWorkflowId: promotion.targetWorkflowId })
  }

  return [
    http.get(`${B}/environment`, ({ params }) => {
      const g = gate(params.ws)
      if (!g.ok) return g.res
      const e = envOf(String(params.ws))
      return HttpResponse.json({ workspaceId: String(params.ws), stage: e.stage, explicit: e.explicit, version: e.version, requireApproval: e.requireApproval })
    }),
    http.put(`${B}/environment`, async ({ params, request }) => {
      const g = gate(params.ws, 'workspace.manage-access')
      if (!g.ok) return g.res
      const ws = String(params.ws)
      const input = (await request.json()) as { stage: Stage; expectedVersion: number; requireApproval?: boolean | null }
      if (!STAGES.includes(input.stage)) return failure(422, 'VALIDATION_FAILED', 'Unknown stage')
      const current = envOf(ws)
      if (current.version !== input.expectedVersion) return failure(409, 'WORKFLOW_VERSION_CONFLICT', 'The environment changed since you loaded it.', { currentVersion: current.version })
      if (input.requireApproval === true && input.stage !== 'production') return failure(422, 'VALIDATION_FAILED', 'Promotion approval can only be required for a production workspace.')
      const requireApproval = input.stage === 'production' ? (input.requireApproval ?? current.requireApproval) : false
      environments.set(ws, { stage: input.stage, version: current.version + 1, requireApproval, explicit: true })
      return HttpResponse.json({ workspaceId: ws, stage: input.stage, explicit: true, version: current.version + 1, requireApproval })
    }),
    http.post(`${B}/workflows/:wf/releases/:rel/promotion-check`, async ({ params, request }) => {
      const input = (await request.json()) as { targetWorkspaceId: string }
      const prepared = prepare(params.ws, String(params.wf), String(params.rel), input.targetWorkspaceId)
      return 'error' in prepared ? prepared.error : HttpResponse.json(prepared.report)
    }),
    http.post(`${B}/workflows/:wf/releases/:rel/promote`, async ({ params, request }) => {
      const input = (await request.json()) as { targetWorkspaceId: string; expectedDraftRevision?: number | null }
      const prepared = prepare(params.ws, String(params.wf), String(params.rel), input.targetWorkspaceId)
      if ('error' in prepared) return prepared.error
      if (prepared.report.approvalRequired) return failure(409, 'PROMOTION_APPROVAL_REQUIRED', 'The target requires approval.')
      if (!prepared.report.compatible) return failure(422, 'WORKFLOW_INVALID', 'The release cannot be promoted.', { issues: prepared.report.issues })
      const written = writeDraft(prepared.user, String(params.wf), String(params.rel), input.targetWorkspaceId, prepared.report, input.expectedDraftRevision)
      if ('error' in written) return written.error
      remember(prepared.user, null, String(params.ws), String(params.wf), String(params.rel), written.promotion)
      return HttpResponse.json(written.promotion, { status: 201 })
    }),
    http.post(`${B}/workflows/:wf/releases/:rel/promotion-requests`, async ({ params, request }) => {
      const input = (await request.json()) as { targetWorkspaceId: string; expectedDraftRevision?: number | null; note?: string | null }
      const prepared = prepare(params.ws, String(params.wf), String(params.rel), input.targetWorkspaceId)
      if ('error' in prepared) return prepared.error
      if (!prepared.report.approvalRequired) return failure(422, 'VALIDATION_FAILED', 'The target does not require approval: promote the release directly.')
      if (!prepared.report.compatible) return failure(422, 'WORKFLOW_INVALID', 'The release cannot be promoted.', { issues: prepared.report.issues })
      if (requests.some((r) => r.status === 'pending' && r.ws === input.targetWorkspaceId && r.workflowKey === prepared.wfKey)) return failure(409, 'DUPLICATE_KEY', 'A request is already waiting.')
      const wf = db.workflows.get(String(params.wf))!
      const row: Request = { id: newId(), status: 'pending', sourceWorkspaceKey: WORKSPACES.find((w) => w.id === String(params.ws))!.key, workflowKey: wf.key, releaseNumber: wf.releases.find((r) => r.id === String(params.rel))!.releaseNumber, targetWorkspaceId: input.targetWorkspaceId, expectedDraftRevision: input.expectedDraftRevision ?? null, note: input.note?.trim() || null, requestedById: prepared.user.id, requestedBy: prepared.user.displayName, requestedAt: clock.iso(), decidedBy: null, decidedAt: null, decisionNote: null, promotionId: null, ws: input.targetWorkspaceId, sourceWs: String(params.ws), sourceWorkflowId: wf.id, releaseId: String(params.rel) }
      requests.unshift(row)
      return HttpResponse.json(view(row), { status: 201 })
    }),
    http.get(`${B}/workflows/:wf/promotions`, ({ params }) => {
      const g = gate(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const ws = String(params.ws)
      const items = records
        .filter((r) => (r.targetWs === ws && r.targetWorkflowId === String(params.wf)) || (r.sourceWs === ws && r.workflowId === String(params.wf)))
        .map((r) => ({ ...r, direction: r.targetWs === ws ? ('incoming' as const) : ('outgoing' as const) }))
        .filter((r) => r.direction === 'incoming' || isMember(g.user, r.targetWs))
        .map(({ sourceWs: _s, targetWs: _t, workflowId: _w, targetWorkflowId: _tw, ...rest }) => rest)
      return HttpResponse.json({ items })
    }),
    http.get(`${B}/promotion-requests`, ({ params, request }) => {
      const g = gate(params.ws, 'workflow.read', 'workflow.edit', 'workflow.approve-promotion')
      if (!g.ok) return g.res
      const all = new URL(request.url).searchParams.get('status') === 'all'
      return HttpResponse.json({ items: requests.filter((r) => r.ws === String(params.ws) && (all || r.status === 'pending')).map(view) })
    }),
    http.post(`${B}/promotion-requests/:id/approve`, async ({ params }) => {
      const g = gate(params.ws, 'workflow.approve-promotion')
      if (!g.ok) return g.res
      const row = requests.find((r) => r.id === String(params.id) && r.ws === String(params.ws))
      if (!row) return failure(404, 'NOT_FOUND', 'Not found')
      if (row.status !== 'pending') return failure(409, 'WORKFLOW_VERSION_CONFLICT', `The request was already ${row.status}.`)
      if (row.requestedById === g.user.id) return failure(403, 'SELF_APPROVAL_DENIED', 'You cannot approve your own request')
      const built = build(row.sourceWs, row.sourceWorkflowId, row.releaseId, row.ws)
      if ('error' in built) return built.error
      if (!built.report.compatible) return failure(422, 'WORKFLOW_INVALID', 'The release cannot be promoted now.', { issues: built.report.issues })
      const requester = userById(row.requestedById) ?? g.user
      const written = writeDraft(requester, row.sourceWorkflowId, row.releaseId, row.ws, built.report, row.expectedDraftRevision)
      if ('error' in written) return written.error
      remember(requester, g.user, row.sourceWs, row.sourceWorkflowId, row.releaseId, written.promotion)
      Object.assign(row, { status: 'approved', decidedBy: g.user.displayName, decidedAt: clock.iso(), promotionId: written.promotion.id })
      return HttpResponse.json(view(row))
    }),
    http.post(`${B}/promotion-requests/:id/reject`, async ({ params, request }) => {
      const g = gate(params.ws, 'workflow.approve-promotion')
      if (!g.ok) return g.res
      const input = (await request.json().catch(() => ({}))) as { note?: string | null }
      if (!input.note?.trim()) return failure(422, 'REASON_REQUIRED', 'A reason is required')
      const row = requests.find((r) => r.id === String(params.id) && r.ws === String(params.ws))
      if (!row) return failure(404, 'NOT_FOUND', 'Not found')
      if (row.status !== 'pending') return failure(409, 'WORKFLOW_VERSION_CONFLICT', `The request was already ${row.status}.`)
      if (row.requestedById === g.user.id) return failure(403, 'SELF_APPROVAL_DENIED', 'You cannot approve your own request')
      Object.assign(row, { status: 'rejected', decidedBy: g.user.displayName, decidedAt: clock.iso(), decisionNote: input.note.trim() })
      return HttpResponse.json(view(row))
    }),
    http.post(`${B}/promotion-requests/:id/cancel`, ({ params }) => {
      const g = gate(params.ws)
      if (!g.ok) return g.res
      const row = requests.find((r) => r.id === String(params.id) && r.ws === String(params.ws))
      if (!row) return failure(404, 'NOT_FOUND', 'Not found')
      if (row.requestedById !== g.user.id) return failure(403, 'FORBIDDEN', 'You are not allowed to do this')
      if (row.status !== 'pending') return failure(409, 'WORKFLOW_VERSION_CONFLICT', `The request was already ${row.status}.`)
      Object.assign(row, { status: 'cancelled', decidedBy: g.user.displayName, decidedAt: clock.iso() })
      return HttpResponse.json(view(row))
    }),
  ]
}

