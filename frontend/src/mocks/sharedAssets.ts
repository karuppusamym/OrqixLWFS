import { delay, http, HttpResponse, type HttpHandler } from 'msw'
import type { DecisionAssetContent, Permission, SharedAsset, SharedAssetKind, SharedAssetSummary } from '@/api/types'
import { canonical } from '@/lib/canonicalJson'
import type { Definition } from '@/features/studio/model/types'
import { documentTemplatesOf } from './documentLibrary'
import { getCurrentUser } from './handlers'
import { USERS, WORKSPACES, agentConnectionDefinition, clock, connectionDefinition, db, isMember, newId, permissionsOf, purchaseTemplate, userById, userByName, type MockUser } from './state'

/**
 * Shared assets in mock mode (PL01, docs/build/S61): a simplified double of the server. Decisions live here, document
 * templates come from the document library mock, connections are the two seeded definitions (version 1). Rules kept:
 * per-kind read/manage permissions, revision compare-and-set, owner eligibility, archive, where-used and the reference
 * findings added to validation. Hashes are compared as key-sorted JSON, not SHA-256.
 */

const B = '/api/v1'
type Decision = { wsId: string; id: string; key: string; name: string; description: string | null; ownerId: string; revision: number; archived: boolean; updatedAt: string; versions: Array<{ version: number; content: DecisionAssetContent; changeNote: string | null; createdById: string; createdAt: string }> }
const decisions = new Map<string, Decision>()
const connectionHeaders = new Map<string, { ownerId: string; revision: number; archived: boolean; updatedAt: string }>()

export function resetSharedAssets(): void {
  decisions.clear()
  connectionHeaders.clear()
}

function seed(): void {
  if (decisions.size > 0) return
  const table = purchaseTemplate().decisions[0]!
  const wsId = WORKSPACES[0]!.id
  const now = clock.iso()
  decisions.set(`${wsId}:spend-limit`, {
    wsId, id: newId(), key: 'spend-limit', name: 'Spend limit', description: 'When a purchase needs finance review.', ownerId: userByName('dana.designer').id,
    revision: 1, archived: false, updatedAt: now,
    versions: [{ version: 1, content: { hitPolicy: table.hitPolicy, rows: structuredClone(table.rows) as unknown as DecisionAssetContent['rows'], default: structuredClone(table.default) }, changeNote: 'First version', createdById: userByName('dana.designer').id, createdAt: now }],
  })
}

const CONNECTION_DOCS = [connectionDefinition, agentConnectionDefinition] as unknown as Array<{ key: string; name: string; description?: string }>

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}): Response {
  return HttpResponse.json({ type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } })
}
const notFound = () => problem(404, 'NOT_FOUND', 'Not found')
const forbidden = () => problem(403, 'FORBIDDEN', 'Forbidden')
const conflict = (revision: number) => problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The workflow changed since you loaded it', { detail: 'The asset changed. Reload before saving.', currentRevision: revision })
const invalid = (detail: string) => problem(422, 'VALIDATION_FAILED', 'Some values are not valid', { detail })

const readPermission = (kind: SharedAssetKind): Permission => (kind === 'connection' ? 'connection.read' : 'workflow.read')
const managePermission = (kind: SharedAssetKind): Permission => (kind === 'connection' ? 'connection.manage' : 'workflow.edit')
const ref = (id: string) => ({ id, displayName: userById(id)?.displayName ?? 'Unknown person' })
const KINDS: SharedAssetKind[] = ['decision', 'documentTemplate', 'connection']

interface Row { summary: SharedAssetSummary; versions: SharedAsset['versions'] }

