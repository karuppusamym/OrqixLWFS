import { http, HttpResponse, type HttpHandler } from 'msw'
import { allComponents, isPage, pinKey, validatePage, type PageDefinition, type PageLookups } from '@/features/pages/model'
import type { ViewDefinition } from '@/features/views/model'
import { mockExtensionInfo } from './extensions'
import { clock, isMember, permissionsOf, type MockUser } from './state'

/**
 * Mock of the S111 page runtime. Chains run step by step by calling the existing mock operations as the current user,
 * so every step is authorized by the same mock rules as the screens that use those operations directly.
 */
interface StoredRoute { ws: string; slug: string; revision: number; page: { id: string; version: number } | null; title: string | null; changedAt: string; history: Array<{ revision: number; page: { id: string; version: number } | null; changedBy: string; changedAt: string }> }
interface StoredDraft { values: Record<string, unknown>; savedAt: string }
type PublishedLookup = (ws: string, id: string, version: number) => ViewDefinition | PageDefinition | null

const routes = new Map<string, StoredRoute>()
const drafts = new Map<string, StoredDraft>()
const chainRuns = new Map<string, { hash: string; result: Record<string, unknown> }>()
const clone = <T,>(value: T): T => structuredClone(value)
const problem = (status: number, code: string, detail: string) => HttpResponse.json({ status, code, title: detail, detail }, { status, headers: { 'Content-Type': 'application/problem+json' } })
const routeKey = (ws: string, slug: string) => `${ws}:${slug}`
const base = '*/api/v1/workspaces/:workspaceId/app-pages'

export function resetMockPages(): void { routes.clear(); drafts.clear(); chainRuns.clear() }
export function snapshotMockPages(): unknown { return { routes: [...routes.values()], drafts: [...drafts.entries()] } }
export function restoreMockPages(value: unknown): void {
  resetMockPages()
  const saved = value as { routes?: StoredRoute[]; drafts?: Array<[string, StoredDraft]> } | null
  for (const route of saved?.routes ?? []) if (route && typeof route.slug === 'string') routes.set(routeKey(route.ws, route.slug), clone(route))
  for (const entry of saved?.drafts ?? []) if (Array.isArray(entry) && typeof entry[0] === 'string') drafts.set(entry[0], clone(entry[1]))
}

/** Lookups for page validation from the mock view and extension stores. */
export function mockPageLookups(ws: string, page: PageDefinition, published: PublishedLookup): PageLookups {
  const views = new Map<string, ViewDefinition | PageDefinition>()
  const extensions = new Map<string, ReturnType<typeof mockExtensionInfo> & object>()
  for (const component of allComponents(page)) {
    if (component.view?.id) { const view = published(ws, component.view.id, component.view.version); if (view) views.set(pinKey(component.view), view) }
    if (component.extension?.id) { const info = mockExtensionInfo(ws, component.extension.id, component.extension.version); if (info) extensions.set(pinKey(component.extension), info) }
  }
  return { views, extensions }
}

/** Server-like page checks: shape, every pin must exist, and request components must name a workflow. */
export function mockPageProblem(ws: string, page: PageDefinition, published: PublishedLookup, publishing: boolean): string | null {
  const lookups = mockPageLookups(ws, page, published)
  for (const component of allComponents(page)) {
    if (component.view && !lookups.views.has(pinKey(component.view))) return `Component ${component.id} pins a view version that does not exist in this workspace.`
    if (component.extension && !lookups.extensions.has(pinKey(component.extension))) return `Component ${component.id} pins an extension version that does not exist in this workspace.`
  }
  return validatePage(page, lookups, publishing)[0] ?? null
}

