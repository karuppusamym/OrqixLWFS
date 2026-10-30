import { http, HttpResponse, type HttpHandler } from 'msw'
import { isPage, type PageDefinition } from '@/features/pages/model'
import type { ViewDefinition } from '@/features/views/model'
import { clock, isMember, newId, permissionsOf, type MockUser } from './state'

/**
 * Mock of the S114 reviewed pin migration. The inventory covers page drafts, published pages, page routes and the
 * workspace worklist (mock workflows and cases do not pin views). Applying calls the existing mock operations as the
 * current user, so their permission and revision rules hold; unlike the server, the mock is not one transaction.
 */
export interface PinStores {
  views: (ws: string) => Array<{ id: string; name: string; description: string | null; definition: ViewDefinition | PageDefinition; draftRevision: number; publishedVersion: number | null; versions: Array<{ version: number; definition: ViewDefinition | PageDefinition }> }>
  worklist: (ws: string) => { revision: number; binding: { id: string; version: number } | null }
  routes: (ws: string) => Array<{ slug: string; revision: number; page: { id: string; version: number } | null }>
  pageProblem: (ws: string, page: PageDefinition, publishing: boolean) => string | null
  viewProblem: (definition: ViewDefinition) => string | null
}
type Item = { key: string; holderType: string; holderId: string; holderName: string; location: string; pinnedVersions: number[]; expected: number | null; status: string; problems: string[]; effect: string }

const clone = <T,>(value: T): T => structuredClone(value)
const problem = (status: number, code: string, detail: string) => HttpResponse.json({ status, code, title: detail, detail }, { status, headers: { 'Content-Type': 'application/problem+json' } })
const migrations = new Map<string, Array<Record<string, unknown>>>()
export function resetMockPins(): void { migrations.clear() }

function status(pinned: number[], target: number, problems: string[]): string {
  if (pinned.every(version => version === target)) return 'current'
  if (pinned.some(version => version > target)) { problems.push(`It already uses a later version than ${target}.`); return 'newer' }
  return problems.length ? 'blocked' : 'ready'
}
const pagePins = (page: PageDefinition, id: string) => page.regions.flatMap(region => region.components).filter(component => component.view?.id === id).map(component => ({ location: `Component ${component.id}`, version: component.view!.version }))
function migratePage(page: PageDefinition, id: string, version: number): PageDefinition {
  const next = clone(page)
  for (const region of next.regions) for (const component of region.components) if (component.view?.id === id) component.view = { id, version }
  return next
}

async function hash(value: unknown): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(value)))
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