function rows(wsId: string): Row[] {
  seed()
  const out: Row[] = []
  for (const d of decisions.values()) {
    if (d.wsId !== wsId) continue
    out.push({
      summary: { kind: 'decision', key: d.key, name: d.name, description: d.description, owner: ref(d.ownerId), latestVersion: d.versions.length, archived: d.archived, revision: d.revision, updatedAt: d.updatedAt, id: d.id },
      versions: [...d.versions].reverse().map((v) => ({ version: v.version, sha256: 'd'.repeat(64), changeNote: v.changeNote, createdBy: ref(v.createdById), createdAt: v.createdAt, content: v.content })),
    })
  }
  for (const t of documentTemplatesOf(wsId)) {
    out.push({
      summary: { kind: 'documentTemplate', key: t.key, name: t.name, description: t.description ?? null, owner: t.owner, latestVersion: t.latestVersion, archived: t.archived, revision: t.revision, updatedAt: t.updatedAt, id: t.id },
      versions: t.versions.map((v) => ({ version: v.version, sha256: v.sha256, changeNote: v.changeNote ?? null, createdBy: v.createdBy, createdAt: v.createdAt, content: null })),
    })
  }
  if (wsId !== WORKSPACES[2]?.id) {
    for (const c of CONNECTION_DOCS) {
      const header = connectionHeaders.get(`${wsId}:${c.key}`)
      out.push({
        summary: { kind: 'connection', key: c.key, name: c.name, description: c.description ?? null, owner: ref(header?.ownerId ?? userByName('wade.admin').id), latestVersion: 1, archived: header?.archived ?? false, revision: header?.revision ?? 0, updatedAt: header?.updatedAt ?? '2026-10-01T09:00:00Z', id: null },
        versions: [{ version: 1, sha256: 'a'.repeat(64), changeNote: null, createdBy: ref(userByName('wade.admin').id), createdAt: '2026-10-01T09:00:00Z', content: null }],
      })
    }
  }
  return out
}

/** Reference findings for the mock validate handler: the same codes and severities as the server. */
export function assetIssues(wsId: string, def: Definition): Array<{ code: string; severity: 'blocker' | 'warning'; message: string; path: string; nodeId: string | null; transitionId: null }> {
  const issues: ReturnType<typeof assetIssues> = []
  const all = rows(wsId)
  ;(def.dependencies.assets ?? []).forEach((a, i) => {
    const path = `/dependencies/assets/${i}`
    const issue = (code: string, severity: 'blocker' | 'warning', message: string, nodeId: string | null = null) => issues.push({ code, severity, message, path, nodeId, transitionId: null })
    const row = all.find((r) => r.summary.kind === a.kind && r.summary.key === a.key)
    const table = a.kind === 'decision' ? def.decisions.find((d) => d.key === a.target) : undefined
    const node = a.kind === 'documentTemplate' ? def.nodes.find((n) => n.id === a.target && n.type === 'generateDocument') : undefined
    if (!table && !node) return issue('WF_ASSET_REFERENCE_INVALID', 'blocker', `The link to "${a.key}" names "${a.target}", which is not in this workflow.`)
    const nodeId = node?.id ?? null
    if (!row || !row.versions.some((v) => v.version === a.version)) return issue('WF_ASSET_MISSING', 'blocker', `The shared asset "${a.key}" version ${a.version} does not exist in this workspace.`, nodeId)
    if (row.summary.archived) issue('WF_ASSET_ARCHIVED', 'blocker', `The shared asset "${a.key}" is archived.`, nodeId)
    let same = true
    if (table) {
      const content = decisions.get(`${wsId}:${a.key}`)?.versions.find((v) => v.version === a.version)?.content
      same = !!content && canonical({ hitPolicy: table.hitPolicy, rows: table.rows, default: table.default }) === canonical(content)
    } else if (node) {
      const version = documentTemplatesOf(wsId).find((t) => t.key === a.key)?.versions.find((v) => v.version === a.version)
      same = !!version && version.body === node.config.template && version.format === (node.config.format ?? 'pdf')
    }
    if (!same) issue('WF_ASSET_DIVERGED', 'blocker', `"${a.target}" no longer matches version ${a.version} of "${a.key}".`, nodeId)
    if (a.version < row.summary.latestVersion) issue('WF_ASSET_STALE', 'warning', `"${a.target}" uses version ${a.version} of "${a.key}"; version ${row.summary.latestVersion} is available.`, nodeId)
  })
  return issues
}

