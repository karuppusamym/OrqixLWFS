import { http, HttpResponse, type HttpHandler } from 'msw'
import type { BundlePromotionPlan, GitConnection, GitSyncEvent, ReleaseBundle } from '@/api/types'
import { releaseFull } from './handlers'
import { makeRelease } from './seed'
import { clock, db, isMember, newId, permissionsOf, USERS, WORKSPACES, type MockUser, type MockWorkflow } from './state'

/**
 * S147 mock backend: the Git connection, push, import and publish from a reviewed commit (CG-S01) and release bundles
 * (CG-S02), for mock mode and unit tests. A simplified double: one review branch per workflow, a commit is "reviewed"
 * once a test calls approveMockCommit, and bundles list the workflow, its rule tables and request form.
 */
const B = '*/api/v1/workspaces/:ws'

const connections = new Map<string, GitConnection>()
const events = new Map<string, GitSyncEvent[]>()
const bundles = new Map<string, ReleaseBundle[]>()
const commits = new Map<string, { wfId: string; revision: number }>()
const approved = new Set<string>()

export function resetGitBundlesMock() {
  for (const m of [connections, events, bundles, commits]) m.clear()
  approved.clear()
}

export function seedGitConnection(ws: string, connection: Partial<GitConnection> = {}) {
  connections.set(ws, {
    provider: 'github', apiUrl: 'https://api.github.com', repository: 'acme/workflows', baseBranch: 'main', basePath: 'orqix',
    credentialRef: 'git-token', enabled: true, version: 1, updatedBy: { id: USERS[0]!.id, displayName: USERS[0]!.displayName },
    updatedAt: clock.iso(), ...connection,
  })
}

/** A bundle for the workflow's active release, published by the first user (for promotion tests). */
export function seedReleaseBundle(wfId: string): ReleaseBundle {
  const wf = db.workflows.get(wfId)!
  const release = wf.releases.find((r) => r.id === wf.activeReleaseId) ?? wf.releases[wf.releases.length - 1]!
  const bundle: ReleaseBundle = {
    id: newId(), workflowId: wf.id, releaseId: release.id, releaseNumber: release.releaseNumber, bundleHash: hex(64),
    components: [
      { type: 'workflow', key: wf.key, name: wf.name, version: `release ${release.releaseNumber}`, sha256: release.semanticHash, ref: release.id },
      { type: 'connection', key: 'procurement', name: null, version: '1', sha256: hex(64), ref: null },
    ],
    source: null, publishedBy: { id: USERS[0]!.id, displayName: USERS[0]!.displayName }, publishedAt: clock.iso(),
  }
  bundles.set(wf.id, [bundle, ...(bundles.get(wf.id) ?? [])])
  return bundle
}

