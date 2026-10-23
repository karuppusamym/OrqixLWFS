import { http, HttpResponse, type HttpHandler } from 'msw'
import type { WorklistViewState } from '@/api/worklists'
import type { ViewDefinition } from '@/features/views/model'
import { blankView, validateView } from '@/features/views/model'
import { clock, isMember, newId, permissionsOf, type MockUser, WS_PURCHASING } from './state'

interface StoredView {
  id: string
  ws: string
  name: string
  description: string | null
  definition: ViewDefinition
  draftRevision: number
  publishedVersion: number | null
  versions: Array<{ version: number; name: string; description: string | null; definition: ViewDefinition; publishedAt: string }>
  createdAt: string
  updatedAt: string
}

const views = new Map<string, StoredView>()
const worklists = new Map<string, WorklistViewState>()
const clone = <T,>(value: T): T => structuredClone(value)
const issue = (status: number, code: string, title: string) => HttpResponse.json({ status, code, title }, { status, headers: { 'Content-Type': 'application/problem+json' } })
const notFound = () => issue(404, 'NOT_FOUND', 'Not found')
const denied = () => issue(403, 'FORBIDDEN', 'Forbidden')
const invalid = (detail: string) => HttpResponse.json({ status: 422, code: 'VALIDATION_FAILED', title: 'Invalid view', detail }, { status: 422, headers: { 'Content-Type': 'application/problem+json' } })
const base = '/api/v1/workspaces/:workspaceId/views'

export function resetMockViews(): void {
  views.clear()
  worklists.clear()
  const definition = blankView()
  definition.sections[0]!.fields[0] = { id: 'amount', label: 'Amount', type: 'number', binding: 'request.amount', required: true }
  definition.sections[0]!.fields.push({ id: 'purpose', label: 'Purpose', type: 'text', binding: 'request.purpose', required: true })
  definition.actions.push({ id: 'submit', label: 'Submit request', type: 'submit' })
  const seeded: StoredView = { id: '77777777-7777-4777-8777-777777777777', ws: WS_PURCHASING, name: 'Purchase request', description: 'Reusable purchase request screen', definition, draftRevision: 1, publishedVersion: 1, versions: [{ version: 1, name: 'Purchase request', description: 'Reusable purchase request screen', definition: clone(definition), publishedAt: clock.iso() }], createdAt: clock.iso(), updatedAt: clock.iso() }
  views.set(seeded.id, seeded)
}

export function snapshotMockViews(): string {
  return JSON.stringify({ views: [...views.values()], worklists: [...worklists.entries()] })
}

export function restoreMockViews(value: unknown): boolean {
  let parsed: unknown
  try { parsed = typeof value === 'string' ? JSON.parse(value) : value } catch { return false }
  const saved = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as {views?: unknown;worklists?: unknown} : null
  if (saved) parsed=saved.views
  if (!Array.isArray(parsed) || !parsed.every(item => item && typeof item.id === 'string' && typeof item.ws === 'string' && item.definition && Array.isArray(item.versions))) return false
  views.clear()
  for (const item of parsed as StoredView[]) views.set(item.id, clone(item))
  worklists.clear()
  if (Array.isArray(saved?.worklists)) for (const entry of saved.worklists) {
    if (Array.isArray(entry) && typeof entry[0]==='string' && entry[1] && Number.isInteger(entry[1].revision) && entry[1].revision>=0 && (entry[1].binding===null || entry[1].binding?.definition?.kind==='worklist' && !validateView(entry[1].binding.definition).length)) worklists.set(entry[0],clone(entry[1]))
  }
  return true
}

function detail(view: StoredView) {
  return { id: view.id, name: view.name, description: view.description, draftRevision: view.draftRevision, publishedVersion: view.publishedVersion, definition: clone(view.definition), versions: view.versions.map(version => ({ version: version.version, publishedAt: version.publishedAt })) }
}

