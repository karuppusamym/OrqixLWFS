import { http, HttpResponse, type HttpHandler } from 'msw'
import type { WorkspaceFeature } from '@/api/types'
import { FEATURES, featureInfo } from '@/lib/features'
import { WORKSPACES, db, isMember, permissionsOf, userById, type MockUser } from './state'

/**
 * Workspace feature switches (docs/build/S127) for mock mode and unit tests: getWorkspaceFeatures, setWorkspaceFeature,
 * the AI status of a workspace where AI is off, and a gate that answers 409 FEATURE_DISABLED for the operations the
 * server always refuses while their module is off (x-orqix-feature without x-orqix-feature-when). The kind-dependent
 * checks (agent connections, Kafka adapters and inbound triggers) are server-only; the backend tests cover them.
 * Register these handlers first: the gate falls through to the other handlers when nothing is switched off.
 */
const B = '*/api/v1/workspaces/:ws'

/** Method + path below /workspaces/{id}/ -> modules that must be on. Mirrors WorkspaceFeatures.GATED_OPERATIONS. */
const GATES: ReadonlyArray<{ method: string; path: RegExp; features: WorkspaceFeature[] }> = [
  { method: 'POST', path: /^ai\/drafts$/, features: ['ai'] },
  { method: 'POST', path: /^workflows\/[^/]+\/explain$/, features: ['ai'] },
  { method: 'POST', path: /^workflows\/[^/]+\/ai\/form-suggestions$/, features: ['ai'] },
  { method: 'POST', path: /^workflows\/[^/]+\/ai\/draft-edits$/, features: ['ai'] },
  { method: 'POST', path: /^migrations\/[^/]+\/blueprint\/ai-proposal$/, features: ['migration', 'ai'] },
  { method: 'POST', path: /^cases\/definitions$/, features: ['cases'] },
  { method: 'POST', path: /^cases\/definitions\/[^/]+\/instances$/, features: ['cases'] },
  { method: 'POST', path: /^migrations\/[^/]+\/case-definition$/, features: ['migration', 'cases'] },
  { method: 'POST', path: /^migrations$/, features: ['migration'] },
  { method: 'POST', path: /^migrations\/[^/]+\/convert$/, features: ['migration'] },
  { method: 'POST', path: /^migrations\/blueprints$/, features: ['migration'] },
  { method: 'PUT', path: /^migrations\/[^/]+\/blueprint$/, features: ['migration'] },
  { method: 'POST', path: /^migrations\/projects$/, features: ['migration'] },
  { method: 'POST', path: /^migrations\/projects\/[^/]+\/convert-next$/, features: ['migration'] },
  { method: 'POST', path: /^agent-write-tools\/approvals$/, features: ['agents'] },
  { method: 'PUT', path: /^app-pages\/[^/]+$/, features: ['apps'] },
  { method: 'POST', path: /^app-pages\/[^/]+\/chains\/[^/]+\/run$/, features: ['apps'] },
  { method: 'POST', path: /^ui-extensions$/, features: ['apps'] },
  { method: 'POST', path: /^ui-extensions\/[^/]+\/versions$/, features: ['apps'] },
  { method: 'POST', path: /^ui-extensions\/[^/]+\/versions\/[^/]+\/decision$/, features: ['apps'] },
  { method: 'POST', path: /^kafka-triggers$/, features: ['kafka'] },
  { method: 'PUT', path: /^kafka-triggers\/[^/]+$/, features: ['kafka'] },
  { method: 'POST', path: /^kafka-triggers\/[^/]+\/test-message$/, features: ['kafka'] },
]

/** Modules switched off in the workspace, in contract order. */
export function disabledFeaturesOf(wsId: string): WorkspaceFeature[] {
  const off = new Set((db.featureSwitches ?? []).filter((s) => s.wsId === wsId && !s.enabled).map((s) => s.feature))
  return FEATURES.map((f) => f.feature).filter((f) => off.has(f))
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

function list(wsId: string) {
  return {
    items: FEATURES.map(({ feature }) => {
      const stored = (db.featureSwitches ?? []).find((s) => s.wsId === wsId && s.feature === feature)
      const by = stored ? userById(stored.updatedById) : undefined
      return {
        feature,
        enabled: stored?.enabled ?? true,
        updatedAt: stored?.updatedAt ?? null,
        updatedBy: stored ? { id: stored.updatedById, displayName: by?.displayName ?? 'Unknown user' } : null,
      }
    }),
  }
}

export function createFeatureHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const notFound = () => problem(404, 'NOT_FOUND', 'Not found', { detail: 'The resource does not exist or is not visible to you.' })
  return [
    http.get(`${B}/features`, ({ params }) => {
      const ws = String(params.ws)
      return visible(ws) ? HttpResponse.json(list(ws)) : notFound()
    }),
    http.put(`${B}/features/:feature`, async ({ params, request }) => {
      const ws = String(params.ws)
      if (!visible(ws)) return notFound()
      const user = currentUser()
      if (!permissionsOf(user, ws).includes('workspace.manage-access')) return problem(403, 'FORBIDDEN', 'You are not allowed to do this')
      const feature = String(params.feature) as WorkspaceFeature
      if (!FEATURES.some((f) => f.feature === feature)) return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { detail: `Unknown feature ${feature}.` })
      const body = (await request.json().catch(() => ({}))) as { enabled?: unknown }
      if (typeof body.enabled !== 'boolean') return problem(400, 'MALFORMED_REQUEST', 'The request is malformed', { detail: 'enabled must be true or false.' })
      const switches = (db.featureSwitches ??= [])
      const current = switches.find((s) => s.wsId === ws && s.feature === feature)
      if ((current?.enabled ?? true) !== body.enabled) {
        const next = { wsId: ws, feature, enabled: body.enabled, updatedAt: new Date().toISOString(), updatedById: user.id }
        if (current) Object.assign(current, next)
        else switches.push(next)
      }
      return HttpResponse.json(list(ws))
    }),
    // AI switched off: the status says so, as the server does, and every AI entry point hides itself.
    http.get(`${B}/ai/status`, ({ params }) => {
      const ws = String(params.ws)
      if (!visible(ws) || !disabledFeaturesOf(ws).includes('ai')) return undefined
      return HttpResponse.json({ enabled: false, reason: 'AI assistance is switched off in this workspace.', defaultProfile: null, profiles: [], excludedModels: [], disabledProfiles: [] })
    }),
    http.all(`${B}/*`, ({ params, request }) => {
      const ws = String(params.ws)
      const off = disabledFeaturesOf(ws)
      if (off.length === 0 || !visible(ws)) return undefined
      const rest = new URL(request.url).pathname.replace(/^.*?\/api\/v1\/workspaces\/[^/]+\//, '')
      const gate = GATES.find((g) => g.method === request.method && g.path.test(rest))
      const blocked = gate?.features.find((f) => off.includes(f))
      if (!blocked) return undefined
      return problem(409, 'FEATURE_DISABLED', 'This part of Orqix is switched off in this workspace', {
        detail: `${featureInfo(blocked).name} is switched off in this workspace. A workspace administrator can switch it on again in Settings, Features. Work that is already running is not affected.`,
        feature: blocked,
      })
    }),
  ]
}
