import { delay, http, HttpResponse, type HttpHandler, type JsonBodyType } from 'msw'
import type { Permission, ProblemCode, TaskDetail, ValidationIssue } from '@/api/types'
import { nodeById } from '@/features/studio/model/graph'
import { addEvent, evaluateTable, claim, createRun, decide, cancelRun, currentRelease, reconcileRun, retryRun, release as releaseTask, selfApprovalAllowed, toRun, toRunDetail, toTaskSummary } from './engine'
import { makeRelease } from './seed'
import { normalizeDefinition, type Definition, type Literal } from '@/features/studio/model/types'
import { buildFields, parseFormSchema } from '@/lib/form/schema'
import { validateJsonInput } from '@/lib/form/validate'
import { validateDefinition, type ValidationContext } from './validate'
import {
  GROUPS,
  TEMPLATES,
  WORKSPACES,
  USERS,
  allMemberships,
  blankDefinition,
  clock,
  connectionDefinition,
  agentConnectionDefinition,
  db,
  groupMembers,
  inGroup,
  isMember,
  newId,
  permissionsOf,
  purchaseTemplate,
  onboardingTemplate,
  rolesOf,
  userById,
  userByName,
  type MockRun,
  type MockTask,
  type MockUser,
  type MockWorkflow,
} from './state'

// Current persona ---------------------------------------------------------------------------------------

let currentUsername = 'rhea.requester'
export function setCurrentUser(username: string): void {
  currentUsername = username
}
export function getCurrentUser(): MockUser {
  return userByName(currentUsername)
}

// Response helpers ----------------------------------------------------------------------------------------

