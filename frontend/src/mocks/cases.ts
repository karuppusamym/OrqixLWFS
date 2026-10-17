import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { clock, inGroup, isMember, newId, permissionsOf, WS_PURCHASING, type MockUser } from './state'

type Definition = components['schemas']['CaseDefinition']
type Instance = components['schemas']['CaseInstance']
type Row = { value: Instance; startedBy: string; step: number }

const B = '/api/v1'
const DEMO_DEFINITION: Definition = { id: '77777777-7777-4777-8777-777777777701', key: 'demoCase', name: 'Evidence review', version: 1, createdAt: '2026-10-17T12:00:00Z' }
const DEMO_INSTANCE = '77777777-7777-4777-8777-777777777702'
let definitions: Array<Definition & { ws: string }> = []
let instances: Row[] = []
const replays = new Map<string, Instance>()

const task = (step: number): Instance['tasks'][number] => step === 0
  ? { id: 'demo-collect', name: 'Collect evidence', status: 'available', candidateGroups: ['managers'], eligible: false }
  : { id: 'demo-review', name: 'Review evidence', status: 'available', candidateGroups: ['finance-approvers'], eligible: false }

export function resetMockCases(): void {
  definitions = [{ ...DEMO_DEFINITION, ws: WS_PURCHASING }]
  instances = [{ startedBy: 'aaaaaaaa-0000-4000-8000-000000000007', step: 0,
    value: { id: DEMO_INSTANCE, definitionId: DEMO_DEFINITION.id, definitionVersion: 1, status: 'active', version: 1,
      tasks: [task(0)], createdAt: clock.iso() } }]
  replays.clear()
}

export function snapshotMockCases(): string { return JSON.stringify({ definitions, instances }) }
export function restoreMockCases(raw: string | null): boolean {
  if (!raw) return false
  try {
    const value = JSON.parse(raw) as { definitions: typeof definitions; instances: Row[] }
    if (!Array.isArray(value.definitions) || !Array.isArray(value.instances)) return false
    definitions = value.definitions; instances = value.instances; replays.clear(); return true
  } catch { return false }
}

const failure = (status: number, code: string, detail: string) => HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase()}`, title: detail,
  status, code, correlationId: newId(), detail }, { status, headers: { 'Content-Type': 'application/problem+json' } })

function visible(row: Row, user: MockUser, ws: string): boolean {
  const perms = permissionsOf(user, ws)
  return perms.includes('run.read') || row.startedBy === user.id || row.value.tasks.some(item => item.candidateGroups.some(group => inGroup(user, group)))
}

function project(row: Row, user: MockUser): Instance {
  return { ...row.value, tasks: row.value.tasks.map(item => ({ ...item, eligible: item.candidateGroups.some(group => inGroup(user, group)) })) }
}

/** Lightweight walkthrough behavior only; arbitrary CMMN deployment is exercised against the real backend. */
export function createCaseHandlers(getUser: () => MockUser): HttpHandler[] {
  const member = (ws: string, user: MockUser) => isMember(user, ws)
  return [
    http.get(`${B}/workspaces/:ws/cases/definitions`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('workflow.read') && !perms.includes('workflow.start')) return failure(403, 'FORBIDDEN', 'Case definitions are not visible.')
      return HttpResponse.json(definitions.filter(value => value.ws === ws).map(({ ws: _ws, ...value }) => value))
    }),
    http.post(`${B}/workspaces/:ws/cases/definitions`, ({ params }) => {
      const ws = String(params.ws), user = getUser()
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!permissionsOf(user, ws).includes('workflow.edit')) return failure(403, 'FORBIDDEN', 'Case definition editing is not allowed.')
      return failure(422, 'WORKFLOW_INVALID', 'CMMN deployment in mock mode needs the real backend validator.')
    }),
    http.get(`${B}/workspaces/:ws/cases/instances`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!['run.read', 'workflow.start', 'task.act'].some(permission => perms.includes(permission as typeof perms[number]))) return failure(403, 'FORBIDDEN', 'Cases are not visible.')
      return HttpResponse.json(instances.filter(row => definitions.some(def => def.ws === ws && def.id === row.value.definitionId) && visible(row, user, ws)).map(row => project(row, user)))
    }),
    http.get(`${B}/workspaces/:ws/cases/instances/:id`, ({ params }) => {
      const ws = String(params.ws), user = getUser(), row = instances.find(value => value.value.id === String(params.id))
      if (!member(ws, user) || !row || !definitions.some(def => def.ws === ws && def.id === row.value.definitionId) || !visible(row, user, ws)) return failure(404, 'NOT_FOUND', 'Case not found.')
      return HttpResponse.json(project(row, user))
    }),
    http.post(`${B}/workspaces/:ws/cases/definitions/:definitionId/instances`, ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('workflow.start')) return failure(403, 'FORBIDDEN', 'Case start is not allowed.')
      const def = definitions.find(value => value.ws === ws && value.id === String(params.definitionId))
      if (!def) return failure(404, 'NOT_FOUND', 'Definition not found.')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return failure(400, 'MALFORMED_REQUEST', 'Idempotency-Key is required.')
      const replay = replays.get(`start:${ws}:${user.id}:${key}`)
      if (replay) return HttpResponse.json(replay, { status: 200, headers: { 'Idempotent-Replayed': 'true' } })
      const value: Instance = { id: newId(), definitionId: def.id, definitionVersion: def.version, status: 'active', version: 1,
        tasks: [task(0)], createdAt: clock.iso() }
      instances.unshift({ value, startedBy: user.id, step: 0 })
      replays.set(`start:${ws}:${user.id}:${key}`, project(instances[0]!, user))
      return HttpResponse.json(project(instances[0]!, user), { status: 201 })
    }),
    http.post(`${B}/workspaces/:ws/cases/instances/:id/tasks/:taskId/complete`, async ({ params, request }) => {
      const ws = String(params.ws), user = getUser(), perms = permissionsOf(user, ws)
      if (!member(ws, user)) return failure(404, 'NOT_FOUND', 'Workspace not found.')
      if (!perms.includes('task.act')) return failure(403, 'FORBIDDEN', 'Case task action is not allowed.')
      const row = instances.find(value => value.value.id === String(params.id) && definitions.some(def => def.ws === ws && def.id === value.value.definitionId))
      if (!row) return failure(404, 'NOT_FOUND', 'Case not found.')
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