export function createViewHandlers(currentUser: () => MockUser): HttpHandler[] {
  const guard = (workspaceId: string, permission: 'workflow.read' | 'workflow.edit' | 'workflow.publish') => {
    const user = currentUser()
    if (!isMember(user, workspaceId)) return notFound()
    if (!permissionsOf(user, workspaceId).includes(permission)) return denied()
    return null
  }
  const find = (workspaceId: string, viewId: string) => {
    const view = views.get(viewId)
    return view?.ws === workspaceId ? view : null
  }
  const validate = (definition: ViewDefinition, name: string) => {
    if (!name?.trim() || name.length > 80) return 'Name is required and must be at most 80 characters.'
    return validateView(definition)[0] ?? null
  }
  return [
    http.get('/api/v1/workspaces/:workspaceId/worklist-view', ({params}) => {
      const ws=String(params.workspaceId), user=currentUser()
      if(!isMember(user,ws))return notFound()
      const permissions=permissionsOf(user,ws)
      if(!permissions.includes('workflow.read')&&!permissions.includes('task.act'))return denied()
      return HttpResponse.json(clone(worklists.get(ws)??{revision:0,binding:null}))
    }),
    http.put('/api/v1/workspaces/:workspaceId/worklist-view',async({params,request})=>{
      const ws=String(params.workspaceId),failure=guard(ws,'workflow.publish')
      if(failure)return failure
      const body=await request.json() as {expectedRevision:number;viewRef?:{id:string;version:number}|null}
      const current=worklists.get(ws)??{revision:0,binding:null}
      if(!Number.isInteger(body.expectedRevision)||body.expectedRevision<0)return invalid('Supply a valid worklist revision.')
      if(body.expectedRevision!==current.revision)return issue(409,'WORKFLOW_VERSION_CONFLICT','The worklist changed')
      let binding:WorklistViewState['binding']=null
      if(body.viewRef) {
        const view=find(ws,body.viewRef.id),version=view?.versions.find(item=>item.version===body.viewRef!.version)
        if(!version)return notFound()
        if(version.definition.kind!=='worklist'||validateView(version.definition).length)return invalid('Choose a compatible published worklist view.')
        binding={id:body.viewRef.id,version:version.version,definition:clone(version.definition)}
      }
      const next={revision:current.revision+1,binding};worklists.set(ws,next)
      return HttpResponse.json(clone(next))
    }),
    http.get(base, ({ params }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.read')
      if (failure) return failure
      const items = [...views.values()].filter(view => view.ws === ws).map(({ id, name, description, definition, draftRevision, publishedVersion, createdAt, updatedAt }) => ({ id, name, description, kind: definition.kind, draftRevision, publishedVersion, createdAt, updatedAt }))
      return HttpResponse.json({ items, viewLimit: 200 })
    }),
    http.post(base, async ({ params, request }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.edit')
      if (failure) return failure
      const payload = await request.json() as { name: string; description?: string; definition: ViewDefinition }
      const error = validate(payload.definition, payload.name)
      if (error) return invalid(error)
      const view: StoredView = { id: newId(), ws, name: payload.name, description: payload.description || null, definition: clone(payload.definition), draftRevision: 1, publishedVersion: null, versions: [], createdAt: clock.iso(), updatedAt: clock.iso() }
      views.set(view.id, view)
      return HttpResponse.json(detail(view), { status: 201 })
    }),
    http.get(`${base}/:viewId`, ({ params }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.read')
      if (failure) return failure
      const view = find(ws, String(params.viewId))
      return view ? HttpResponse.json(detail(view)) : notFound()
    }),
    http.post(`${base}/:viewId/copy`, async ({ params, request }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.edit')
      if (failure) return failure
      const source = find(ws, String(params.viewId))
      if (!source) return notFound()
      const { name } = await request.json() as { name: string }
      const error = validate(source.definition, name)
      if (error) return invalid(error)
      const copy: StoredView = { id: newId(), ws, name, description: source.description, definition: clone(source.definition), draftRevision: 1, publishedVersion: null, versions: [], createdAt: clock.iso(), updatedAt: clock.iso() }
      views.set(copy.id, copy)
      return HttpResponse.json(detail(copy), { status: 201 })
    }),
    http.put(`${base}/:viewId/draft`, async ({ params, request }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.edit')
      if (failure) return failure
      const view = find(ws, String(params.viewId))
      if (!view) return notFound()
      const payload = await request.json() as { expectedRevision: number; name: string; description?: string; definition: ViewDefinition }
      if (payload.expectedRevision !== view.draftRevision) return issue(409, 'REVISION_CONFLICT', 'A newer draft exists')
      const error = validate(payload.definition, payload.name)
      if (error) return invalid(error)
      view.name = payload.name
      view.description = payload.description || null
      view.definition = clone(payload.definition)
      view.draftRevision++
      view.updatedAt = clock.iso()
      return HttpResponse.json(detail(view))
    }),
    http.post(`${base}/:viewId/publish`, async ({ params, request }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.publish')
      if (failure) return failure
      const view = find(ws, String(params.viewId))
      if (!view) return notFound()
      const { expectedRevision } = await request.json() as { expectedRevision: number }
      if (expectedRevision !== view.draftRevision) return issue(409, 'REVISION_CONFLICT', 'A newer draft exists')
      const error = validate(view.definition, view.name)
      if (error) return invalid(error)
      const version = (view.publishedVersion ?? 0) + 1
      view.versions.push({ version, name: view.name, description: view.description, definition: clone(view.definition), publishedAt: clock.iso() })
      view.publishedVersion = version
      view.updatedAt = clock.iso()
      return HttpResponse.json(detail(view))
    }),
    http.get(`${base}/:viewId/versions/:version`, ({ params }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.read')
      if (failure) return failure
      const view = find(ws, String(params.viewId))
      if (!view) return notFound()
      const version = view.versions.find(item => item.version === Number(params.version))
      return version ? HttpResponse.json({ viewId: view.id, ...clone(version) }) : notFound()
    }),
  ]
}