function problem(status: number, code: ProblemCode, title: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}): Response {
  return HttpResponse.json(
    {
      type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`,
      title,
      status,
      code,
      correlationId: newId(),
      ...extra,
    },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  )
}

const notFound = () => problem(404, 'NOT_FOUND', 'Not found', { detail: 'The resource does not exist or is not visible to you.' })
const forbidden = (detail = 'You do not have permission to do this.') => problem(403, 'FORBIDDEN', 'Forbidden', { detail })

type Guard = { ok: true; user: MockUser; perms: Permission[] } | { ok: false; res: Response }

/** Member check (404 for outsiders) then any-of permission check (403). */
function guard(wsId: string | readonly string[] | undefined, ...anyOf: Permission[]): Guard {
  const id = Array.isArray(wsId) ? wsId[0] : (wsId as string | undefined)
  const user = getCurrentUser()
  if (!id || !WORKSPACES.some((w) => w.id === id) || !isMember(user, id)) return { ok: false, res: notFound() }
  const perms = permissionsOf(user, id)
  if (anyOf.length > 0 && !anyOf.some((p) => perms.includes(p))) return { ok: false, res: forbidden() }
  return { ok: true, user, perms }
}

function str(value: string | readonly string[] | undefined): string {
  return Array.isArray(value) ? (value[0] ?? '') : ((value as string | undefined) ?? '')
}

function paginate<T>(items: T[], url: URL): { items: T[]; nextCursor: string | null } {
  const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') ?? 25) || 25))
  const cursor = url.searchParams.get('cursor')
  let offset = 0
  if (cursor) {
    offset = Number.parseInt(cursor, 10)
    if (!Number.isFinite(offset) || offset < 0) offset = 0
  }
  const slice = items.slice(offset, offset + limit)
  return { items: slice, nextCursor: offset + limit < items.length ? String(offset + limit) : null }
}

const subjectRef = (u: MockUser | undefined) => (u ? { id: u.id, displayName: u.displayName } : { id: newId(), displayName: 'Unknown' })

async function body<T = Record<string, unknown>>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T
  } catch {
    return {} as T
  }
}

/** Idempotent command wrapper (docs/build/S3-api.md section 5). */
function idempotent(request: Request, user: MockUser, operation: string, payload: unknown, exec: () => { status: number; body: JsonBodyType; headers?: Record<string, string> } | Response): Response {
  const key = request.headers.get('Idempotency-Key')
  if (!key || !/^[A-Za-z0-9._:-]{8,128}$/.test(key)) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key header required')
  const stored = `${user.id}|${operation}|${key}`
  const hash = JSON.stringify([operation, new URL(request.url).pathname, payload])
  const prior = db.idempotency.get(stored)
  if (prior) {
    if (prior.hash !== hash) return problem(409, 'IDEMPOTENCY_KEY_REUSED', 'Idempotency key reused with a different request')
    return HttpResponse.json(prior.body as JsonBodyType, { status: 200, headers: { 'Idempotent-Replayed': 'true' } })
  }
  const result = exec()
  if (result instanceof Response) return result
  db.idempotency.set(stored, { hash, status: result.status, body: result.body })
  return HttpResponse.json(result.body, { status: result.status, headers: result.headers })
}

// Workspace and workflow representations ----------------------------------------------------------------------

function membership(user: MockUser, wsId: string) {
  const ws = WORKSPACES.find((w) => w.id === wsId)!
  return { id: ws.id, key: ws.key, name: ws.name, organizationName: ws.organizationName, isTest: ws.isTest, permissions: permissionsOf(user, wsId) }
}

function workflowsOf(wsId: string): MockWorkflow[] {
  return [...db.workflows.values()].filter((w) => w.wsId === wsId)
}

function activeRelease(wf: MockWorkflow) {
  return wf.releases.find((r) => r.id === wf.activeReleaseId) ?? null
}

function summary(wf: MockWorkflow, perms: Permission[]) {
  const active = activeRelease(wf)
  return {
    id: wf.id,
    key: wf.key,
    name: wf.name,
    description: wf.description,
    owner: subjectRef(userById(wf.ownerId)),
    draftRevision: wf.drafts.at(-1)?.revision ?? 1,
    activeRelease: active ? { id: active.id, releaseNumber: active.releaseNumber, publishedAt: active.publishedAt } : null,
    canStart: !!active && perms.includes('workflow.start'),
    updatedAt: wf.updatedAt,
  }
}

function releaseSummary(wf: MockWorkflow, r: MockWorkflow['releases'][number]) {
  return {
    id: r.id,
    workflowId: wf.id,
    releaseNumber: r.releaseNumber,
    draftRevision: r.draftRevision,
    publishedAt: r.publishedAt,
    publishedBy: subjectRef(userById(r.publishedById)),
    isActive: wf.activeReleaseId === r.id,
    compilerVersion: r.compilerVersion,
    releaseNote: r.releaseNote,
  }
}

function releaseFull(wf: MockWorkflow, r: MockWorkflow['releases'][number]) {
  return {
    ...releaseSummary(wf, r),
    sourceHash: r.sourceHash,
    semanticHash: r.semanticHash,
    warnings: r.warnings,
    manifest: {
      releaseId: r.id,
      workflowKey: wf.key,
      schemaVersion: '0.1',
      compilerVersion: r.compilerVersion,
      sourceHash: r.sourceHash,
      semanticHash: r.semanticHash,
      bpmnHash: r.sourceHash.split('').reverse().join(''),
      dmnHashes: {},
      processDefinitionId: `${wf.key}:${r.releaseNumber}:mock`,
      decisions: r.definition.decisions.map((d) => ({ key: d.key, version: d.version })),
      connections: r.definition.dependencies.connections.map((c) => ({ key: c.key, definitionVersion: c.definitionVersion, definitionHash: 'f'.repeat(64) })),
      groups: [...new Set(r.definition.nodes.filter((n) => n.type === 'approval').map((n) => n.config.candidateGroupRef as string))],
    },
    definition: r.definition,
  }
}

function validationContext(wsId: string): ValidationContext {
  const ws = WORKSPACES.find((w) => w.id === wsId)
  const binding = db.bindings.get(`${wsId}:procurement`)
  const ops = connectionDefinition.operations as Record<string, { requestSchema?: { required?: string[]; properties?: Record<string, unknown> }; responseSchema?: { properties?: Record<string, unknown> }; maxTimeoutSeconds: number }>
  return {
    groups: wsId === WORKSPACES[2]?.id ? [] : GROUPS.map((g) => g.key),
    isTestWorkspace: ws?.isTest ?? false,
    publishedWorkflows: [...db.workflows.values()].filter((w) => w.wsId === wsId && w.activeReleaseId).map((w) => w.key),
    connections:
      wsId === WORKSPACES[2]?.id
        ? []
        : [
            {
              key: 'procurement',
              versions: [1],
              bound: !!binding?.enabled,
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

function findWorkflow(wsId: string, id: string): MockWorkflow | undefined {
  const wf = db.workflows.get(id)
  return wf && wf.wsId === wsId ? wf : undefined
}

/** Visible to the assignee, the decider, the initiator and eligible candidates (also after completion, so a late approver can see who won). */
function isOpenTaskVisible(user: MockUser, task: MockTask, run: MockRun): boolean {
  return task.assigneeId === user.id || task.completedById === user.id || inGroup(user, task.candidateGroup) || run.initiatorId === user.id
}

function taskDetail(user: MockUser, perms: Permission[], run: MockRun, task: MockTask): TaskDetail {
  const rel = currentRelease(run)
  const summaryValue = toTaskSummary(run, task)
  const isAssignee = task.assigneeId === user.id
  const open = task.status === 'open'
  const eligibleGroup = inGroup(user, task.candidateGroup) && perms.includes('task.act')
  const own = run.initiatorId === user.id && !selfApprovalAllowed(run, rel)
  const claimAllowed = open && !task.assigneeId && eligibleGroup && !own
  return {
    ...summaryValue,
    request: { input: run.input, inputSchema: rel.definition.inputSchema as never, inputUi: (rel.definition.inputUi ?? {}) as never },
    history: run.tasks.filter((t) => t.decision).map((t) => t.decision!).sort((a, b) => a.decidedAt.localeCompare(b.decidedAt)),
    form: { rejectReasonRequired: true },
    completedBy: task.completedById ? subjectRef(userById(task.completedById)) : null,
    allowedActions: {
      claim: claimAllowed ? { allowed: true } : { allowed: false, reasonCode: !open ? 'TASK_NOT_OPEN' : task.assigneeId ? 'TASK_ALREADY_CLAIMED' : own ? 'SELF_APPROVAL_DENIED' : 'NOT_ELIGIBLE' },
      release: open && isAssignee ? { allowed: true } : { allowed: false, reasonCode: 'TASK_NOT_CLAIMED_BY_CALLER' },
      decide: open && isAssignee && perms.includes('task.act') ? { allowed: true } : { allowed: false, reasonCode: !open ? 'TASK_NOT_OPEN' : 'TASK_NOT_CLAIMED_BY_CALLER' },
      reassign: open && perms.includes('run.operate') ? { allowed: true } : { allowed: false, reasonCode: 'FORBIDDEN' },
    },
  } as unknown as TaskDetail
}

function findTask(wsId: string, taskId: string): { run: MockRun; task: MockTask } | undefined {
  for (const run of db.runs.values()) {
    if (run.wsId !== wsId) continue
    const task = run.tasks.find((t) => t.id === taskId)
    if (task) return { run, task }
  }
  return undefined
}

function canReadRun(user: MockUser, perms: Permission[], run: MockRun): boolean {
  return perms.includes('run.read') || run.initiatorId === user.id || run.tasks.some((t) => isOpenTaskVisible(user, t, run))
}

function problemFromValidation(issues: ValidationIssue[]) {
  return { issues }
}

// Handlers --------------------------------------------------------------------------------------------------------

const B = '*/api/v1'

export function createHandlers(): HttpHandler[] {
  const slow = async () => {
    if (db.latencyMs > 0) await delay(db.latencyMs)
  }

  return [
    // Identity ------------------------------------------------------------------------------------------------
    http.get(`${B}/me`, async () => {
      await slow()
      const user = getCurrentUser()
      return HttpResponse.json({
        subject: { id: user.id, displayName: user.displayName, email: user.email },
        platformAdmin: user.platformAdmin,
        workspaces: WORKSPACES.filter((w) => isMember(user, w.id)).map((w) => membership(user, w.id)),
      })
    }),
    http.get(`${B}/workspaces/:ws`, async ({ params }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      return HttpResponse.json(membership(g.user, str(params.ws)))
    }),

    // Templates and workflows ---------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/templates`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      return g.ok ? HttpResponse.json({ items: TEMPLATES }) : g.res
    }),
    http.get(`${B}/workspaces/:ws/workflows`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read', 'workflow.start')
      if (!g.ok) return g.res
      const url = new URL(request.url)
      const q = url.searchParams.get('q')?.toLowerCase()
      const startable = url.searchParams.get('startable') === 'true'
      const readOnlyStart = !g.perms.includes('workflow.read')
      let items = workflowsOf(str(params.ws))
      if (readOnlyStart || startable) items = items.filter((w) => !!activeRelease(w) && g.perms.includes('workflow.start'))
      if (q) items = items.filter((w) => w.name.toLowerCase().includes(q) || w.key.includes(q))
      items.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      const page = paginate(items, url)
      return HttpResponse.json({ items: page.items.map((w) => summary(w, g.perms)), nextCursor: page.nextCursor })
    }),
    http.post(`${B}/workspaces/:ws/workflows`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const input = await body<{ key: string; name: string; description?: string; templateId?: string }>(request)
      if (!input.key || !input.name) return problem(422, 'VALIDATION_FAILED', 'Invalid request', { fieldErrors: [{ pointer: '/key', code: 'required', message: 'Enter a name.' }] })
      if (workflowsOf(str(params.ws)).some((w) => w.key === input.key)) {
        return problem(409, 'DUPLICATE_KEY', 'A workflow with this key already exists', { fieldErrors: [{ pointer: '/key', code: 'duplicate', message: 'A workflow with this key already exists.' }] })
      }
      const definition: Definition = input.templateId === 'purchase-approval' ? purchaseTemplate() : input.templateId === 'new-hire-onboarding' ? onboardingTemplate() : blankDefinition(input.key, input.name)
      definition.workflowKey = input.key
      definition.name = input.name
      if (input.description) definition.metadata.description = input.description
      const wf: MockWorkflow = {
        id: newId(),
        wsId: str(params.ws),
        key: input.key,
        name: input.name,
        description: input.description ?? null,
        ownerId: g.user.id,
        drafts: [{ revision: 1, savedAt: clock.iso(), savedById: g.user.id, definition }],
        releases: [],
        activeReleaseId: null,
        version: 1,
        createdAt: clock.iso(),
        updatedAt: clock.iso(),
      }
      db.workflows.set(wf.id, wf)
      return HttpResponse.json({ ...summary(wf, g.perms), version: wf.version, createdAt: wf.createdAt, permissions: g.perms }, { status: 201, headers: { ETag: '"r1"' } })
    }),
    http.get(`${B}/workspaces/:ws/workflows/:id`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      return HttpResponse.json({ ...summary(wf, g.perms), version: wf.version, createdAt: wf.createdAt, permissions: g.perms })
    }),

    // Drafts ---------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/workflows/:id/draft`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const d = wf.drafts.at(-1)!
      return HttpResponse.json(
        { workflowId: wf.id, revision: d.revision, savedAt: d.savedAt, savedBy: subjectRef(userById(d.savedById)), definition: d.definition },
        { headers: { ETag: `"r${d.revision}"` } },
      )
    }),
    http.put(`${B}/workspaces/:ws/workflows/:id/draft`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const ifMatch = request.headers.get('If-Match')
      if (!ifMatch) return problem(428, 'PRECONDITION_REQUIRED', 'If-Match header required')
      const latest = wf.drafts.at(-1)!
      if (ifMatch !== `"r${latest.revision}"`) return problem(412, 'STALE_REVISION', 'The draft changed', { currentRevision: latest.revision })
      const input = await body<{ definition?: Record<string, unknown> }>(request)
      if (!input.definition || input.definition.schemaVersion !== '0.1') return problem(400, 'MALFORMED_REQUEST', 'schemaVersion must be "0.1"')
      if (JSON.stringify(input.definition).length > 256 * 1024) return problem(413, 'PAYLOAD_TOO_LARGE', 'Draft too large')
      const next = { revision: latest.revision + 1, savedAt: clock.iso(), savedById: g.user.id, definition: normalizeDefinition(input.definition) }
      wf.drafts.push(next)
      if (wf.drafts.length > 50) wf.drafts.splice(0, wf.drafts.length - 50)
      wf.updatedAt = next.savedAt
      wf.name = next.definition.name || wf.name
      return HttpResponse.json(
        { workflowId: wf.id, revision: next.revision, savedAt: next.savedAt, savedBy: subjectRef(g.user), definition: next.definition },
        { headers: { ETag: `"r${next.revision}"` } },
      )
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/validate`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const { revision } = await body<{ revision: number }>(request)
      const draft = wf.drafts.find((d) => d.revision === revision)
      if (!draft) return notFound()
      const issues = validateDefinition(draft.definition, validationContext(wf.wsId))
      return HttpResponse.json({ revision, compilerVersion: '0.1.0-mock', publishable: !issues.some((i) => i.severity === 'blocker'), semanticHash: null, issues })
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/decisions/:key/evaluate`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const input = await body<{ revision: number; values: Record<string, Literal> }>(request)
      const draft = wf.drafts.find((d) => d.revision === input.revision)
      const table = draft?.definition.decisions.find((d) => d.key === str(params.key))
      if (!draft || !table) return notFound()
      const result = evaluateTable(table, input.values ?? {})
      return HttpResponse.json(result)
    }),

    // Releases -------------------------------------------------------------------------------------------------------
    http.post(`${B}/workspaces/:ws/workflows/:id/publish`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.publish')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const input = await body<{ draftRevision: number; acknowledgeWarnings?: boolean; activate?: boolean; releaseNote?: string }>(request)
      return idempotent(request, g.user, 'publishDraft', input, () => {
        const draft = wf.drafts.find((d) => d.revision === input.draftRevision)
        if (!draft) return notFound()
        const issues = validateDefinition(draft.definition, validationContext(wf.wsId))
        if (issues.some((i) => i.severity === 'blocker')) return problem(422, 'WORKFLOW_INVALID', 'The workflow has blocking problems', problemFromValidation(issues))
        const warnings = issues.filter((i) => i.severity === 'warning')
        if (warnings.length > 0 && !input.acknowledgeWarnings) return problem(422, 'WARNINGS_NOT_ACKNOWLEDGED', 'Warnings must be acknowledged', problemFromValidation(issues))
        const number = Math.max(0, ...wf.releases.map((r) => r.releaseNumber)) + 1
        const rel = makeRelease(wf.id, number, draft.revision, draft.definition, g.user.id, input.releaseNote ?? null)
        rel.warnings = warnings
        wf.releases.push(rel)
        if (input.activate !== false) {
          wf.activeReleaseId = rel.id
          wf.version += 1
        }
        wf.updatedAt = clock.iso()
        return { status: 201, body: releaseFull(wf, rel) }
      })
    }),
    http.get(`${B}/workspaces/:ws/workflows/:id/releases`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const page = paginate([...wf.releases].sort((a, b) => b.releaseNumber - a.releaseNumber), new URL(request.url))
      return HttpResponse.json({ items: page.items.map((r) => releaseSummary(wf, r)), nextCursor: page.nextCursor })
    }),
    http.get(`${B}/workspaces/:ws/workflows/:id/releases/:rid`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      const rel = wf?.releases.find((r) => r.id === str(params.rid))
      if (!wf || !rel) return notFound()
      return HttpResponse.json(releaseFull(wf, rel))
    }),
    http.get(`${B}/workspaces/:ws/workflows/:id/releases/:rid/export`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.export')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      const rel = wf?.releases.find((r) => r.id === str(params.rid))
      if (!wf || !rel) return notFound()
      const content = `Orqix mock export\nrelease ${rel.releaseNumber} of ${wf.key}\n\n${JSON.stringify(rel.definition, null, 2)}\n`
      return new HttpResponse(content, {
        status: 200,
        headers: { 'Content-Type': 'application/zip', 'Content-Disposition': `attachment; filename="${wf.key}-r${rel.releaseNumber}.zip"` },
      })
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/active-release`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.publish')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const input = await body<{ releaseId: string; expectedVersion: number }>(request)
      if (input.expectedVersion !== wf.version) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The workflow changed', { currentVersion: wf.version })
      if (!wf.releases.some((r) => r.id === input.releaseId)) return notFound()
      wf.activeReleaseId = input.releaseId
      wf.version += 1
      wf.updatedAt = clock.iso()
      return HttpResponse.json({ ...summary(wf, g.perms), version: wf.version, createdAt: wf.createdAt, permissions: g.perms })
    }),

    // Runs ---------------------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/workflows/:id/start-form`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.start')
      if (!g.ok) return g.res
      const wf = findWorkflow(str(params.ws), str(params.id))
      if (!wf) return notFound()
      const rel = activeRelease(wf)
      if (!rel) return problem(422, 'NO_ACTIVE_RELEASE', 'No active release')
      return HttpResponse.json({
        workflowId: wf.id,
        workflowName: wf.name,
        releaseId: rel.id,
        releaseNumber: rel.releaseNumber,
        inputSchema: rel.definition.inputSchema,
        inputUi: rel.definition.inputUi ?? {},
        isTest: WORKSPACES.find((w) => w.id === wf.wsId)?.isTest ?? false,
      })
    }),
    http.get(`${B}/workspaces/:ws/runs`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'run.read')
      if (!g.ok) return g.res
      const url = new URL(request.url)
      let runs = [...db.runs.values()].filter((r) => r.wsId === str(params.ws))
      const lifecycle = url.searchParams.get('lifecycle')
      const workflowId = url.searchParams.get('workflowId')
      const key = url.searchParams.get('businessKey')
      if (lifecycle) runs = runs.filter((r) => r.lifecycle === lifecycle)
      if (workflowId) runs = runs.filter((r) => r.workflowId === workflowId)
      if (key) runs = runs.filter((r) => r.businessKey === key || r.reference === key)
      runs.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      const page = paginate(runs, url)
      return HttpResponse.json({ items: page.items.map(toRun), nextCursor: page.nextCursor })
    }),
    http.post(`${B}/workspaces/:ws/runs`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.start')
      if (!g.ok) return g.res
      const input = await body<{ workflowId: string; expectedReleaseId?: string; businessKey?: string; input: Record<string, unknown> }>(request)
      return idempotent(request, g.user, 'startRun', input, () => {
        const wf = findWorkflow(str(params.ws), input.workflowId)
        if (!wf) return notFound()
        const rel = activeRelease(wf)
        if (!rel) return problem(422, 'NO_ACTIVE_RELEASE', 'No active release')
        if (input.expectedReleaseId && input.expectedReleaseId !== rel.id) return problem(409, 'RELEASE_NOT_ACTIVE', 'The form was updated')
        const schema = parseFormSchema(rel.definition.inputSchema)
        const fields = buildFields(schema, {})
        const issues = validateJsonInput(schema, input.input ?? {})
        if (issues.length > 0) {
          return problem(422, 'VALIDATION_FAILED', 'The request has invalid fields', {
            fieldErrors: issues.map((i) => ({
              pointer: `/input${i.pointer}`,
              code: i.keyword,
              message: `${fields.find((f) => `/${f.key}` === i.pointer)?.label ?? i.pointer.slice(1)} is not valid (${i.keyword}).`,
            })),
          })
        }
        const active = [...db.runs.values()].filter((r) => r.workflowId === wf.id && (r.lifecycle === 'running' || r.lifecycle === 'waiting' || r.lifecycle === 'needsAttention')).length
        if (active >= rel.definition.execution.maxConcurrentRuns) return problem(429, 'QUOTA_EXCEEDED', 'Too many open requests', {}, { 'Retry-After': '60' })
        const run = createRun({ wsId: str(params.ws), workflow: wf, release: rel, initiator: g.user, input: input.input, businessKey: input.businessKey ?? null })
        return { status: 201, body: toRun(run), headers: { Location: `/api/v1/workspaces/${run.wsId}/runs/${run.id}` } }
      })
    }),
    http.get(`${B}/workspaces/:ws/runs/:id`, async ({ params }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      if (!run || run.wsId !== str(params.ws) || !canReadRun(g.user, g.perms, run)) return notFound()
      return HttpResponse.json(toRunDetail(run, g.user))
    }),
    http.get(`${B}/workspaces/:ws/runs/:id/comments`, async ({ params, request }) => {
      const g = guard(params.ws); if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      if (!run || run.wsId !== str(params.ws) || !canReadRun(g.user, g.perms, run)) return notFound()
      if (!toRunDetail(run,g.user).input) return forbidden()
      const before = new URL(request.url).searchParams.get('before')
      const comments = [...(run.comments ?? [])].reverse()
      const start = before ? comments.findIndex(c => c.id === before) + 1 : 0
      return HttpResponse.json({ comments: comments.slice(start,start+50), hasMore: comments.length > start+50 })
    }),
    http.post(`${B}/workspaces/:ws/runs/:id/comments`, async ({ params, request }) => {
      const g = guard(params.ws); if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      if (!run || run.wsId !== str(params.ws) || !canReadRun(g.user, g.perms, run)) return notFound()
      const participant = run.tasks.some(t => t.assigneeId === g.user.id || t.completedById === g.user.id || (g.perms.includes('task.act') && inGroup(g.user,t.candidateGroup) && (selfApprovalAllowed(run, currentRelease(run)) || run.initiatorId !== g.user.id)))
      if (!toRunDetail(run,g.user).input || !(run.initiatorId === g.user.id || participant || g.perms.includes('run.operate'))) return forbidden()
      const input = await body<{ text: string }>(request)
      if (typeof input.text !== 'string' || !input.text.trim() || input.text.length > 2000) return problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { fieldErrors: [{ pointer: '/text', code: 'maxLength', message: 'Comment must contain 1 to 2000 characters.' }] })
      return idempotent(request, g.user, `comment-${run.id}`, input, () => {
        const comment = { id: newId(), runId: run.id, author: { id: g.user.id, displayName: g.user.displayName }, text: input.text.trim(), createdAt: clock.iso() }
        run.comments ??= []; run.comments.push(comment)
        return { status: 201, body: comment }
      })
    }),
    http.get(`${B}/workspaces/:ws/runs/:id/events`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      if (!run || run.wsId !== str(params.ws) || !canReadRun(g.user, g.perms, run)) return notFound()
      const url = new URL(request.url)
      const after = Number(url.searchParams.get('after') ?? 0)
      const limit = Math.min(100, Number(url.searchParams.get('limit') ?? 100))
      const fresh = run.events.filter((e) => e.seq > after)
      const items = fresh.slice(0, limit).map((e) => ({
        seq: e.seq,
        type: e.type,
        nodeId: e.nodeId,
        nodeLabel: e.nodeId ? (nodeById(currentRelease(run).definition, e.nodeId)?.label ?? null) : null,
        actor: e.actorId ? subjectRef(userById(e.actorId)) : null,
        occurredAt: e.occurredAt,
        data: e.data,
      }))
      return HttpResponse.json({ items, nextAfter: items.at(-1)?.seq ?? after, hasMore: fresh.length > limit, lifecycle: run.lifecycle })
    }),
    http.post(`${B}/workspaces/:ws/runs/:id/cancel`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'run.operate')
      if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      if (!run || run.wsId !== str(params.ws)) return notFound()
      const input = await body<{ reason: string; expectedVersion: number }>(request)
      return idempotent(request, g.user, 'cancelRun', input, () => {
        if (!input.reason?.trim()) return problem(422, 'REASON_REQUIRED', 'A reason is required', { fieldErrors: [{ pointer: '/reason', code: 'required', message: 'Add a reason.' }] })
        if (run.lifecycle === 'completed' || run.lifecycle === 'cancelled') return problem(409, 'RUN_NOT_ACTIVE', 'The request already finished')
        if (input.expectedVersion !== run.version) return problem(409, 'RUN_NOT_ACTIVE', 'The request changed', { currentVersion: run.version })
        cancelRun(run, g.user, input.reason.trim())
        return { status: 200, body: toRunDetail(run, g.user) }
      })
    }),
    http.post(`${B}/workspaces/:ws/runs/:id/retry`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'run.operate')
      if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      if (!run || run.wsId !== str(params.ws)) return notFound()
      const input = await body<{ reason: string; expectedVersion: number; effectId?: string }>(request)
      return idempotent(request, g.user, 'retryRun', input, () => {
        if (!input.reason?.trim()) return problem(422, 'REASON_REQUIRED', 'A reason is required', { fieldErrors: [{ pointer: '/reason', code: 'required', message: 'Add a reason.' }] })
        if (run.lifecycle !== 'needsAttention') return problem(409, 'RUN_NOT_RECOVERABLE', 'The request is not waiting on a failed step')
        if (run.effects.some((e) => e.state === 'unknown')) return problem(409, 'EFFECT_NOT_RECONCILED', 'The outcome of the step is unknown. Reconcile it first.')
        if (input.expectedVersion !== run.version) return problem(409, 'RUN_BUSY', 'The request changed', { currentVersion: run.version })
        retryRun(run, g.user, input.reason.trim())
        return { status: 200, body: toRunDetail(run, g.user) }
      })
    }),
    http.post(`${B}/workspaces/:ws/runs/:id/effects/:eid/reconcile`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'run.operate')
      if (!g.ok) return g.res
      const run = db.runs.get(str(params.id))
      const effect = run?.effects.find((e) => e.id === str(params.eid))
      if (!run || run.wsId !== str(params.ws) || !effect) return notFound()
      const input = await body<{ resolution: 'confirmedApplied' | 'confirmedNotApplied'; evidence: { note: string; remoteReference?: string }; result?: Record<string, string | number | boolean> }>(request)
      return idempotent(request, g.user, 'reconcileEffect', input, () => {
        if (!(effect.state === 'unknown' || effect.state === 'permanentFailure' || effect.state === 'retryableFailure')) return problem(409, 'EFFECT_STATE_CONFLICT', 'The step does not need reconciling')
        if (!input.evidence?.note || input.evidence.note.trim().length < 10) {
          return problem(422, 'VALIDATION_FAILED', 'Evidence is required', { fieldErrors: [{ pointer: '/evidence/note', code: 'minLength', message: 'Describe what you checked, in at least 10 characters.' }] })
        }
        if (input.resolution === 'confirmedApplied') {
          const node = currentRelease(run).definition.nodes.find((n) => n.id === effect.nodeId)
          const keys = Object.keys((node?.config.resultMapping ?? {}) as Record<string, string>).sort()
          const given = Object.keys(input.result ?? {}).sort()
          if (keys.join(',') !== given.join(',')) return problem(422, 'VALIDATION_FAILED', 'Result fields do not match', { fieldErrors: [{ pointer: '/result', code: 'keys', message: `Provide exactly these fields: ${keys.join(', ')}.` }] })
        }
        reconcileRun(run, effect, g.user, input.resolution, input.evidence, input.result)
        const { runId: _runId, ...plain } = effect
        return { status: 200, body: plain }
      })
    }),
    http.get(`${B}/workspaces/:ws/me/requests`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      const url = new URL(request.url)
      let runs = [...db.runs.values()].filter((r) => r.wsId === str(params.ws) && r.initiatorId === g.user.id)
      const lifecycle = url.searchParams.get('lifecycle')
      if (lifecycle) runs = runs.filter((r) => r.lifecycle === lifecycle)
      runs.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      const page = paginate(runs, url)
      return HttpResponse.json({ items: page.items.map(toRun), nextCursor: page.nextCursor })
    }),

    // Notifications ------------------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/me/notifications`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      const url = new URL(request.url)
      let items = db.notifications.filter((n) => n.wsId === str(params.ws) && n.userId === g.user.id)
      const unreadCount = items.filter((n) => !n.readAt).length
      if (url.searchParams.get('unread') === 'true') items = items.filter((n) => !n.readAt)
      const page = paginate(items, url)
      return HttpResponse.json({ items: page.items.map(({ wsId: _w, userId: _u, ...n }) => ({ ...n, runReference: (n.runId ? db.runs.get(n.runId)?.reference : undefined) ?? null })), nextCursor: page.nextCursor, unreadCount })
    }),
    http.post(`${B}/workspaces/:ws/notifications/:id/read`, async ({ params }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      const n = db.notifications.find((x) => x.id === str(params.id) && x.userId === g.user.id && x.wsId === str(params.ws))
      if (!n) return notFound()
      n.readAt ??= clock.iso()
      return new HttpResponse(null, { status: 204 })
    }),

    // Tasks ---------------------------------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/tasks`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'task.act')
      if (!g.ok) return g.res
      const url = new URL(request.url)
      const view = url.searchParams.get('view')
      if (view !== 'assigned' && view !== 'claimable' && view !== 'completed') return problem(400, 'MALFORMED_REQUEST', 'view is required')
      const all = [...db.runs.values()].filter((r) => r.wsId === str(params.ws)).flatMap((run) => run.tasks.map((task) => ({ run, task })))
      const claimable = all.filter(({ run, task }) => task.status === 'open' && !task.assigneeId && inGroup(g.user, task.candidateGroup) && (run.initiatorId !== g.user.id || selfApprovalAllowed(run, currentRelease(run))))
      const assigned = all.filter(({ task }) => task.status === 'open' && task.assigneeId === g.user.id)
      const completed = all.filter(({ task }) => task.status === 'completed' && task.completedById === g.user.id)
      const chosen = view === 'assigned' ? assigned : view === 'claimable' ? claimable : completed
      chosen.sort((a, b) => (view === 'completed' ? (b.task.decision?.decidedAt ?? '').localeCompare(a.task.decision?.decidedAt ?? '') : a.task.createdAt.localeCompare(b.task.createdAt)))
      const page = paginate(chosen, url)
      return HttpResponse.json({ items: page.items.map(({ run, task }) => toTaskSummary(run, task)), nextCursor: page.nextCursor, counts: { assigned: assigned.length, claimable: claimable.length } })
    }),
    http.get(`${B}/workspaces/:ws/tasks/:id`, async ({ params }) => {
      await slow()
      const g = guard(params.ws)
      if (!g.ok) return g.res
      const found = findTask(str(params.ws), str(params.id))
      if (!found || !(g.perms.includes('run.read') || (g.perms.includes('task.act') && isOpenTaskVisible(g.user, found.task, found.run)))) return notFound()
      return HttpResponse.json(taskDetail(g.user, g.perms, found.run, found.task))
    }),
    http.post(`${B}/workspaces/:ws/tasks/:id/claim`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'task.act')
      if (!g.ok) return g.res
      const found = findTask(str(params.ws), str(params.id))
      if (!found) return notFound()
      const { run, task } = found
      const input = await body<{ expectedVersion: number }>(request)
      if (!inGroup(g.user, task.candidateGroup)) return problem(403, 'NOT_ELIGIBLE', 'Not eligible')
      if (run.initiatorId === g.user.id && !selfApprovalAllowed(run, currentRelease(run))) return problem(403, 'SELF_APPROVAL_DENIED', 'You cannot act on your own request')
      if (task.status !== 'open') return problem(409, 'TASK_NOT_OPEN', 'The task is no longer open', { currentVersion: task.version })
      if (task.assigneeId) return problem(409, 'TASK_ALREADY_CLAIMED', 'The task is already claimed', { currentVersion: task.version })
      if (input.expectedVersion !== task.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The task changed', { currentVersion: task.version })
      claim(run, task, g.user)
      return HttpResponse.json(taskDetail(g.user, g.perms, run, task))
    }),
    http.post(`${B}/workspaces/:ws/tasks/:id/release`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'task.act')
      if (!g.ok) return g.res
      const found = findTask(str(params.ws), str(params.id))
      if (!found) return notFound()
      const { run, task } = found
      const input = await body<{ expectedVersion: number }>(request)
      if (task.status !== 'open') return problem(409, 'TASK_NOT_OPEN', 'The task is no longer open')
      if (task.assigneeId !== g.user.id) return problem(409, 'TASK_NOT_CLAIMED_BY_CALLER', 'Not your task')
      if (input.expectedVersion !== task.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The task changed', { currentVersion: task.version })
      releaseTask(run, task, g.user)
      return HttpResponse.json(taskDetail(g.user, g.perms, run, task))
    }),
    http.post(`${B}/workspaces/:ws/tasks/:id/decision`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'task.act')
      if (!g.ok) return g.res
      const found = findTask(str(params.ws), str(params.id))
      if (!found) return notFound()
      const { run, task } = found
      const input = await body<{ expectedVersion: number; action: 'approve' | 'reject'; reason?: string; comment?: string }>(request)
      return idempotent(request, g.user, 'decideTask', input, () => {
        if (run.initiatorId === g.user.id && !selfApprovalAllowed(run, currentRelease(run))) return problem(403, 'SELF_APPROVAL_DENIED', 'You cannot act on your own request')
        if (task.status !== 'open') return problem(409, 'TASK_NOT_OPEN', 'The task is no longer open', { currentVersion: task.version })
        if (input.expectedVersion !== task.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The task changed', { currentVersion: task.version })
        if (task.assigneeId !== g.user.id) return problem(409, 'TASK_NOT_CLAIMED_BY_CALLER', 'Claim the task first')
        if (input.action === 'reject' && !input.reason?.trim()) return problem(422, 'REASON_REQUIRED', 'A reason is required to reject', { fieldErrors: [{ pointer: '/reason', code: 'required', message: 'Add a reason so the requester knows what to change.' }] })
        const decision = decide(run, task, g.user, input.action, input.action === 'reject' ? (input.reason?.trim() ?? null) : null, input.comment?.trim() || null)
        return { status: 201, body: decision }
      })
    }),
    http.post(`${B}/workspaces/:ws/tasks/:id/reassign`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'run.operate')
      if (!g.ok) return g.res
      const found = findTask(str(params.ws), str(params.id))
      if (!found) return notFound()
      const { run, task } = found
      const input = await body<{ expectedVersion: number; assigneeSubjectId?: string; reason: string }>(request)
      if (!input.reason?.trim()) return problem(422, 'REASON_REQUIRED', 'A reason is required', { fieldErrors: [{ pointer: '/reason', code: 'required', message: 'Add a reason.' }] })
      if (task.status !== 'open') return problem(409, 'TASK_NOT_OPEN', 'The task is no longer open')
      if (input.expectedVersion !== task.version) return problem(409, 'TASK_VERSION_CONFLICT', 'The task changed', { currentVersion: task.version })
      const target = input.assigneeSubjectId ? userById(input.assigneeSubjectId) : undefined
      if (input.assigneeSubjectId && (!target || !inGroup(target, task.candidateGroup))) return problem(422, 'VALIDATION_FAILED', 'Not eligible', { fieldErrors: [{ pointer: '/assigneeSubjectId', code: 'eligible', message: 'This person is not in the approver group.' }] })
      task.assigneeId = target?.id ?? null
      task.version += 1
      run.version += 1
      addEvent(run, 'TASK_REASSIGNED', { nodeId: task.nodeId, actorId: g.user.id, data: { label: task.label, to: target?.displayName ?? 'the group', reason: input.reason.trim() } })
      return HttpResponse.json(taskDetail(g.user, g.perms, run, task))
    }),

    // Connections ------------------------------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/connections`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'connection.read')
      if (!g.ok) return g.res
      if (str(params.ws) === WORKSPACES[2]?.id) return HttpResponse.json({ items: [] })
      return HttpResponse.json({ items: Object.keys(CONNECTIONS).map((key) => connectionSummary(str(params.ws), key)) })
    }),
    http.get(`${B}/workspaces/:ws/connections/:key`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'connection.read')
      if (!g.ok) return g.res
      if (!(str(params.key) in CONNECTIONS) || str(params.ws) === WORKSPACES[2]?.id) return notFound()
      const binding = db.bindings.get(`${str(params.ws)}:${str(params.key)}`)
      return HttpResponse.json({
        key: str(params.key),
        definitions: [{ version: 1, definition: CONNECTIONS[str(params.key)], definitionHash: 'a'.repeat(64), createdAt: '2026-10-01T09:00:00Z', createdBy: subjectRef(userByName('wade.admin')) }],
        binding: g.perms.includes('connection.manage') && binding ? { ...binding, updatedBy: subjectRef(userById(binding.updatedById)) } : null,
      })
    }),
    http.put(`${B}/workspaces/:ws/connections/:key/binding`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'connection.manage')
      if (!g.ok) return g.res
      if (!(str(params.key) in CONNECTIONS)) return notFound()
      const bindingKey = `${str(params.ws)}:${str(params.key)}`
      if (str(params.key) === 'vendor-risk-assistant' && (await peekAuthType(request)) === 'apiKeyHeader') {
        return problem(422, 'VALIDATION_FAILED', 'The credential reference is incomplete', { fieldErrors: [{ pointer: '/auth/type', code: 'enum', message: 'An agent connection supports the auth types none and bearerToken' }] })
      }
      const existing = db.bindings.get(bindingKey)
      const ifMatch = request.headers.get('If-Match')
      if (existing) {
        if (!ifMatch) return problem(428, 'PRECONDITION_REQUIRED', 'If-Match header required')
        if (ifMatch !== `"r${existing.revision}"`) return problem(412, 'STALE_REVISION', 'The binding changed', { currentRevision: existing.revision })
      }
      const input = await body<{ baseUrl: string; auth: { type: 'none' | 'bearerToken' | 'apiKeyHeader'; headerName?: string; secretRef?: string }; enabled: boolean }>(request)
      let host = ''
      try {
        const u = new URL(input.baseUrl)
        host = u.host
        if (u.username || u.password) throw new Error('userinfo')
      } catch {
        return problem(422, 'VALIDATION_FAILED', 'Invalid address', { fieldErrors: [{ pointer: '/baseUrl', code: 'format', message: 'Enter a full address such as https://procurement.example.internal.' }] })
      }
      const allowed = /(^|\.)example\.(internal|com)(:\d+)?$/.test(host)
      if (!allowed) return problem(422, 'EGRESS_NOT_ALLOWED', 'Address not allowed', { fieldErrors: [{ pointer: '/baseUrl', code: 'egress', message: 'This address is not on the network allowlist.' }] })
      const next = { connectionKey: str(params.key), baseUrl: input.baseUrl, auth: input.auth, enabled: input.enabled, revision: (existing?.revision ?? 0) + 1, updatedAt: clock.iso(), updatedById: g.user.id }
      db.bindings.set(bindingKey, next)
      return HttpResponse.json({ ...next, updatedBy: subjectRef(g.user) }, { headers: { ETag: `"r${next.revision}"` } })
    }),
    http.post(`${B}/workspaces/:ws/connections/:key/test`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'connection.manage')
      if (!g.ok) return g.res
      const input = await body<{ version: number; operation: string; input?: Record<string, unknown> }>(request)
      const definition = CONNECTIONS[str(params.key)]
      if (!definition) return notFound()
      const ops = definition.operations as Record<string, { testable: boolean }>
      const op = ops[input.operation]
      if (!op) return notFound()
      if (!op.testable) return problem(422, 'OPERATION_NOT_TESTABLE', 'Operation not testable')
      const binding = db.bindings.get(`${str(params.ws)}:${str(params.key)}`)
      if (!binding?.enabled) return problem(422, 'CONNECTION_NOT_BOUND', 'No enabled binding')
      if (str(params.key) === 'vendor-risk-assistant') return agentTest(input.input)
      if (db.procurementFault === 'down') return HttpResponse.json({ outcome: 'failed', httpStatus: null, durationMs: 3012, errorCode: 'CONNECT_FAILED', detail: 'The service did not answer in time.' })
      return HttpResponse.json({ outcome: 'succeeded', httpStatus: 200, durationMs: 41, errorCode: null, detail: null })
    }),

    // Access -------------------------------------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/access`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workspace.manage-access')
      if (!g.ok) return g.res
      const ws = str(params.ws)
      const roleBindings = allMemberships()
        .filter((m) => m.wsId === ws)
        .flatMap((m) =>
          m.roles
            .filter((r) => r !== 'REQUESTER' && r !== 'INTEGRATION_ADMIN')
            .map((role) => {
              const u = userByName(m.username)
              return { id: newId(), role, principal: { type: 'subject', value: u.id, displayName: u.displayName }, source: 'seed', createdAt: '2026-10-01T09:00:00Z' }
            }),
        )
      const grants = allMemberships()
        .filter((m) => m.wsId === ws)
        .flatMap((m) =>
          m.roles
            .filter((r) => r === 'REQUESTER' || r === 'INTEGRATION_ADMIN')
            .map((r) => {
              const u = userByName(m.username)
              return { id: newId(), permission: r === 'REQUESTER' ? 'workflow.start' : 'connection.manage', workflowId: null, principal: { type: 'subject', value: u.id, displayName: u.displayName }, createdAt: '2026-10-01T09:00:00Z' }
            }),
        )
      const groups = ws === WORKSPACES[2]?.id ? [] : GROUPS.map((gr) => ({ ...gr, members: groupMembers(gr.key).map((u) => ({ type: 'subject', value: u.id, displayName: u.displayName })) }))
      return HttpResponse.json({ roleBindings, grants, groups })
    }),
    http.get(`${B}/workspaces/:ws/groups`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read', 'workspace.manage-access')
      if (!g.ok) return g.res
      if (str(params.ws) === WORKSPACES[2]?.id) return HttpResponse.json({ items: [] })
      const withMembers = g.perms.includes('workspace.manage-access') || g.perms.includes('run.operate')
      return HttpResponse.json({
        items: GROUPS.map((gr) => ({ ...gr, ...(withMembers ? { members: groupMembers(gr.key).map((u) => ({ type: 'subject', value: u.id, displayName: u.displayName })) } : {}) })),
      })
    }),

    // Logout (navigation POST) ---------------------------------------------------------------------------------------------------------------------
    http.post('*/logout', () => HttpResponse.redirect('/', 303)),
  ]
}