export function createViewPinHandlers(currentUser: () => MockUser, stores: PinStores): HttpHandler[] {
  const base = '*/api/v1/workspaces/:workspaceId/views/:viewId'
  const plan = async (ws: string, viewId: string, requested: number | null) => {
    const view = stores.views(ws).find(item => item.id === viewId)
    if (!view) return { error: problem(404, 'NOT_FOUND', 'Not found') }
    if (!view.publishedVersion) return { error: problem(422, 'VALIDATION_FAILED', 'Publish this view before moving pins to it.') }
    const version = requested ?? view.publishedVersion
    const target = view.versions.find(item => item.version === version)
    if (!target) return { error: problem(422, 'VALIDATION_FAILED', 'Choose a published version of this view.') }
    const items: Item[] = []
    if (!isPage(target.definition)) {
      for (const page of stores.views(ws).filter(item => item.id !== viewId && isPage(item.definition))) {
        const pins = pagePins(page.definition as PageDefinition, viewId)
        if (pins.length) {
          const problems: string[] = []
          let state = status([...new Set(pins.map(pin => pin.version))], version, problems)
          if (state === 'ready') { const issue = stores.pageProblem(ws, migratePage(page.definition as PageDefinition, viewId, version), false); if (issue) { problems.push(issue); state = 'blocked' } }
          items.push({ key: `page:${page.id}`, holderType: 'pageDraft', holderId: page.id, holderName: page.name, location: pins.map(pin => pin.location).join(', '), pinnedVersions: [...new Set(pins.map(pin => pin.version))].sort(), expected: page.draftRevision, status: state, problems, effect: `Saves page draft revision ${page.draftRevision + 1}. Publish the page and pin its route to use it.` })
        }
        const published = page.versions.find(item => item.version === page.publishedVersion)
        const fixed = published && isPage(published.definition) ? pagePins(published.definition, viewId) : []
        if (fixed.length) items.push({ key: `pageVersion:${page.id}`, holderType: 'pageVersion', holderId: page.id, holderName: page.name, location: `Published version ${page.publishedVersion}`, pinnedVersions: [...new Set(fixed.map(pin => pin.version))].sort(), expected: null, status: 'readOnly', problems: [], effect: `Published version ${page.publishedVersion} keeps its pins. Publish the updated page draft to change them.` })
      }
    }
    const worklist = stores.worklist(ws)
    if (target.definition.kind === 'worklist' && worklist.binding?.id === viewId) {
      const problems: string[] = []
      const issue = stores.viewProblem(target.definition as ViewDefinition)
      if (issue) problems.push(issue)
      items.push({ key: 'worklist', holderType: 'worklist', holderId: ws, holderName: 'Workspace worklist', location: 'Task list presentation', pinnedVersions: [worklist.binding.version], expected: worklist.revision, status: status([worklist.binding.version], version, problems), problems, effect: `Assigns the new version as worklist revision ${worklist.revision + 1}.` })
    }
    if (isPage(target.definition)) {
      const issue = stores.pageProblem(ws, target.definition, true)
      for (const route of stores.routes(ws).filter(item => item.page?.id === viewId)) {
        const problems: string[] = []
        let state = status([route.page!.version], version, problems)
        if (state === 'ready' && issue) { problems.push(issue); state = 'blocked' }
        items.push({ key: `route:${route.slug}`, holderType: 'route', holderId: route.slug, holderName: `/apps/${route.slug}`, location: 'Page route', pinnedVersions: [route.page!.version], expected: route.revision, status: state, problems, effect: `Pins the route to the new version as revision ${route.revision + 1}.` })
      }
    }
    const planHash = await hash([`${viewId}:${version}`, ...items.map(item => [item.key, item.expected, item.pinnedVersions, item.status, item.problems])])
    return { inventory: { viewId, viewName: view.name, kind: target.definition.kind, latestVersion: view.publishedVersion, targetVersion: version, planHash, items }, view }
  }
  const guard = (ws: string, permission: string) => {
    const user = currentUser()
    if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
    if (!permissionsOf(user, ws).includes(permission as never)) return problem(403, 'FORBIDDEN', 'Forbidden')
    return null
  }
  return [
    http.get(`${base}/pins`, async ({ params, request }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.read')
      if (failure) return failure
      const requested = new URL(request.url).searchParams.get('targetVersion')
      const result = await plan(ws, String(params.viewId), requested ? Number(requested) : null)
      return result.error ?? HttpResponse.json(result.inventory)
    }),
    http.get(`${base}/pin-migrations`, ({ params }) => {
      const ws = String(params.workspaceId)
      const failure = guard(ws, 'workflow.read')
      if (failure) return failure
      return HttpResponse.json({ items: clone(migrations.get(`${ws}:${String(params.viewId)}`) ?? []) })
    }),
    http.post(`${base}/pin-migrations`, async ({ params, request }) => {
      const ws = String(params.workspaceId), viewId = String(params.viewId)
      const failure = guard(ws, 'workflow.edit')
      if (failure) return failure
      const body = await request.json() as { targetVersion?: number; planHash?: string; items?: string[]; reason?: string }
      const reason = body.reason?.trim() ?? ''
      if (!body.items?.length || body.items.length > 50 || new Set(body.items).size !== body.items.length) return problem(422, 'VALIDATION_FAILED', 'Choose 1 to 50 distinct pins to update.')
      if (!reason || reason.length > 500) return problem(422, 'VALIDATION_FAILED', 'Give a reason of 1 to 500 characters.')
      const result = await plan(ws, viewId, body.targetVersion ?? null)
      if (result.error) return result.error
      const { inventory } = result
      if (inventory.planHash !== body.planHash) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The pins changed since you reviewed them. Review them again before updating.')
      const chosen = body.items.map(key => inventory.items.find(item => item.key === key))
      const notReady = chosen.find(item => !item || item.status !== 'ready')
      if (notReady !== undefined) return problem(422, 'VALIDATION_FAILED', `${notReady?.holderName ?? 'A pin'} is not ready to move to version ${inventory.targetVersion}.`)
      const call = async (method: string, path: string, payload: unknown) => {
        const response = await fetch(new URL(`/api/v1/workspaces/${ws}${path}`, request.url), { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
        const data = await response.json().catch(() => ({})) as Record<string, unknown>
        if (!response.ok) throw Object.assign(new Error(String(data.detail ?? data.title ?? 'The update failed.')), { status: response.status, code: String(data.code ?? 'FAILED') })
        return data
      }
      const applied: Array<{ key: string; holderType: string; holderName: string; outcome: string }> = []
      try {
        for (const item of chosen as Item[]) {
          if (item.holderType === 'pageDraft') {
            const page = stores.views(ws).find(view => view.id === item.holderId)!
            const saved = await call('PUT', `/views/${page.id}/draft`, { expectedRevision: item.expected, name: page.name, description: page.description ?? undefined, definition: migratePage(page.definition as PageDefinition, viewId, inventory.targetVersion) })
            applied.push({ key: item.key, holderType: item.holderType, holderName: item.holderName, outcome: `Saved page draft revision ${String(saved.draftRevision)}.` })
          } else if (item.holderType === 'worklist') {
            const saved = await call('PUT', '/worklist-view', { expectedRevision: item.expected, viewRef: { id: viewId, version: inventory.targetVersion } })
            applied.push({ key: item.key, holderType: item.holderType, holderName: item.holderName, outcome: `Assigned as worklist revision ${String(saved.revision)}.` })
          } else if (item.holderType === 'route') {
            const saved = await call('PUT', `/app-pages/${item.holderId}`, { expectedRevision: item.expected, viewRef: { id: viewId, version: inventory.targetVersion } })
            applied.push({ key: item.key, holderType: item.holderType, holderName: item.holderName, outcome: `Pinned as route revision ${String(saved.revision)}.` })
          }
        }
      } catch (error) {
        const failed = error as Error & { status?: number; code?: string }
        return problem(failed.status ?? 422, failed.code ?? 'VALIDATION_FAILED', failed.message)
      }
      const migration = { id: newId(), targetVersion: inventory.targetVersion, reason, items: applied, appliedBy: currentUser().id, appliedAt: clock.iso() }
      migrations.set(`${ws}:${viewId}`, [migration, ...(migrations.get(`${ws}:${viewId}`) ?? [])].slice(0, 50))
      return HttpResponse.json(clone(migration))
    }),
  ]
}
