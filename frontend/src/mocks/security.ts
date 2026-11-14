import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { canSeeRunData, createRun, protectedFieldsOf } from './engine'
import { makeRelease } from './seed'
import { auditReaders, clock, dataRevealers, db, isMember, newId, permissionsOf, purchaseTemplate, userById, userByName, WORKSPACES, WS_PURCHASING, type MockUser, type MockWorkflow } from './state'

type S = components['schemas']
type ResourceType = S['ResourceGrant']['resourceType']

/**
 * S151 mock backend: grants on single resources (CG-I01), masked personal and sensitive answers with reveal (CG-I05) and
 * the tamper-evident audit log (CG-I06), for mock mode and unit tests. Grants and checks start empty; seedProtectedRequest
 * adds a workflow with classified questions and one request, so the reveal flow works in mock mode.
 */
const B = '*/api/v1/workspaces/:ws'

const ALLOWED: Record<ResourceType, S['ResourcePermission'][]> = {
  folder: ['workflow.read', 'workflow.edit', 'workflow.publish', 'workflow.start', 'run.read', 'run.operate'],
  workflow: ['workflow.read', 'workflow.edit', 'workflow.publish', 'workflow.start', 'run.read', 'run.operate'],
  decision: ['workflow.read', 'workflow.edit'],
  connection: ['connection.read', 'connection.manage'],
}

const resourceGrants = new Map<string, S['ResourceGrant'][]>()
const chainChecks = new Map<string, S['AuditChainCheck'][]>()
/** Audit events recorded per workspace; every reveal and every check adds one. */
const auditHeads = new Map<string, number>()
/** Tests can simulate a changed audit row: the first broken position per workspace. */
const brokenAt = new Map<string, number>()
/** Reveals recorded, newest last (what the server writes to the audit log). */
export const revealLog: Array<{ wsId: string; runId: string; userId: string; fields: string[] }> = []

export const PROTECTED_WORKFLOW_ID = 'bbbbbbbb-0000-4000-8000-0000000000f1'

export function resetSecurityMock() {
  for (const m of [resourceGrants, chainChecks, auditHeads, brokenAt]) m.clear()
  dataRevealers.clear()
  auditReaders.clear()
  revealLog.length = 0
}

export function grantDataReveal(ws: string, userId: string) {
  dataRevealers.add(`${ws}:${userId}`)
}

export function grantAuditRead(ws: string, userId: string) {
  auditReaders.add(`${ws}:${userId}`)
}

/** Make the next chain check report a break at this position. */
export function breakAuditChain(ws: string, seq: number) {
  brokenAt.set(ws, seq)
}

/**
 * A reimbursement workflow whose bank account (sensitive) and home address (personal) answers are masked, with one
 * request from Rhea waiting at a manager. Returns the run ID.
 */
export function seedProtectedRequest(): string {
  const designer = userByName('dana.designer')
  const definition = purchaseTemplate()
  definition.workflowKey = 'expense-reimbursement'
  definition.name = 'Expense reimbursement'
  definition.inputSchema.properties.homeAddress = { type: 'string', title: 'Home address', maxLength: 300, classification: 'personal' }
  definition.inputSchema.properties.bankAccount = { type: 'string', title: 'Bank account (IBAN)', maxLength: 34, classification: 'sensitive' }
  definition.inputUi = { ...definition.inputUi, order: [...(definition.inputUi?.order ?? []), 'homeAddress', 'bankAccount'] }
  const wf: MockWorkflow = {
    id: PROTECTED_WORKFLOW_ID, wsId: WS_PURCHASING, key: 'expense-reimbursement', name: 'Expense reimbursement',
    description: 'Pay back money spent for work. Bank details are masked for everyone.', ownerId: designer.id,
    drafts: [{ revision: 1, savedAt: clock.iso(), savedById: designer.id, definition: structuredClone(definition) }],
    releases: [], activeReleaseId: null, version: 2, createdAt: clock.iso(), updatedAt: clock.iso(),
  }
  const release = makeRelease(wf.id, 1, 1, definition, userByName('paul.publisher').id, 'First version')
  wf.releases.push(release)
  wf.activeReleaseId = release.id
  db.workflows.set(wf.id, wf)
  const run = createRun({
    wsId: WS_PURCHASING, workflow: wf, release, initiator: userByName('rhea.requester'), businessKey: null,
    input: { title: 'Train tickets for the Berlin workshop', purpose: 'Customer workshop on 3 November.', costCenter: 'OPS', amount: 240, homeAddress: '12 Rose Street, Springfield', bankAccount: 'DE89370400440532013000' },
  })
  return run.id
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const list = <T,>(map: Map<string, T[]>, ws: string): T[] => {
  if (!map.has(ws)) map.set(ws, [])
  return map.get(ws)!
}

const recordAudit = (ws: string) => auditHeads.set(ws, (auditHeads.get(ws) ?? 120) + 1)
const head = (ws: string) => auditHeads.get(ws) ?? 120
const fakeHash = (ws: string, seq: number) => Array.from(`${ws}${seq}`).reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7).toString(16).padStart(8, '0').repeat(8)