export function approveMockCommit(commit: string) {
  approved.add(commit)
}

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: crypto.randomUUID(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const hex = (length: number) => Array.from({ length }, () => Math.floor(Math.random() * 16).toString(16)).join('')

export function createGitBundleHandlers(currentUser: () => MockUser): HttpHandler[] {
  const visible = (ws: string) => WORKSPACES.some((w) => w.id === ws) && isMember(currentUser(), ws)
  const has = (ws: string, permission: string) => (permissionsOf(currentUser(), ws) as string[]).includes(permission)
  const refused = (ws: string, permission: string) =>
    !visible(ws) ? problem(404, 'NOT_FOUND', 'Not found') : !has(ws, permission) ? problem(403, 'FORBIDDEN', 'You are not allowed to do this') : null
  const workflow = (ws: string, id: string): MockWorkflow | undefined => {
    const wf = db.workflows.get(id)
    return wf && wf.wsId === ws ? wf : undefined
  }
  const actor = () => ({ id: currentUser().id, displayName: currentUser().displayName })
  const state = (ws: string) => {
    const c = connections.get(ws)
    return {
      configured: c !== undefined,
      connection: c ? { ...c, credentialRef: has(ws, 'connection.manage') ? c.credentialRef : null } : null,
      providers: ['azure-devops', 'bitbucket', 'github', 'gitlab'],
    }
  }
  const record = (wfId: string, event: Omit<GitSyncEvent, 'id' | 'actor' | 'occurredAt'>) => {
    const list = events.get(wfId) ?? []
    list.unshift({ id: newId(), actor: actor(), occurredAt: clock.iso(), ...event })
    events.set(wfId, list)
  }
  const bundleOf = (wf: MockWorkflow, release: MockWorkflow['releases'][number]): ReleaseBundle => ({
    id: newId(), workflowId: wf.id, releaseId: release.id, releaseNumber: release.releaseNumber, bundleHash: hex(64),
    components: [
      { type: 'workflow', key: wf.key, name: wf.name, version: `release ${release.releaseNumber}`, sha256: release.semanticHash, ref: release.id },
      ...release.definition.decisions.map((d) => ({ type: 'ruleTable' as const, key: d.key, name: null, version: `release ${release.releaseNumber}`, sha256: hex(64), ref: null })),
      { type: 'requestForm', key: 'request', name: 'Request form', version: `release ${release.releaseNumber}`, sha256: hex(64), ref: null },
    ],
    source: null, publishedBy: actor(), publishedAt: clock.iso(),
  })
  const publishRelease = (wf: MockWorkflow, revision: number) => {
    const draft = wf.drafts.find((d) => d.revision === revision)
    if (!draft) return null
    const number = Math.max(0, ...wf.releases.map((r) => r.releaseNumber)) + 1
    const rel = makeRelease(wf.id, number, draft.revision, draft.definition, currentUser().id, null)
    wf.releases.push(rel)
    wf.activeReleaseId = rel.id
    wf.version += 1
    wf.updatedAt = clock.iso()
    return rel
  }

  return [
    http.get(`${B}/git-connection`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'connection.read') ?? HttpResponse.json(state(ws))
    }),
    http.put(`${B}/git-connection`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'connection.manage')
      if (denied) return denied
      const body = (await request.json()) as Partial<GitConnection> & { expectedVersion: number }
      const current = connections.get(ws)
      if ((current?.version ?? 0) !== body.expectedVersion) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'Changed')
      if (!body.repository || !body.repository.includes('/')) {
        return problem(422, 'VALIDATION_FAILED', 'Invalid', { detail: 'Use the form owner/repository.', fieldErrors: [{ pointer: '/repository', code: 'pattern', message: 'Use the form owner/repository.' }] })
      }
      connections.set(ws, {
        provider: body.provider ?? 'github', apiUrl: body.apiUrl || 'https://api.github.com', repository: body.repository,
        baseBranch: body.baseBranch || 'main', basePath: body.basePath || 'orqix', credentialRef: body.credentialRef ?? '',
        enabled: body.enabled ?? true, version: (current?.version ?? 0) + 1, updatedBy: actor(), updatedAt: clock.iso(),
      })
      return HttpResponse.json(state(ws))
    }),
    http.delete(`${B}/git-connection`, ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'connection.manage')
      if (denied) return denied
      const current = connections.get(ws)
      if (!current) return problem(404, 'NOT_FOUND', 'Not found')
      if (Number(new URL(request.url).searchParams.get('expectedVersion')) !== current.version) return problem(409, 'CONFIGURATION_VERSION_CONFLICT', 'Changed')
      connections.delete(ws)
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${B}/git-connection/check`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'connection.manage') ?? HttpResponse.json({ reachable: true, defaultBranch: 'main', baseBranchExists: true, message: 'Connected.' })
    }),
    http.post(`${B}/workflows/:id/git/push`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.export')
      if (denied) return denied
      const wf = workflow(ws, String(params.id))
      const conn = connections.get(ws)
      if (!wf || !conn) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { draftRevision: number }
      const commit = hex(40)
      const branch = `orqix/${wf.key}`
      commits.set(commit, { wfId: wf.id, revision: body.draftRevision })
      const pullRequest = { id: '1', url: `https://github.example/${conn.repository}/pull/1`, state: 'open' as const, sourceBranch: branch, headCommit: commit, approvedBy: [] }
      record(wf.id, { direction: 'push', branch, commit, contentHash: hex(64), draftRevision: body.draftRevision, releaseId: null, pullRequest: '1', pullRequestUrl: pullRequest.url })
      return HttpResponse.json({ branch, commit, unchanged: false, contentHash: hex(64), draftRevision: body.draftRevision, changedFiles: [`orqix/workflows/${wf.key}/workflow.json`], pullRequest })
    }),
    http.get(`${B}/workflows/:id/git/import-preview`, ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.edit')
      if (denied) return denied
      const wf = workflow(ws, String(params.id))
      if (!wf) return problem(404, 'NOT_FOUND', 'Not found')
      const branch = new URL(request.url).searchParams.get('branch') ?? ''
      const latest = wf.drafts[wf.drafts.length - 1]!
      return HttpResponse.json({
        branch, commit: hex(40), contentHash: hex(64), currentDraftRevision: latest.revision, draftContentHash: hex(64), identical: false,
        testsIncluded: false, changes: [{ kind: 'changed', area: 'step', title: 'Reviewed in Git (manager)' }], issues: [],
      })
    }),
    http.post(`${B}/workflows/:id/git/import`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.edit')
      if (denied) return denied
      const wf = workflow(ws, String(params.id))
      if (!wf) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { branch: string; commit: string; expectedDraftRevision: number }
      const latest = wf.drafts[wf.drafts.length - 1]!
      if (latest.revision !== body.expectedDraftRevision) return problem(412, 'STALE_REVISION', 'Stale')
      const revision = latest.revision + 1
      wf.drafts.push({ revision, savedAt: clock.iso(), savedById: currentUser().id, definition: structuredClone(latest.definition) })
      commits.set(body.commit, { wfId: wf.id, revision })
      record(wf.id, { direction: 'pull', branch: body.branch, commit: body.commit, contentHash: hex(64), draftRevision: revision, releaseId: null, pullRequest: null, pullRequestUrl: null })
      return HttpResponse.json({ draftRevision: revision, unchanged: false, commit: body.commit, contentHash: hex(64), testsChanged: false, testSuiteVersion: 0, changes: [] })
    }),
    http.post(`${B}/workflows/:id/git/publish`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.publish')
      if (denied) return denied
      const wf = workflow(ws, String(params.id))
      const conn = connections.get(ws)
      if (!wf || !conn) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { draftRevision: number; commit: string; pullRequest: string; asBundle?: boolean }
      if (!approved.has(body.commit)) return problem(422, 'GIT_REVIEW_REQUIRED', 'The Git commit has not been reviewed and approved')
      const pinned = commits.get(body.commit)
      if (!pinned || pinned.wfId !== wf.id || pinned.revision !== body.draftRevision) return problem(409, 'GIT_CONTENT_MISMATCH', 'The Git commit does not match the draft')
      const rel = publishRelease(wf, body.draftRevision)
      if (!rel) return problem(404, 'NOT_FOUND', 'Not found')
      const gitSource = {
        provider: conn.provider, repository: conn.repository, branch: `orqix/${wf.key}`, commit: body.commit, contentHash: hex(64),
        pullRequest: body.pullRequest, pullRequestUrl: null, reviewState: 'open' as const, approvedBy: ['rita-reviewer'], verifiedAt: clock.iso(),
      }
      let bundle: ReleaseBundle | null = null
      if (body.asBundle) {
        bundle = bundleOf(wf, rel)
        bundles.set(wf.id, [bundle, ...(bundles.get(wf.id) ?? [])])
      }
      record(wf.id, { direction: 'publish', branch: gitSource.branch, commit: body.commit, contentHash: gitSource.contentHash, draftRevision: body.draftRevision, releaseId: rel.id, pullRequest: body.pullRequest, pullRequestUrl: null })
      const release = releaseFull(wf, rel)
      return HttpResponse.json({ release: { ...release, manifest: { ...release.manifest, gitSource } }, gitSource, bundle, replayed: false }, { status: 201 })
    }),
    http.get(`${B}/workflows/:id/git/events`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workflow.read') ?? HttpResponse.json(events.get(String(params.id)) ?? [])
    }),
    http.get(`${B}/workflows/:id/bundles`, ({ params }) => {
      const ws = String(params.ws)
      return refused(ws, 'workflow.read') ?? HttpResponse.json({ items: bundles.get(String(params.id)) ?? [] })
    }),
    http.post(`${B}/workflows/:id/bundles`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.publish')
      if (denied) return denied
      const wf = workflow(ws, String(params.id))
      if (!wf) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { draftRevision: number }
      const rel = publishRelease(wf, body.draftRevision)
      if (!rel) return problem(404, 'NOT_FOUND', 'Not found')
      const bundle = bundleOf(wf, rel)
      bundles.set(wf.id, [bundle, ...(bundles.get(wf.id) ?? [])])
      return HttpResponse.json({ release: releaseFull(wf, rel), bundle, replayed: false }, { status: 201 })
    }),
    http.get(`${B}/workflows/:id/releases/:rid/bundle`, ({ params }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.read')
      if (denied) return denied
      const found = (bundles.get(String(params.id)) ?? []).find((b) => b.releaseId === String(params.rid))
      return found ? HttpResponse.json(found) : problem(404, 'NOT_FOUND', 'Not found')
    }),
    http.post(`${B}/bundles/:bundleId/promotion-check`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.export')
      if (denied) return denied
      const bundle = [...bundles.values()].flat().find((b) => b.id === String(params.bundleId))
      if (!bundle) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { targetWorkspaceId: string }
      if (!visible(body.targetWorkspaceId)) return problem(404, 'NOT_FOUND', 'Not found')
      const plan: BundlePromotionPlan = {
        promotable: true, bundleHash: bundle.bundleHash, sourceStage: 'test', targetStage: 'production', approvalRequired: false,
        targetWorkflow: { exists: false, id: null, draftRevision: null },
        items: bundle.components.map((c) => ({ type: c.type, key: c.key, name: c.name, sourceVersion: c.version, action: c.type === 'workflow' ? 'create' : 'copy', targetVersion: null, problem: null })),
        issues: [],
      }
      return HttpResponse.json(plan)
    }),
    http.post(`${B}/bundles/:bundleId/promote`, async ({ params, request }) => {
      const ws = String(params.ws)
      const denied = refused(ws, 'workflow.export')
      if (denied) return denied
      const bundle = [...bundles.values()].flat().find((b) => b.id === String(params.bundleId))
      if (!bundle) return problem(404, 'NOT_FOUND', 'Not found')
      const body = (await request.json()) as { targetWorkspaceId: string }
      return HttpResponse.json({
        id: newId(), bundleHash: bundle.bundleHash, targetWorkspaceId: body.targetWorkspaceId, targetWorkflowId: newId(), draftRevision: 1,
        createdWorkflow: true, items: bundle.components.map((c) => ({ type: c.type, key: c.key, name: c.name, sourceVersion: c.version, action: 'copy', targetVersion: null, problem: null })),
      }, { status: 201 })
    }),
  ]
}
