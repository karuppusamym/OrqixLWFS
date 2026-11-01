import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { clock, GROUPS, inGroup, isMember, newId, permissionsOf, WS_PURCHASING, type MockUser } from './state'
import { caseDefinitionWarnings, caseFieldProblem, caseFormSchema } from '@/features/cases/caseFields'
import { validateJsonInput } from '@/lib/form/validate'

type Definition = components['schemas']['CaseDefinition']
type Instance = components['schemas']['CaseInstance']
type Values = components['schemas']['CaseDataValues']
type Row = { value: Instance; startedBy: string; step: number; events?: components['schemas']['CaseEvent'][]; planned?: boolean; data?: Values; changes?: components['schemas']['CaseDataChange'][] }
type Stored = Definition & { ws: string; sourceXml?: string; workflowTasks?: components['schemas']['CaseWorkflowPin'][] }
type Draft = components['schemas']['CaseDefinitionDraft'] & { ws: string }
type Issue = components['schemas']['CaseDefinitionCheck']['issues'][number]

const B = '/api/v1'
const DEMO_DEFINITION: Definition = { id: '77777777-7777-4777-8777-777777777701', key: 'evidence-review', name: 'Evidence review', version: 1, createdAt: '2026-10-17T12:00:00Z' }
const DEMO_INSTANCE = '77777777-7777-4777-8777-777777777702'
let definitions: Stored[] = []
let instances: Row[] = []
let drafts: Draft[] = []
const replays = new Map<string, Instance>()
const terminations = new Map<string, { request: string; value: Instance }>()
const lifecycleCommands = new Map<string, { request: string; value: Instance }>()

const task = (step: number): Instance['tasks'][number] => step === 0
  ? { id: 'demo-collect', name: 'Collect evidence', status: 'available', candidateGroups: ['managers'], eligible: false }
  : { id: 'demo-review', name: 'Review evidence', status: 'available', candidateGroups: ['finance-approvers'], eligible: false }

export function resetMockCases(): void {
  definitions = [{ ...DEMO_DEFINITION, ws: WS_PURCHASING }]
  drafts = []
  instances = [{ startedBy: 'aaaaaaaa-0000-4000-8000-000000000007', step: 0,
    value: { id: DEMO_INSTANCE, definitionId: DEMO_DEFINITION.id, definitionVersion: 1, status: 'active', version: 1,
      tasks: [task(0)], createdAt: clock.iso() } }]
  replays.clear()
  terminations.clear()
  lifecycleCommands.clear()
}

export function snapshotMockCases(): string { return JSON.stringify({ definitions, instances, drafts }) }
export function restoreMockCases(raw: string | null): boolean {
  if (!raw) return false
  try {
    const value = JSON.parse(raw) as { definitions: typeof definitions; instances: Row[]; drafts?: Draft[] }
    if (!Array.isArray(value.definitions) || !Array.isArray(value.instances)) return false
    definitions = value.definitions; instances = value.instances; drafts = Array.isArray(value.drafts) ? value.drafts : []; replays.clear(); return true
  } catch { return false }
}

/** Definition as the API returns it: warnings (S125) only when there are some. */
function withWarnings<T extends { dataFields?: Definition['dataFields'] }>(value: T): T & { warnings?: Definition['warnings'] } {
  const warnings = caseDefinitionWarnings(value.dataFields ?? [])
  return warnings.length ? { ...value, warnings } : value
}

/** The server's case data check (CaseDataModel.values): the same keywords as the pinned fields. */
function dataProblem(def: Stored, proposed: unknown): string | undefined {
  if (typeof proposed !== 'object' || proposed === null || Array.isArray(proposed) || JSON.stringify(proposed).length > 32768) return 'Case data must be an object of at most 32 KiB.'
  const { schema } = caseFormSchema(def.dataFields ?? [])
  const issues = validateJsonInput(schema, proposed)
  return issues.length ? `Case data does not match the pinned fields: ${issues.slice(0, 10).map(issue => `${issue.pointer} (${issue.keyword})`).join(', ')}` : undefined
}