export function createSecurityHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const has = (ws: string, ...permissions: string[]) => permissions.some((p) => (permissionsOf(currentUser(), ws) as string[]).includes(p))
  const refused = (ws: string, ...permissions: string[]) =>
    !visible(ws) ? problem(404, 'NOT_FOUND', 'Not found') : !has(ws, ...permissions) ? problem(403, 'FORBIDDEN', 'You are not allowed to do this') : null

  const resourceName = (ws: string, type: ResourceType, id: string): string | null | undefined => {
    if (type === 'folder') { const f = db.workflowFolders.get(id); return f && f.wsId === ws ? f.name : undefined }
    if (type === 'workflow') { const w = db.workflows.get(id); return w && w.wsId === ws ? w.name : undefined }
    return id
  }

  return [
    // ------------------------------------------------------------ resource grants (CG-I01)
    http.get(`${B}/access/resource-grants`, ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const url = new URL(request.url)
      const type = url.searchParams.get('resourceType')
      const id = url.searchParams.get('resourceId')
      const items = list(resourceGrants, ws)
        .filter((g) => (!type || g.resourceType === type) && (!id || g.resourceId === id))
        .map((g) => ({ ...g, resourceName: resourceName(ws, g.resourceType, g.resourceId) ?? null }))
      return HttpResponse.json({ items })
    }),
    http.post(`${B}/access/resource-grants`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const body = (await request.json()) as Partial<{ resourceType: ResourceType; resourceId: string; permission: S['ResourcePermission']; principal: S['PrincipalRef'] }>
      const value = body.principal?.value?.trim()
      if (!body.resourceType || !(body.resourceType in ALLOWED) || !body.resourceId?.trim() || !body.permission || !value || (body.principal?.type !== 'idpGroup' && body.principal?.type !== 'subject')) {
        return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { detail: 'Choose a resource, a permission and a person or sign-in group.' })
      }
      if (!ALLOWED[body.resourceType].includes(body.permission)) {
        return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { detail: 'This permission cannot be given on this kind of resource.', errors: [{ pointer: '/permission', code: 'invalid', message: 'This permission cannot be given on this kind of resource.' }] })
      }
      const name = resourceName(ws, body.resourceType, body.resourceId.trim())
      if (name === undefined) return problem(404, 'NOT_FOUND', 'Not found', { detail: 'The folder or workflow does not exist in this workspace.' })
      const grants = list(resourceGrants, ws)
      if (grants.some((g) => g.resourceType === body.resourceType && g.resourceId === body.resourceId && g.permission === body.permission && g.principal.type === body.principal!.type && g.principal.value === value)) {
        return problem(409, 'DUPLICATE_KEY', 'This grant already exists.')
      }
      const displayName = body.principal.type === 'subject' ? userById(value)?.displayName ?? value : value
      const grant: S['ResourceGrant'] = {
        id: newId(), resourceType: body.resourceType, resourceId: body.resourceId.trim(), resourceName: name, permission: body.permission,
        principal: { type: body.principal.type, value, displayName }, createdAt: clock.iso(),
      }
      grants.push(grant)
      recordAudit(ws)
      return HttpResponse.json(grant, { status: 201 })
    }),
    http.delete(`${B}/access/resource-grants/:id`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workspace.manage-access')
      if (denied) return denied
      const grants = list(resourceGrants, ws)
      const at = grants.findIndex((g) => g.id === String(params.id))
      if (at < 0) return problem(404, 'NOT_FOUND', 'Not found')
      grants.splice(at, 1)
      recordAudit(ws)
      return new HttpResponse(null, { status: 204 })
    }),

    // ------------------------------------------------------------ reveal masked answers (CG-I05)
    http.post(`${B}/runs/:runId/reveal`, async ({ params, request }) => {
      const ws = String(params.ws)
      const user = currentUser()
      const run = db.runs.get(String(params.runId))
      if (!visible(ws) || !run || run.wsId !== ws || !canSeeRunData(run, user)) return problem(404, 'NOT_FOUND', 'Not found')
      if (!has(ws, 'data.reveal')) return problem(403, 'FORBIDDEN', 'You are not allowed to reveal masked answers', { detail: 'Revealing masked answers needs the data.reveal permission.' })
      const text = await request.text()
      const body = (text ? JSON.parse(text) : {}) as { fields?: string[] }
      const protectedFields = protectedFieldsOf(run)
      const wanted = body.fields?.length ? body.fields : protectedFields
      const unknown = wanted.filter((f) => !protectedFields.includes(f))
      if (unknown.length) return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { detail: `Not a masked question: ${unknown.join(', ')}.` })
      const values = Object.fromEntries(wanted.filter((f) => f in run.input).map((f) => [f, run.input[f]]))
      revealLog.push({ wsId: ws, runId: run.id, userId: user.id, fields: wanted })
      recordAudit(ws)
      return HttpResponse.json({ runId: run.id, values, revealedAt: clock.iso() })
    }),

    // ------------------------------------------------------------ tamper-evident audit log (CG-I06)
    http.get(`${B}/audit/chain-verifications`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'audit.read') ?? HttpResponse.json({ items: list(chainChecks, ws).slice(0, 20) })
    }),
    http.post(`${B}/audit/chain-verifications`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'audit.read')
      if (denied) return denied
      const headSeq = head(ws)
      const broken = brokenAt.get(ws)
      const check: S['AuditChainCheck'] = broken !== undefined
        ? { chainKey: ws, checkedAt: clock.iso(), checked: broken, intact: false, brokenSeq: broken, brokenEventId: newId(), reason: 'hashMismatch', headSeq, headHash: fakeHash(ws, headSeq) }
        : { chainKey: ws, checkedAt: clock.iso(), checked: headSeq, intact: true, brokenSeq: null, brokenEventId: null, reason: null, headSeq, headHash: fakeHash(ws, headSeq) }
      list(chainChecks, ws).unshift(check)
      return HttpResponse.json(check)
    }),
    http.get(`${B}/audit/export`, ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'audit.read')
      if (denied) return denied
      const url = new URL(request.url)
      const afterSeq = Number(url.searchParams.get('afterSeq') ?? 0)
      const limit = Math.min(Number(url.searchParams.get('limit') ?? 1000), 5000)
      const last = Math.min(head(ws), afterSeq + limit)
      const count = Math.max(0, last - afterSeq)
      const exported: S['AuditExport'] = {
        format: 'orqix.audit-export.v1', workspaceId: ws, firstSeq: afterSeq + 1, lastSeq: last, eventCount: count,
        firstPrevHash: afterSeq > 0 ? fakeHash(ws, afterSeq) : null, lastHash: count ? fakeHash(ws, last) : null,
        nextAfterSeq: last < head(ws) ? last : null, keyId: 'mock-audit-key-1', algorithm: 'ES256',
        jws: `eyJhbGciOiJFUzI1NiIsImtpZCI6Im1vY2stYXVkaXQta2V5LTEifQ.${btoa(JSON.stringify({ workspaceId: ws, firstSeq: afterSeq + 1, lastSeq: last }))}.mock-signature`,
      }
      return HttpResponse.json(exported)
    }),
    http.get(`${B}/audit/signing-keys`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'audit.read') ?? HttpResponse.json({ keys: [{ kty: 'EC', crv: 'P-256', kid: 'mock-audit-key-1', use: 'sig', alg: 'ES256', x: 'mock-x', y: 'mock-y' }] })
    }),
  ]
}