export function createPageHandlers(currentUser: () => MockUser, published: PublishedLookup): HttpHandler[] {
  const routed = (ws: string, slug: string) => {
    const route = routes.get(routeKey(ws, slug))
    if (!route?.page) return null
    const page = published(ws, route.page.id, route.page.version)
    return isPage(page) ? { route, page } : null
  }
  const internal = async (request: Request, method: string, path: string, body?: unknown, key?: string) => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (key) headers['Idempotency-Key'] = key
    const response = await fetch(new URL(`/api/v1${path}`, request.url), { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const data = await response.json().catch(() => ({})) as Record<string, unknown>
    if (!response.ok) throw Object.assign(new Error(String(data.detail ?? data.title ?? 'The step failed.')), { code: String(data.code ?? 'FAILED') })
    return data
  }
  return [
    http.get(base, ({ params }) => {
      const ws = String(params.workspaceId), user = currentUser()
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      const designer = permissionsOf(user, ws).includes('workflow.read')
      const items = [...routes.values()].filter(route => route.ws === ws && (designer || route.page)).sort((a, b) => a.slug.localeCompare(b.slug)).map(({ slug, revision, title, page, changedAt }) => ({ slug, revision, title, page, changedAt }))
      return HttpResponse.json({ items, canAssign: permissionsOf(user, ws).includes('workflow.publish') })
    }),
    http.put(`${base}/:slug`, async ({ params, request }) => {
      const ws = String(params.workspaceId), slug = String(params.slug), user = currentUser()
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissionsOf(user, ws).includes('workflow.publish')) return problem(403, 'FORBIDDEN', 'Forbidden')
      if (!/^[a-z][a-z0-9-]{0,38}[a-z0-9]$/.test(slug)) return problem(404, 'NOT_FOUND', 'Not found')
      const body = await request.json() as { expectedRevision?: number; viewRef?: { id: string; version: number } | null }
      let page: PageDefinition | null = null
      if (body.viewRef) {
        const found = published(ws, body.viewRef.id, body.viewRef.version)
        if (!found) return problem(422, 'VALIDATION_FAILED', 'Choose a published page version from this workspace.')
        if (!isPage(found)) return problem(422, 'VALIDATION_FAILED', 'Only application pages can be routed.')
        const issue = mockPageProblem(ws, found, published, true)
        if (issue) return problem(422, 'VALIDATION_FAILED', issue)
        page = found
      }
      const current = routes.get(routeKey(ws, slug))
      if ((current?.revision ?? 0) !== body.expectedRevision) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The route changed. Reload before pinning another version.')
      if (!page && !current?.page) return problem(422, 'VALIDATION_FAILED', 'This route has no page to remove.')
      const revision = (current?.revision ?? 0) + 1
      const pin = page && body.viewRef ? { id: body.viewRef.id, version: body.viewRef.version } : null
      const next: StoredRoute = { ws, slug, revision, page: pin, title: page?.title ?? null, changedAt: clock.iso(), history: [...(current?.history ?? []), { revision, page: pin, changedBy: user.id, changedAt: clock.iso() }] }
      routes.set(routeKey(ws, slug), next)
      return HttpResponse.json({ slug, revision, title: next.title, page: pin, changedAt: next.changedAt })
    }),
    http.get(`${base}/:slug`, ({ params }) => {
      const ws = String(params.workspaceId), user = currentUser()
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      const found = routed(ws, String(params.slug))
      if (!found) return problem(404, 'NOT_FOUND', 'Not found')
      const lookups = mockPageLookups(ws, found.page, published)
      const views = allComponents(found.page).filter(component => component.view).map(component => ({ id: component.view!.id, version: component.view!.version, definition: lookups.views.get(pinKey(component.view!)) }))
      const extensions = allComponents(found.page).filter(component => component.extension).flatMap(component => { const info = mockExtensionInfo(ws, component.extension!.id, component.extension!.version); return info ? [info] : [] })
      const permissions = permissionsOf(user, ws)
      const start = permissions.includes('workflow.start') ? [...new Set(allComponents(found.page).flatMap(component => component.workflowId ? [component.workflowId] : []))] : []
      return HttpResponse.json({ slug: found.route.slug, revision: found.route.revision, page: { id: found.route.page!.id, version: found.route.page!.version, definition: found.page }, views, extensions, grants: { startWorkflows: start, taskAct: permissions.includes('task.act'), readCases: permissions.includes('task.act') || permissions.includes('workflow.start') } })
    }),
    http.get(`${base}/:slug/history`, ({ params }) => {
      const ws = String(params.workspaceId), user = currentUser()
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissionsOf(user, ws).includes('workflow.read')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const route = routes.get(routeKey(ws, String(params.slug)))
      return route ? HttpResponse.json({ items: [...route.history].reverse() }) : problem(404, 'NOT_FOUND', 'Not found')
    }),
    http.get(`${base}/:slug/drafts`, ({ params }) => {
      const ws = String(params.workspaceId), user = currentUser()
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      const found = routed(ws, String(params.slug))
      if (!found) return problem(404, 'NOT_FOUND', 'Not found')
      const prefix = `${ws}:${found.route.page!.id}:`
      const items = [...drafts.entries()].filter(([key]) => key.startsWith(prefix) && key.endsWith(`:${user.id}`)).map(([key, draft]) => ({ componentId: key.slice(prefix.length, key.length - user.id.length - 1), values: clone(draft.values), savedAt: draft.savedAt }))
      return HttpResponse.json({ items })
    }),
    http.get(`${base}/:slug/components/:componentId/data`, async ({ params, request }) => {
      const ws = String(params.workspaceId), user = currentUser()
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      const found = routed(ws, String(params.slug))
      const component = found ? allComponents(found.page).find(item => item.id === params.componentId) : undefined
      if (!found || component?.type !== 'extension' || !component.extension) return problem(404, 'NOT_FOUND', 'Not found')
      if (mockExtensionInfo(ws, component.extension.id, component.extension.version)?.status !== 'approved') return problem(403, 'FORBIDDEN', 'This extension version is no longer approved.')
      const values: Record<string, unknown> = {}
      const context = new URL(request.url).searchParams.get('context')
      if (!component.bindings?.length || found.page.context === 'none') return HttpResponse.json({ values })
      if (!context) return problem(422, 'VALIDATION_FAILED', `Open the page for a ${found.page.context}.`)
      try {
        if (found.page.context === 'task') {
          const task = await internal(request, 'GET', `/workspaces/${ws}/tasks/${context}`) as { requestTitle?: string; runReference?: string; label?: string; workflowName?: string; requester?: { displayName: string }; createdAt?: string; dueAt?: string | null; status?: string; priority?: number; request?: { input?: Record<string, unknown> } }
          const summary: Record<string, unknown> = { requestTitle: task.requestTitle, reference: task.runReference, label: task.label, workflowName: task.workflowName, requester: task.requester?.displayName, createdAt: task.createdAt, dueAt: task.dueAt ?? null, status: task.status, priority: task.priority }
          for (const binding of component.bindings) {
            const field = binding.slice(binding.indexOf('.') + 1)
            if (binding.startsWith('task.')) values[binding] = summary[field] ?? null
            else if (task.request?.input && field in task.request.input) values[binding] = task.request.input[field]
          }
        } else {
          const data = await internal(request, 'GET', `/workspaces/${ws}/cases/instances/${context}/data`) as { values?: Record<string, unknown> }
          for (const binding of component.bindings) { const field = binding.slice(5); if (data.values && field in data.values) values[binding] = data.values[field] }
        }
      } catch (error) {
        const code = (error as { code?: string }).code ?? 'NOT_FOUND'
        return problem(code === 'FORBIDDEN' ? 403 : 404, code, (error as Error).message)
      }
      return HttpResponse.json({ values })
    }),
    http.post(`${base}/:slug/chains/:chainId/run`, async ({ params, request }) => {
      const ws = String(params.workspaceId), user = currentUser(), slug = String(params.slug), chainId = String(params.chainId)
      if (!isMember(user, ws)) return problem(404, 'NOT_FOUND', 'Not found')
      const key = request.headers.get('Idempotency-Key')
      if (!key) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', 'An Idempotency-Key header is required')
      const body = await request.json() as { expectedRevision?: number; context?: string; source?: string; inputs?: Record<string, { values?: Record<string, unknown>; expectedVersion?: number; reason?: string; comment?: string }> }
      const found = routed(ws, slug)
      if (!found) return problem(404, 'NOT_FOUND', 'Not found')
      if (found.route.revision !== body.expectedRevision) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The page changed. Reload it before running an action.')
      const chain = found.page.chains.find(item => item.id === chainId)
      if (!chain) return problem(404, 'NOT_FOUND', 'Not found')
      const components = allComponents(found.page)
      if (body.source) {
        const source = components.find(item => item.id === body.source)
        if (source?.type !== 'extension' || !source.chains?.includes(chainId)) return problem(403, 'FORBIDDEN', 'The extension component is not granted this chain.')
        if (mockExtensionInfo(ws, source.extension!.id, source.extension!.version)?.status !== 'approved') return problem(403, 'FORBIDDEN', 'This extension version is no longer approved.')
      } else if (!components.some(item => item.type === 'actions' && item.chains?.includes(chainId))) return problem(403, 'FORBIDDEN', 'This chain is not placed on the page.')
      if (found.page.context !== 'none' && chain.steps.some(step => ['claim', 'approve', 'reject', 'saveCaseData'].includes(step.action)) && !body.context) return problem(422, 'VALIDATION_FAILED', `Open the page for a ${found.page.context} before running this action.`)
      const runKey = `${ws}:${user.id}:${key}`, hash = JSON.stringify([slug, chainId, body])
      const previous = chainRuns.get(runKey)
      if (previous) return previous.hash === hash ? HttpResponse.json(clone(previous.result)) : problem(409, 'IDEMPOTENCY_KEY_REUSED', 'The Idempotency-Key was used for a different request')
      const lookups = mockPageLookups(ws, found.page, published)
      const steps: Array<{ index: number; action: string; component: string | null; status: string; detail: string | null; problemCode: string | null; runId: string | null }> = []
      let failed = false, navigateTo: string | null = null, taskVersion: number | null = null
      for (const [index, step] of chain.steps.entries()) {
        const component = step.component ? components.find(item => item.id === step.component) : undefined
        if (failed) { steps.push({ index, action: step.action, component: step.component ?? null, status: 'skipped', detail: null, problemCode: null, runId: null }); continue }
        const input = step.component ? body.inputs?.[step.component] : undefined
        const view = component?.view ? lookups.views.get(pinKey(component.view)) : undefined
        const bound = (prefix: string) => {
          const allowed = new Set(view && !isPage(view) ? view.sections.flatMap(section => section.fields.map(field => field.binding)).filter(binding => binding.startsWith(prefix)).map(binding => binding.slice(prefix.length)) : [])
          const values = input?.values
          if (!values || typeof values !== 'object') throw Object.assign(new Error('Supply the form values for this step.'), { code: 'VALIDATION_FAILED' })
          if (Object.keys(values).some(name => !allowed.has(name))) throw Object.assign(new Error('Values must belong to fields of the pinned view.'), { code: 'VALIDATION_FAILED' })
          return values
        }
        const version = () => { const value = taskVersion ?? input?.expectedVersion; if (!value) throw Object.assign(new Error('Supply the version you loaded.'), { code: 'VALIDATION_FAILED' }); return value }
        try {
          let runId: string | null = null
          switch (step.action) {
            case 'navigate': navigateTo = step.target ?? null; break
            case 'submit': {
              const run = await internal(request, 'POST', `/workspaces/${ws}/runs`, { workflowId: component!.workflowId, input: bound('request.') }, `${key}:${index}`)
              runId = String(run.id)
              drafts.delete(`${ws}:${found.route.page!.id}:${component!.id}:${user.id}`)
              break
            }
            case 'saveDraft':
              if (!permissionsOf(user, ws).includes('workflow.start')) throw Object.assign(new Error('Forbidden'), { code: 'FORBIDDEN' })
              drafts.set(`${ws}:${found.route.page!.id}:${component!.id}:${user.id}`, { values: clone(bound('request.')), savedAt: clock.iso() })
              break
            case 'claim': taskVersion = Number((await internal(request, 'POST', `/workspaces/${ws}/tasks/${body.context}/claim`, { expectedVersion: version() })).version); break
            case 'approve': case 'reject': await internal(request, 'POST', `/workspaces/${ws}/tasks/${body.context}/decision`, { expectedVersion: version(), action: step.action, ...(input?.reason ? { reason: input.reason } : {}), ...(input?.comment ? { comment: input.comment } : {}) }, `${key}:${index}`); break
            case 'saveCaseData': await internal(request, 'POST', `/workspaces/${ws}/cases/instances/${body.context}/data`, { expectedVersion: version(), reason: input?.reason, values: bound('case.') }, `${key}:${index}`); break
          }
          steps.push({ index, action: step.action, component: step.component ?? null, status: 'succeeded', detail: null, problemCode: null, runId })
        } catch (error) {
          failed = true
          navigateTo = null
          steps.push({ index, action: step.action, component: step.component ?? null, status: 'failed', detail: (error as Error).message, problemCode: (error as { code?: string }).code ?? 'FAILED', runId: null })
        }
      }
      const result = { completed: !failed, navigateTo, steps }
      chainRuns.set(runKey, { hash, result: clone(result) })
      return HttpResponse.json(result)
    }),
  ]
}