export { USERS, rolesOf }

// Connections shared by the list, detail, binding and test handlers ----------------------------------------------------------------------
type ConnectionDoc = { key: string; name: string; kind?: string; operations: Record<string, { label: string; effectClass: string; testable: boolean }> }
const CONNECTIONS: Record<string, ConnectionDoc> = {
  procurement: connectionDefinition as unknown as ConnectionDoc,
  'vendor-risk-assistant': agentConnectionDefinition as unknown as ConnectionDoc,
}

function connectionSummary(ws: string, key: string) {
  const definition = CONNECTIONS[key]!
  const binding = db.bindings.get(`${ws}:${key}`)
  return {
    key,
    name: definition.name,
    kind: definition.kind ?? 'http',
    latestVersion: 1,
    versions: [1],
    binding: { configured: !!binding, enabled: !!binding?.enabled, host: binding ? new URL(binding.baseUrl).host : null },
    operations: Object.entries(definition.operations).map(([name, op]) => ({ name, label: op.label, effectClass: op.effectClass, testable: op.testable })),
  }
}

async function peekAuthType(request: Request): Promise<string | undefined> {
  try {
    const parsed = (await request.clone().json()) as { auth?: { type?: string } }
    return parsed.auth?.type
  } catch {
    return undefined
  }
}