export function createSharedAssetHandlers(): HttpHandler[] {
  const slow = () => delay(db.latencyMs)
  const member = (wsId: string): MockUser | null => {
    const user = getCurrentUser()
    return WORKSPACES.some((w) => w.id === wsId) && isMember(user, wsId) ? user : null
  }
  const find = (wsId: string, kind: string, key: string) => rows(wsId).find((r) => r.summary.kind === kind && r.summary.key === key)
  return [
    http.get(`${B}/workspaces/:ws/shared-assets`, async ({ params, request }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      const url = new URL(request.url)
      const kind = url.searchParams.get('kind') as SharedAssetKind | null
      if (kind && !KINDS.includes(kind)) return problem(400, 'MALFORMED_REQUEST', 'The request is malformed')
      const perms = permissionsOf(user, wsId)
      const kinds = (kind ? [kind] : KINDS).filter((k) => perms.includes(readPermission(k)))
      if (kinds.length === 0) return forbidden()
      const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
      const owner = url.searchParams.get('owner')
      const archived = url.searchParams.get('includeArchived') === 'true'
      const items = rows(wsId).map((r) => r.summary)
        .filter((a) => kinds.includes(a.kind) && (archived || !a.archived) && (!owner || a.owner.id === owner))
        .filter((a) => !q || a.name.toLowerCase().includes(q) || a.key.includes(q) || a.owner.displayName.toLowerCase().includes(q))
        .sort((a, b) => a.name.localeCompare(b.name))
      return HttpResponse.json({ items, searchedKinds: kinds, truncated: false })
    }),
    http.get(`${B}/workspaces/:ws/shared-asset-owner-candidates`, async ({ params, request }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      const kind = new URL(request.url).searchParams.get('kind') as SharedAssetKind
      if (!KINDS.includes(kind)) return problem(400, 'MALFORMED_REQUEST', 'The request is malformed')
      if (!permissionsOf(user, wsId).includes(managePermission(kind))) return forbidden()
      return HttpResponse.json({ items: USERS.filter((u) => isMember(u, wsId) && permissionsOf(u, wsId).includes(managePermission(kind))).map((u) => ({ id: u.id, displayName: u.displayName })) })
    }),
    http.post(`${B}/workspaces/:ws/shared-assets/decision`, async ({ params, request }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.edit')) return forbidden()
      const body = (await request.json()) as { key: string; name: string; description?: string | null; content: DecisionAssetContent; changeNote?: string | null }
      if (!/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(body.key)) return invalid('Use lowercase letters, digits and dashes for the key.')
      if (!body.content?.rows?.length) return invalid('rows must list 1 to 50 rows.')
      if ([...decisions.values()].some((d) => d.wsId === wsId && (d.key === body.key || d.name.toLowerCase() === body.name.trim().toLowerCase()))) {
        return problem(409, 'DUPLICATE_KEY', 'The key is already in use', { detail: 'A shared decision with this key or name already exists.' })
      }
      const now = clock.iso()
      decisions.set(`${wsId}:${body.key}`, { wsId, id: newId(), key: body.key, name: body.name.trim(), description: body.description ?? null, ownerId: user.id, revision: 1, archived: false, updatedAt: now, versions: [{ version: 1, content: body.content, changeNote: body.changeNote ?? null, createdById: user.id, createdAt: now }] })
      const row = find(wsId, 'decision', body.key)!
      return HttpResponse.json({ asset: row.summary, versions: row.versions }, { status: 201 })
    }),
    http.post(`${B}/workspaces/:ws/shared-assets/decision/:key/versions`, async ({ params, request }) => {
      await slow()
      seed()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      if (!permissionsOf(user, wsId).includes('workflow.edit')) return forbidden()
      const d = decisions.get(`${wsId}:${String(params.key)}`)
      if (!d) return notFound()
      const body = (await request.json()) as { expectedVersion: number; content: DecisionAssetContent; changeNote?: string | null }
      if (body.expectedVersion !== d.versions.length) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The workflow changed since you loaded it', { detail: 'The shared decision changed. Reload before saving.', currentVersion: d.versions.length })
      if (d.archived) return invalid('Restore the shared decision before adding a version.')
      if (canonical(d.versions.at(-1)!.content) === canonical(body.content)) return invalid('The new version is identical to the latest version.')
      d.updatedAt = clock.iso()
      d.versions.push({ version: d.versions.length + 1, content: body.content, changeNote: body.changeNote ?? null, createdById: user.id, createdAt: d.updatedAt })
      const row = find(wsId, 'decision', d.key)!
      return HttpResponse.json({ asset: row.summary, versions: row.versions }, { status: 201 })
    }),
    http.get(`${B}/workspaces/:ws/shared-assets/:kind/:key`, async ({ params }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      const kind = String(params.kind) as SharedAssetKind
      if (!KINDS.includes(kind)) return notFound()
      if (!permissionsOf(user, wsId).includes(readPermission(kind))) return forbidden()
      const row = find(wsId, kind, String(params.key))
      return row ? HttpResponse.json({ asset: row.summary, versions: row.versions }) : notFound()
    }),
    http.get(`${B}/workspaces/:ws/shared-assets/:kind/:key/usage`, async ({ params }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      const kind = String(params.kind) as SharedAssetKind
      if (!KINDS.includes(kind)) return notFound()
      const perms = permissionsOf(user, wsId)
      if (!perms.includes(readPermission(kind)) || !perms.includes('workflow.read')) return forbidden()
      const key = String(params.key)
      const row = find(wsId, kind, key)
      if (!row) return notFound()
      const pinned = (def: Definition | undefined): number | null => {
        if (!def) return null
        const versions = kind === 'connection'
          ? def.dependencies.connections.filter((c) => c.key === key).map((c) => c.definitionVersion)
          : (def.dependencies.assets ?? []).filter((a) => a.kind === kind && a.key === key).map((a) => a.version)
        return versions.length ? Math.min(...versions) : null
      }
      const items = [...db.workflows.values()].filter((w) => w.wsId === wsId).flatMap((w) => {
        const draftVersion = pinned(w.drafts.at(-1)?.definition)
        const release = w.releases.find((r) => r.id === w.activeReleaseId)
        const releaseVersion = pinned(release?.definition)
        if (draftVersion === null && releaseVersion === null) return []
        const latest = row.summary.latestVersion
        return [{ workflowId: w.id, workflowKey: w.key, workflowName: w.name, draftVersion, activeReleaseNumber: releaseVersion === null ? null : release!.releaseNumber, activeReleaseVersion: releaseVersion, stale: (draftVersion ?? latest) < latest || (releaseVersion ?? latest) < latest }]
      }).sort((a, b) => a.workflowName.localeCompare(b.workflowName))
      return HttpResponse.json({ kind, key, latestVersion: row.summary.latestVersion, items })
    }),
    http.put(`${B}/workspaces/:ws/shared-assets/:kind/:key/:field`, async ({ params, request }) => {
      await slow()
      const wsId = String(params.ws)
      const user = member(wsId)
      if (!user) return notFound()
      const kind = String(params.kind) as SharedAssetKind
      const field = String(params.field)
      if (!KINDS.includes(kind) || !['owner', 'archived'].includes(field)) return notFound()
      if (!permissionsOf(user, wsId).includes(managePermission(kind))) return forbidden()
      const key = String(params.key)
      const row = find(wsId, kind, key)
      if (!row) return notFound()
      const body = (await request.json()) as { ownerSubjectId?: string; archived?: boolean; expectedRevision: number }
      if (body.expectedRevision !== row.summary.revision) return conflict(row.summary.revision)
      if (field === 'owner') {
        const next = body.ownerSubjectId ? userById(body.ownerSubjectId) : undefined
        if (!next || !permissionsOf(next, wsId).includes(managePermission(kind))) return invalid('The new owner must be a person who can manage this kind of asset in this workspace.')
      }
      const now = clock.iso()
      if (kind === 'decision') {
        const d = decisions.get(`${wsId}:${key}`)!
        if (field === 'owner') d.ownerId = body.ownerSubjectId!
        else d.archived = !!body.archived
        d.revision += 1
        d.updatedAt = now
      } else if (kind === 'documentTemplate') {
        const t = documentTemplatesOf(wsId).find((x) => x.key === key)!
        if (field === 'owner') t.owner = ref(body.ownerSubjectId!)
        else t.archived = !!body.archived
        t.revision += 1
        t.updatedAt = now
      } else {
        const current = connectionHeaders.get(`${wsId}:${key}`) ?? { ownerId: row.summary.owner.id, revision: 0, archived: false, updatedAt: now }
        connectionHeaders.set(`${wsId}:${key}`, { ownerId: field === 'owner' ? body.ownerSubjectId! : current.ownerId, archived: field === 'archived' ? !!body.archived : current.archived, revision: current.revision + 1, updatedAt: now })
      }
      const updated = find(wsId, kind, key)!
      return HttpResponse.json({ asset: updated.summary, versions: updated.versions })
    }),
  ]
}