const failure = (status: number, code: string, detail: string) => HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase()}`, title: detail,
  status, code, correlationId: newId(), detail }, { status, headers: { 'Content-Type': 'application/problem+json' } })

function visible(row: Row, user: MockUser, ws: string): boolean {
  const perms = permissionsOf(user, ws)
  return perms.includes('run.read') || row.startedBy === user.id || row.value.tasks.some(item => item.candidateGroups.some(group => inGroup(user, group)))
}

/** As CaseService.canEditData: an eligible worker on an open task of an active case, or an operator who can read data. */
function canEditData(row: Row, user: MockUser, ws: string): boolean {
  const perms = permissionsOf(user, ws)
  if (row.value.status !== 'active') return false
  if (perms.includes('run.operate') && perms.includes('run.read-data')) return true
  return perms.includes('task.act') && row.value.tasks.some(item => item.candidateGroups.some(group => inGroup(user, group)))
}

function project(row: Row, user: MockUser): Instance {
  return { ...row.value, tasks: row.value.tasks.map(item => ({ ...item, eligible: row.value.status === 'active' && item.candidateGroups.some(group => inGroup(user, group)) })) }
}


const DATA_FIELD_TYPES = ['text', 'number', 'integer', 'boolean', 'date', 'choice']
const NO_MODEL = 'The case type has no CMMN model yet. Draw the case plan or upload a CMMN file.'

/**
 * Mock mode keeps a structural check only (S126); executable admission is the real backend validator's job. Like the
 * server, independent rules all run so every problem is reported at once.
 */
function checkCase(body: components['schemas']['CreateCaseDefinitionDraft']): Issue[] {
  const issues: Issue[] = []
  const invalid = (message: string) => issues.push({ code: 'VALIDATION_FAILED', message })
  const keyOk = /^[a-z][a-z0-9-]{1,62}$/.test(body.key ?? '')
  if (!keyOk || !body.name?.trim()) invalid('Case key or name is invalid.')
  const fields = (body.dataFields ?? []) as Array<{ key?: unknown; label?: unknown; type?: unknown; options?: unknown }>
  const keys = new Set<string>()
  const fieldsOk = fields.length <= 30 && fields.every(field => typeof field.key === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(field.key) && !keys.has(field.key) && keys.add(field.key)
    && typeof field.label === 'string' && !!field.label.trim() && DATA_FIELD_TYPES.includes(String(field.type))
    && (field.type !== 'choice' || Array.isArray(field.options) && field.options.length > 0 && field.options.every(option => typeof option === 'string' && !!option.trim())))
  if (!fieldsOk) invalid('Case data fields need unique keys, labels and supported types.')
  // S125: the same per-field checks as request questions.
  const fieldProblem = fieldsOk ? caseFieldProblem(body.dataFields) : null
  if (fieldProblem) invalid(fieldProblem)
  const xml = body.sourceXml ?? ''
  if (!xml.trim()) { issues.push({ code: 'WORKFLOW_INVALID', message: NO_MODEL }); return issues }
  if (!keyOk) return issues
  if (!xml.includes('http://www.omg.org/spec/CMMN/20151109/MODEL') || !xml.includes(`<case id="${body.key}"`)) {
    issues.push({ code: 'WORKFLOW_INVALID', message: 'Exactly one case with an ID matching the catalog key is required.' }); return issues
  }
  const groups = [...xml.matchAll(/candidateGroups="([^"]*)"/g)].flatMap(match => match[1]!.split(',').map(group => group.trim()).filter(Boolean))
  if (groups.some(group => !GROUPS.some(known => known.key === group))) invalid('Every candidate group must exist in this workspace.')
  const processTasks = [...xml.matchAll(/<processTask id="([^"]+)"/g)].map(match => match[1]!)
  const mapped = new Set((body.workflowTasks ?? []).map(task => task.taskId))
  if (processTasks.length !== mapped.size || processTasks.some(id => !mapped.has(id))) invalid(`Workflow task mappings must match the workflow tasks of the model exactly: [${processTasks.join(', ')}].`)
  return issues
}

function deploy(ws: string, body: components['schemas']['CreateCaseDefinition']): Definition {
  const version = definitions.filter(value => value.ws === ws && value.key === body.key).length + 1
  const stored: Stored = { id: newId(), key: body.key, name: body.name.trim(), version, createdAt: clock.iso(), dataFields: body.dataFields ?? [], ws, sourceXml: body.sourceXml,
    workflowTasks: (body.workflowTasks ?? []).map(task => ({ taskId: task.taskId, workflowId: newId(), workflowKey: /externalRef="orqixwf:([^"]+)"/.exec(body.sourceXml)?.[1] ?? 'workflow', workflowName: 'Pinned workflow', releaseId: newId(), releaseNumber: 1, inputMapping: task.inputMapping ?? {}, resultMapping: task.resultMapping ?? {} })) }
  definitions = [stored, ...definitions]
  const { ws: _ws, sourceXml: _source, workflowTasks: _tasks, ...summary } = stored
  return withWarnings(summary)
}

const publishedVersion = (ws: string, key: string) => definitions.filter(value => value.ws === ws && value.key === key).reduce<number | null>((max, value) => Math.max(max ?? 0, value.version), null)
function projectDraft(draft: Draft): components['schemas']['CaseDefinitionDraft'] {
  const { ws, ...value } = draft
  return { ...value, publishedVersion: publishedVersion(ws, draft.key) }
}
type DraftContent = Omit<components['schemas']['SaveCaseDefinitionDraft'], 'expectedVersion' | 'name'>
const draftContent = (body: DraftContent) => ({
  sourceXml: body.sourceXml?.trim() ? body.sourceXml : null, designerModel: body.designerModel ?? null, dataFields: body.dataFields ?? [], viewRef: body.viewRef ?? null,
  workflowTasks: body.workflowTasks ?? [], caseTasks: body.caseTasks ?? [], decisionTasks: body.decisionTasks ?? [],
})
const contentProblem = (body: DraftContent) => (body.sourceXml && new TextEncoder().encode(body.sourceXml).length > 65536) ? 'The CMMN source must be at most 64 KiB.'
  : (body.dataFields ?? []).length > 30 ? 'A case supports at most 30 data fields.' : null

/** Lightweight walkthrough behavior only; arbitrary CMMN deployment is exercised against the real backend. */
export function createCaseHandlers(getUser: () => MockUser): HttpHandler[] {
  const member = (ws: string, user: MockUser) => isMember(user, ws)
  return [
    http.post(`${B}/workspaces/:ws/cases/instances/:id/terminate`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('run.operate')) return failure(403, 'FORBIDDEN', 'Case termination is not allowed.')
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!row) return failure(404, 'NOT_FOUND', 'Case not found.')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return failure(400, 'MALFORMED_REQUEST', 'Idempotency-Key is required.')
      const body = await request.json() as { expectedVersion: number; reason: string }
      if (!Number.isInteger(body.expectedVersion) || body.expectedVersion < 1 || typeof body.reason !== 'string' || !body.reason.trim() || body.reason.length > 1000) return failure(422, 'VALIDATION_FAILED', 'Supply a current version and reason.')
      const replayKey = `${ws}:${user.id}:${key}`, fingerprint = JSON.stringify({ id: row.value.id, ...body })
      const replay = terminations.get(replayKey)
      if (replay) return replay.request === fingerprint ? HttpResponse.json(replay.value, { headers: { 'Idempotent-Replayed': 'true' } }) : failure(409, 'IDEMPOTENCY_KEY_REUSED', 'The command changed.')
      if (row.value.version !== body.expectedVersion) return failure(409, 'TASK_VERSION_CONFLICT', 'Refresh the case.')
      if (!['active', 'suspended'].includes(row.value.status)) return failure(409, 'TASK_NOT_OPEN', 'The case has ended.')
      row.value = { ...row.value, status: 'terminated', version: row.value.version + 1, tasks: [] }
      const value = project(row, user)
      terminations.set(replayKey, { request: fingerprint, value })
      return HttpResponse.json(value)
    }),
    ...(['suspend', 'resume', 'close'] as const).map(action => http.post(`${B}/workspaces/:ws/cases/instances/:id/${action}`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('run.operate')) return failure(403, 'FORBIDDEN', 'Case operations are not allowed.')
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!row) return failure(404, 'NOT_FOUND', 'Case not found.')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return failure(400, 'MALFORMED_REQUEST', 'Idempotency-Key is required.')
      const body = await request.json() as { expectedVersion: number; reason: string }
      if (!Number.isInteger(body.expectedVersion) || body.expectedVersion < 1 || typeof body.reason !== 'string' || !body.reason.trim() || body.reason.length > 1000) return failure(422, 'VALIDATION_FAILED', 'Supply a current version and reason.')
      const replayKey = `${ws}:${user.id}:${action}:${key}`, fingerprint = JSON.stringify({ id: row.value.id, ...body })
      const replay = lifecycleCommands.get(replayKey)
      if (replay) return replay.request === fingerprint ? HttpResponse.json(replay.value, { headers: { 'Idempotent-Replayed': 'true' } }) : failure(409, 'IDEMPOTENCY_KEY_REUSED', 'The command changed.')
      if (row.value.version !== body.expectedVersion) return failure(409, 'TASK_VERSION_CONFLICT', 'Refresh the case.')
      if (row.value.status !== (action === 'resume' ? 'suspended' : 'active')) return failure(409, row.value.status === 'suspended' ? 'RUN_SUSPENDED' : 'TASK_NOT_OPEN', 'That command is not available now.')
      if (action === 'close' && row.value.tasks.length) return failure(422, 'VALIDATION_FAILED', 'Required or active work still prevents closing this case.')
      const before = row.value.status
      row.value = { ...row.value, status: action === 'suspend' ? 'suspended' : action === 'resume' ? 'active' : 'completed', version: row.value.version + 1 }
      row.events = [{ version: row.value.version, action, planItemId: null, fromStatus: before, toStatus: row.value.status, actorId: user.id, actorName: user.displayName, reason: body.reason.trim(), occurredAt: clock.iso() }, ...(row.events ?? [])]
      const result = project(row, user)
      lifecycleCommands.set(replayKey, { request: fingerprint, value: result })
      return HttpResponse.json(result)
    })),
    http.get(`${B}/workspaces/:ws/cases/definitions`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('workflow.read') && !perms.includes('workflow.start')) return failure(403, 'FORBIDDEN', 'Case definitions are not visible.')
      return HttpResponse.json(definitions.filter(value => value.ws === ws).map(({ ws: _ws, sourceXml: _source, workflowTasks: _tasks, ...value }) => withWarnings(value)))
    }),
    http.post(`${B}/workspaces/:ws/cases/definitions`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser()
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case definition editing is not allowed.')
      // Mock mode keeps a structural check only; executable admission is the real backend validator's job.
      const body = await request.json() as components['schemas']['CreateCaseDefinition']
      if (!/^[a-z][a-z0-9-]{1,62}$/.test(body.key ?? '') || !body.name?.trim()) return failure(422, 'VALIDATION_FAILED', 'Case key or name is invalid.')
      if (!body.sourceXml?.includes('http://www.omg.org/spec/CMMN/20151109/MODEL') || !body.sourceXml.includes(`<case id="${body.key}"`)) return failure(422, 'WORKFLOW_INVALID', 'Exactly one case with an ID matching the catalog key is required.')
      const fieldProblem = caseFieldProblem(body.dataFields)
      if (fieldProblem) return failure(422, 'VALIDATION_FAILED', fieldProblem)
      const processTasks = [...body.sourceXml.matchAll(/<processTask id="([^"]+)"/g)].map(match => match[1]!)
      const mapped = new Set((body.workflowTasks ?? []).map(task => task.taskId))
      if (processTasks.length !== mapped.size || processTasks.some(id => !mapped.has(id))) return failure(422, 'VALIDATION_FAILED', `Workflow task mappings must match the workflow tasks of the model exactly: [${processTasks.join(', ')}].`)
      return HttpResponse.json(deploy(ws, body), { status: 201 })
    }),
    // S126: case type drafts, their check and publication.
    http.get(`${B}/workspaces/:ws/cases/drafts`, ({ params }) => {
      const ws = String(params.ws), user = getUser()
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type drafts are not visible.')
      return HttpResponse.json(drafts.filter(draft => draft.ws === ws).map(draft => {
        const { id, key, name, version, updatedAt, publishedDraftVersion } = draft
        return { id, key, name, version, updatedAt, publishedVersion: publishedVersion(ws, key), publishedDraftVersion }
      }))
    }),
    http.post(`${B}/workspaces/:ws/cases/drafts`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser()
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type editing is not allowed.')
      const body = await request.json() as components['schemas']['CreateCaseDefinitionDraft']
      if (!/^[a-z][a-z0-9-]{1,62}$/.test(body.key ?? '')) return failure(422, 'VALIDATION_FAILED', 'Give the case type a key of 2 to 63 lowercase letters, digits or hyphens, starting with a letter.')
      if (!body.name?.trim() || body.name.trim().length > 200) return failure(422, 'VALIDATION_FAILED', 'Give the case type a name of 1 to 200 characters.')
      const problem = contentProblem(body)
      if (problem) return failure(422, 'VALIDATION_FAILED', problem)
      if (drafts.some(draft => draft.ws === ws && draft.key === body.key)) return failure(409, 'DUPLICATE_KEY', `Case type ${body.key} already has a draft. Open that draft instead.`)
      const draft: Draft = { ws, id: newId(), key: body.key, name: body.name.trim(), version: 1, ...draftContent(body), updatedAt: clock.iso(), publishedVersion: null, publishedDraftVersion: null }
      drafts = [draft, ...drafts]
      return HttpResponse.json(projectDraft(draft), { status: 201 })
    }),
    http.get(`${B}/workspaces/:ws/cases/drafts/:draftId`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), draft = drafts.find(value => value.ws === ws && value.id === String(params.draftId))
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type drafts are not visible.')
      if (!draft) return failure(404, 'NOT_FOUND', 'Case type draft not found.')
      return HttpResponse.json(projectDraft(draft))
    }),
    http.put(`${B}/workspaces/:ws/cases/drafts/:draftId`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), draft = drafts.find(value => value.ws === ws && value.id === String(params.draftId))
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type editing is not allowed.')
      if (!draft) return failure(404, 'NOT_FOUND', 'Case type draft not found.')
      const body = await request.json() as components['schemas']['SaveCaseDefinitionDraft']
      if (!body.name?.trim() || body.name.trim().length > 200) return failure(422, 'VALIDATION_FAILED', 'Give the case type a name of 1 to 200 characters.')
      const problem = contentProblem(body)
      if (problem) return failure(422, 'VALIDATION_FAILED', problem)
      if (body.expectedVersion !== draft.version) return failure(409, 'WORKFLOW_VERSION_CONFLICT', 'The case type draft changed since you loaded it. Reload it and try again.')
      Object.assign(draft, { name: body.name.trim(), version: draft.version + 1, ...draftContent(body), updatedAt: clock.iso() })
      return HttpResponse.json(projectDraft(draft))
    }),
    http.delete(`${B}/workspaces/:ws/cases/drafts/:draftId`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), draft = drafts.find(value => value.ws === ws && value.id === String(params.draftId))
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type editing is not allowed.')
      if (!draft) return failure(404, 'NOT_FOUND', 'Case type draft not found.')
      drafts = drafts.filter(value => value !== draft)
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${B}/workspaces/:ws/cases/drafts/:draftId/check`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), draft = drafts.find(value => value.ws === ws && value.id === String(params.draftId))
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type editing is not allowed.')
      if (!draft) return failure(404, 'NOT_FOUND', 'Case type draft not found.')
      const issues = checkCase(draft)
      return HttpResponse.json({ draftVersion: draft.version, valid: issues.length === 0, issues })
    }),
    http.post(`${B}/workspaces/:ws/cases/drafts/:draftId/publish`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), draft = drafts.find(value => value.ws === ws && value.id === String(params.draftId))
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case type editing is not allowed.')
      if (!draft) return failure(404, 'NOT_FOUND', 'Case type draft not found.')
      const body = await request.json() as components['schemas']['PublishCaseDefinitionDraft']
      if (body.expectedVersion !== draft.version) return failure(409, 'WORKFLOW_VERSION_CONFLICT', 'The case type draft changed since you loaded it. Reload it and try again.')
      const issues = checkCase(draft)
      if (issues.length) return failure(422, 'WORKFLOW_INVALID', `The check found ${issues.length} ${issues.length === 1 ? 'problem' : 'problems'}. Fix ${issues.length === 1 ? 'it' : 'them'} before publishing. First: ${issues[0]!.message}`)
      const definition = deploy(ws, { key: draft.key, name: draft.name, sourceXml: draft.sourceXml!, dataFields: draft.dataFields as components['schemas']['CaseDataField'][],
        ...(draft.viewRef ? { viewRef: draft.viewRef } : {}), workflowTasks: draft.workflowTasks, caseTasks: draft.caseTasks, decisionTasks: draft.decisionTasks })
      draft.publishedDraftVersion = draft.version
      return HttpResponse.json(definition, { status: 201 })
    }),
    http.get(`${B}/workspaces/:ws/cases/definitions/:definitionId`, ({ params }) => {
      const ws = String(params.ws), user = getUser()
      const stored = definitions.find(value => value.ws === ws && value.id === String(params.definitionId))
      if (!member(ws, user) || !stored) return failure(404, 'NOT_FOUND', 'Case definition not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case definition source is not visible.')
      const { ws: _ws, ...detail } = stored
      return HttpResponse.json({ ...withWarnings(detail), sourceXml: stored.sourceXml ?? `<?xml version="1.0" encoding="UTF-8"?>\n<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="urn:orqix:mock"><case id="${stored.key}" name="${stored.name}"/></definitions>`, workflowTasks: stored.workflowTasks ?? [] })
    }),
    http.post(`${B}/workspaces/:ws/cases/instances/:id/plan-items/:itemId/plan`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser()
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!member(ws, user) || !row) return failure(404, 'NOT_FOUND', 'Case not found.')
      if (!permissionsOf(user, ws).includes('task.act')) return failure(403, 'FORBIDDEN', 'Planning is not allowed.')
      if (String(params.itemId) !== `${row.value.id}-expert` || row.planned) return failure(404, 'NOT_FOUND', 'Plan item not found.')
      if (!inGroup(user, 'managers')) return failure(403, 'NOT_ELIGIBLE', 'Only managers may plan this item.')
      const body = await request.json() as { expectedVersion: number; reason: string }
      if (!body.reason?.trim()) return failure(422, 'VALIDATION_FAILED', 'Supply a current version and reason.')
      if (row.value.version !== body.expectedVersion) return failure(409, 'TASK_VERSION_CONFLICT', 'Refresh the case.')
      if (row.value.status !== 'active') return failure(409, row.value.status === 'suspended' ? 'RUN_SUSPENDED' : 'TASK_NOT_OPEN', 'That command is not available now.')
      row.planned = true
      row.value = { ...row.value, version: row.value.version + 1, tasks: [...row.value.tasks, { id: 'demo-expert', name: 'Expert opinion', status: 'available', candidateGroups: ['finance-approvers'], eligible: false }] }
      row.events = [{ version: row.value.version, action: 'plan', planItemId: `${row.value.id}-expert`, fromStatus: 'active', toStatus: 'active', actorId: user.id, actorName: user.displayName, reason: body.reason.trim(), occurredAt: clock.iso() }, ...(row.events ?? [])]
      return HttpResponse.json(project(row, user))
    }),
    http.get(`${B}/workspaces/:ws/cases/instances`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!['run.read', 'workflow.start', 'task.act'].some(permission => perms.includes(permission as typeof perms[number]))) return failure(403, 'FORBIDDEN', 'Cases are not visible.')
      return HttpResponse.json(instances.filter(row => definitions.some(def => def.ws === ws && def.id === row.value.definitionId) && visible(row, user, ws)).map(row => project(row, user)))
    }),
    http.get(`${B}/workspaces/:ws/cases/instances/:id/data`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!member(ws, user) || !row || !(perms.includes('run.read-data') || row.startedBy === user.id || row.value.tasks.some(item => item.candidateGroups.some(group => inGroup(user, group))))) return failure(404, 'NOT_FOUND', 'Case data not found.')
      const def = definitions.find(value => value.id === row.value.definitionId)!
      return HttpResponse.json({ fields: def.dataFields ?? [], values: row.data ?? {}, version: row.value.version, canEdit: canEditData(row, user, ws), changes: perms.includes('run.read-data') ? row.changes ?? [] : [] })
    }),
    http.post(`${B}/workspaces/:ws/cases/instances/:id/data`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('task.act') && !(perms.includes('run.operate') && perms.includes('run.read-data'))) return failure(403, 'FORBIDDEN', 'Case data editing is not allowed.')
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!row) return failure(404, 'NOT_FOUND', 'Case not found.')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return failure(400, 'MALFORMED_REQUEST', 'Idempotency-Key is required.')
      const body = await request.json() as { expectedVersion: number; reason: string; values: Values }
      if (!Number.isInteger(body.expectedVersion) || body.expectedVersion < 1 || typeof body.reason !== 'string' || !body.reason.trim() || body.reason.length > 1000) return failure(422, 'VALIDATION_FAILED', 'Supply a current version and reason.')
      const replayKey = `data:${ws}:${user.id}:${key}`, fingerprint = JSON.stringify({ id: row.value.id, ...body })
      const replay = lifecycleCommands.get(replayKey)
      if (replay) return replay.request === fingerprint ? HttpResponse.json(replay.value, { headers: { 'Idempotent-Replayed': 'true' } }) : failure(409, 'IDEMPOTENCY_KEY_REUSED', 'The command changed.')
      const problem = dataProblem(definitions.find(value => value.id === row.value.definitionId)!, body.values)
      if (problem) return failure(422, 'VALIDATION_FAILED', problem)
      if (row.value.version !== body.expectedVersion) return failure(409, 'TASK_VERSION_CONFLICT', 'Refresh the case.')
      if (row.value.status === 'suspended') return failure(409, 'RUN_SUSPENDED', 'The case is on hold.')
      if (!canEditData(row, user, ws)) return failure(403, 'NOT_ELIGIBLE', 'You cannot change this case data now.')
      row.value = { ...row.value, version: row.value.version + 1 }
      row.changes = [{ version: row.value.version, before: row.data ?? {}, after: body.values }, ...(row.changes ?? [])]
      row.data = body.values
      const result = project(row, user)
      lifecycleCommands.set(replayKey, { request: fingerprint, value: result })
      return HttpResponse.json(result)
    }),
    http.get(`${B}/workspaces/:ws/cases/instances/:id/plan`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), row = instances.find(value => value.value.id === String(params.id))
      if (!member(ws, user) || !row || !definitions.some(def => def.ws === ws && def.id === row.value.definitionId) || !visible(row, user, ws)) return failure(404, 'NOT_FOUND', 'Case not found.')
      // Demonstration history only; arbitrary nested-stage execution is covered by the PostgreSQL engine tests.
      const items: components['schemas']['CasePlanItem'][] = [0, 1].filter(step => step <= row.step).map(step => ({
        id: `${row.value.id}-${step}`, elementId: step ? 'review' : 'collect', name: task(step).name, type: 'humantask',
        state: step < row.step ? 'completed' : row.value.status === 'terminated' ? 'terminated' : 'active',
        required: false, allowedActions: {}, parentStageId: null, createdAt: row.value.createdAt, endedAt: step < row.step || row.value.status !== 'active' ? row.value.createdAt : null, workflow: null,
      }))
      // The demo case also shows a discretionary item (planned by managers) and a workflow task with its run.
      const active = row.value.status === 'active'
      items.push({ id: `${row.value.id}-expert`, elementId: 'expert', name: 'Expert opinion', type: 'discretionaryItem', state: row.planned ? 'planned' : active ? 'available' : 'notPlanned',
        required: false, allowedActions: { plan: !row.planned && active && inGroup(user, 'managers') && permissionsOf(user, ws).includes('task.act') }, parentStageId: null, createdAt: row.value.createdAt, endedAt: null, workflow: null })
      if (row.planned) items.push({ id: `${row.value.id}-expert-task`, elementId: 'expert', name: 'Expert opinion', type: 'humantask', state: active ? 'active' : 'terminated', required: true, allowedActions: {}, parentStageId: null, createdAt: row.value.createdAt, endedAt: null, workflow: null })
      items.push({ id: `${row.value.id}-supplier`, elementId: 'supplierCheck', name: 'Supplier check', type: 'processTask', state: row.step > 0 ? 'completed' : active ? 'active' : 'terminated',
        required: true, allowedActions: {}, parentStageId: null, createdAt: row.value.createdAt, endedAt: row.step > 0 ? row.value.createdAt : null,
        workflow: { attempt: 1, status: row.step > 0 ? 'completed' : active ? 'running' : 'cancelled', runId: null, reference: 'ORQ-1042', outcome: row.step > 0 ? 'Approved' : null, detail: null, caseId: null } })
      const milestones = row.value.status === 'completed' ? [{ id: `${row.value.id}-milestone`, elementId: 'done', name: 'Reviewed', achievedAt: row.value.createdAt }] : []
      return HttpResponse.json({ items, milestones, events: (row.events ?? []).map(event => ({ ...event, reason: permissionsOf(user, ws).includes('run.read-data') ? event.reason : null })), canClose: row.value.status === 'active' && !row.value.tasks.length && permissionsOf(user, ws).includes('run.operate') })
    }),
    http.get(`${B}/workspaces/:ws/cases/instances/:id`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), row = instances.find(value => value.value.id === String(params.id))
      if (!member(ws, user) || !row || !definitions.some(def => def.ws === ws && def.id === row.value.definitionId) || !visible(row, user, ws)) return failure(404, 'NOT_FOUND', 'Case not found.')
      return HttpResponse.json(project(row, user))
    }),
    http.post(`${B}/workspaces/:ws/cases/definitions/:definitionId/instances`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('workflow.start')) return failure(403, 'FORBIDDEN', 'Case start is not allowed.')
      const def = definitions.find(value => value.ws === ws && value.id === String(params.definitionId))
      if (!def) return failure(404, 'NOT_FOUND', 'Definition not found.')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return failure(400, 'MALFORMED_REQUEST', 'Idempotency-Key is required.')
      const replay = replays.get(`start:${ws}:${user.id}:${key}`)
      if (replay) return HttpResponse.json(replay, { status: 200, headers: { 'Idempotent-Replayed': 'true' } })
      const start = await request.json().catch(() => null) as { data?: Values } | null
      const problem = dataProblem(def, start?.data ?? {})
      if (problem) return failure(422, 'VALIDATION_FAILED', problem)
      const value: Instance = { id: newId(), definitionId: def.id, definitionVersion: def.version, status: 'active', version: 1,
        tasks: [task(0)], createdAt: clock.iso() }
      instances.unshift({ value, startedBy: user.id, step: 0, data: start?.data ?? {} })
      replays.set(`start:${ws}:${user.id}:${key}`, project(instances[0]!, user))
      return HttpResponse.json(project(instances[0]!, user), { status: 201 })
    }),
    http.post(`${B}/workspaces/:ws/cases/instances/:id/tasks/:taskId/complete`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('task.act')) return failure(403, 'FORBIDDEN', 'Case task action is not allowed.')
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!row) return failure(404, 'NOT_FOUND', 'Case not found.')
      if (row.value.status === 'suspended') return failure(409, 'RUN_SUSPENDED', 'The case is on hold.')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return failure(400, 'MALFORMED_REQUEST', 'Idempotency-Key is required.')
      const replayKey = `complete:${ws}:${user.id}:${key}`
      const replay = replays.get(replayKey)
      if (replay) return HttpResponse.json(replay, { headers: { 'Idempotent-Replayed': 'true' } })
      const body = await request.json() as { expectedVersion?: number }
      if (body.expectedVersion !== row.value.version) return failure(409, 'TASK_VERSION_CONFLICT', 'The case changed. Refresh and try again.')
      const current = row.value.tasks[0]
      if (!current || current.id !== String(params.taskId)) return failure(404, 'NOT_FOUND', 'Task not found.')
      if (!current.candidateGroups.some(group => inGroup(user, group))) return failure(403, 'NOT_ELIGIBLE', 'You are not eligible for this task.')
      row.step += 1
      row.value = { ...row.value, version: row.value.version + 1, status: row.step > 1 ? 'completed' : 'active', tasks: row.step > 1 ? [] : [task(1)] }
      const result = project(row, user)
      replays.set(replayKey, result)
      return HttpResponse.json(result)
    }),
  ]
}