/** Deterministic stand-in for the demo vendor registry (same fixtures as tools/procurement-stub). */
const VENDOR_FIXTURES: Record<string, { riskScore: number; sanctionsHit: boolean }> = {
  'acme ltd': { riskScore: 72, sanctionsHit: false },
  'globex corporation': { riskScore: 18, sanctionsHit: false },
  initech: { riskScore: 45, sanctionsHit: false },
  'umbrella trading': { riskScore: 91, sanctionsHit: true },
}

function agentTest(sample: Record<string, unknown> | undefined) {
  const vendorName = typeof sample?.vendorName === 'string' ? sample.vendorName.trim() : ''
  if (!vendorName || typeof sample?.country !== 'string' || typeof sample?.annualSpend !== 'number') {
    return problem(422, 'VALIDATION_FAILED', 'The sample input does not match the operation.', { fieldErrors: [{ pointer: '/input', code: 'required', message: 'Provide vendorName, country and annualSpend' }] })
  }
  const fixture = VENDOR_FIXTURES[vendorName.toLowerCase()] ?? { riskScore: 33, sanctionsHit: false }
  const riskBand = fixture.riskScore >= 70 ? 'high' : fixture.riskScore >= 40 ? 'medium' : 'low'
  return HttpResponse.json({
    outcome: 'succeeded',
    httpStatus: null,
    durationMs: 4210,
    errorCode: null,
    detail: 'model deepseek/deepseek-v3.2; 2 turns; tools vendor_risk_lookup ok 38 ms; tokens 1850/96; USD 0.0006',
    output: { ...fixture, riskBand, registryId: 'VR-3FA9C2' },
    summary: `Vendor risk assistant checked '${vendorName}' using Vendor registry: risk ${fixture.riskScore} (${riskBand})`,
  })
}
