import { delay, http, HttpResponse, type HttpHandler } from 'msw'
import type { Permission } from '@/api/types'
import { draftResource, getCurrentUser, workflowResource } from '@/mocks/handlers'
import {
  MockSourceError, approveReview, convertMigration, createMigration, migrationView, nextInProject, planProject, policyView, projectView,
  reviewView, type ConvertOutcome, type ConvertRequest, type Denial, type ProjectSource,
} from '@/mocks/migration'
import { WORKSPACES, clock, db, isMember, newId, permissionsOf, type MockMigration, type MockMigrationProject } from '@/mocks/state'

/**
 * Mock-mode migration workbench: single-file upload/convert, multi-file projects, the workspace migration-review
 * policy and the per-workflow independent review. Rules live in mocks/migration.ts; this file only speaks HTTP.
 */
const B = '*/api/v1'

function problem(status: number, code: string, title: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title, status, code, correlationId: newId(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const notFound = () => problem(404, 'NOT_FOUND', 'Not found', { detail: 'The resource does not exist or is not visible to you.' })
const denial = (d: Denial) => problem(d.status, d.code, d.title, { detail: d.detail })

function guard(ws: unknown, ...anyOf: Permission[]) {
  const id = typeof ws === 'string' ? ws : ''
  const user = getCurrentUser()
  if (!WORKSPACES.some((w) => w.id === id) || !isMember(user, id)) return { ok: false as const, res: notFound() }
  const perms = permissionsOf(user, id)
  if (anyOf.length > 0 && !anyOf.some((p) => perms.includes(p))) return { ok: false as const, res: problem(403, 'FORBIDDEN', 'Forbidden', { detail: 'You do not have permission to do this.' }) }
  return { ok: true as const, user, perms, ws: id }
}

async function json<T>(request: Request): Promise<T> {
  try { return (await request.json()) as T } catch { return {} as T }
}

const migrationOf = (ws: string, id: unknown): MockMigration | undefined => {
  const migration = typeof id === 'string' ? db.migrations.get(id) : undefined
  return migration && migration.wsId === ws ? migration : undefined
}
const projectOf = (ws: string, id: unknown): MockMigrationProject | undefined => {
  const project = typeof id === 'string' ? db.migrationProjects.get(id) : undefined
  return project && project.wsId === ws ? project : undefined
}

function convertedBody(outcome: Extract<ConvertOutcome, { ok: true }>, perms: Permission[]) {
  return { workflow: workflowResource(outcome.workflow, perms), draft: draftResource(outcome.workflow), report: outcome.report }
}

function failure(outcome: Extract<ConvertOutcome, { ok: false }>) {
  return problem(outcome.status, outcome.code, outcome.title, { ...(outcome.detail ? { detail: outcome.detail } : {}), ...outcome.extra })
}

export function createMigrationHandlers(): HttpHandler[] {
  const slow = async () => { if (db.latencyMs > 0) await delay(db.latencyMs) }
  return [
    http.post(`${B}/workspaces/:ws/migrations`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const input = await json<{ fileName?: string; content?: string; sourceFormat?: string }>(request)
      if (!input.fileName || !input.content) return problem(400, 'MALFORMED_REQUEST', 'Bad request', { detail: 'fileName and content are required.' })
      if (input.sourceFormat && !['bpmn', 'dmn', 'camundaForm'].includes(input.sourceFormat)) return problem(422, 'SOURCE_INVALID', 'Source not supported', { detail: 'Mock mode inventories BPMN, DMN and Camunda forms only; Pega archive, native rule archive and XPDL conversion require the real backend.' })
      try {
        const { migration } = createMigration(g.ws, input.fileName, input.content, input.sourceFormat)
        return HttpResponse.json(migrationView(migration), { status: 201 })
      } catch (cause) {
        if (cause instanceof MockSourceError) return problem(422, 'SOURCE_INVALID', 'Source rejected', { detail: cause.message })
        throw cause
      }
    }),
    // Mock mode cannot read PDFs: the Pega Blueprint import needs the real backend's isolated extraction worker.
    http.post(`${B}/workspaces/:ws/migrations/blueprints`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      return problem(422, 'SOURCE_INVALID', 'Source not supported', { detail: 'Mock mode cannot read PDF files. Pega Blueprint import requires the real backend.' })
    }),
    http.get(`${B}/workspaces/:ws/migrations/:id`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const migration = migrationOf(g.ws, params.id)
      return migration ? HttpResponse.json(migrationView(migration)) : notFound()
    }),
    // Native Pega rule archives (docs/build/S112) are refused at upload in mock mode, so no mock job has this view.
    http.get(`${B}/workspaces/:ws/migrations/:id/pega-rules`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      return notFound()
    }),
    http.post(`${B}/workspaces/:ws/migrations/:id/convert`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const migration = migrationOf(g.ws, params.id)
      if (!migration) return notFound()
      const outcome = convertMigration(migration, g.user, await json<ConvertRequest>(request))
      return outcome.ok ? HttpResponse.json(convertedBody(outcome, g.perms), { status: 201 }) : failure(outcome)
    }),

    // Projects ---------------------------------------------------------------------------------------------------------------
    http.post(`${B}/workspaces/:ws/migrations/projects`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const input = await json<{ name?: string; files?: ProjectSource[] }>(request)
      if (!input.name?.trim() || input.name.length > 200 || !Array.isArray(input.files) || input.files.length < 1 || input.files.length > 10)
        return problem(400, 'MALFORMED_REQUEST', 'Bad request', { detail: 'A project needs a name and 1 to 10 files.' })
      try {
        return HttpResponse.json(projectView(planProject(g.ws, input.name, input.files)), { status: 201 })
      } catch (cause) {
        if (cause instanceof MockSourceError) return problem(422, 'SOURCE_INVALID', 'Source rejected', { detail: cause.message })
        throw cause
      }
    }),
    http.get(`${B}/workspaces/:ws/migrations/projects/:pid`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const project = projectOf(g.ws, params.pid)
      return project ? HttpResponse.json(projectView(project)) : notFound()
    }),
    http.post(`${B}/workspaces/:ws/migrations/projects/:pid/convert-next`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.edit')
      if (!g.ok) return g.res
      const project = projectOf(g.ws, params.pid)
      if (!project) return notFound()
      const input = await json<{ expectedMigrationId?: string; mappings?: ConvertRequest }>(request)
      if (!input.expectedMigrationId || !input.mappings) return problem(400, 'MALFORMED_REQUEST', 'Bad request', { detail: 'expectedMigrationId and mappings are required.' })
      if (project.issues.length > 0) return problem(422, 'MIGRATION_BLOCKED', 'Migration blocked', { detail: 'Resolve project dependency findings before converting files.', projectIssues: project.issues })
      const next = nextInProject(project)
      if (!next || next !== input.expectedMigrationId) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'Conflict', { detail: 'Project conversion order changed or every file is already converted.' })
      const outcome = convertMigration(db.migrations.get(next)!, g.user, input.mappings)
      return outcome.ok ? HttpResponse.json({ project: projectView(project), converted: convertedBody(outcome, g.perms) }, { status: 201 }) : failure(outcome)
    }),

    // Governance -------------------------------------------------------------------------------------------------------------
    http.get(`${B}/workspaces/:ws/migration-policy`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workspace.manage-access')
      return g.ok ? HttpResponse.json(policyView(g.ws)) : g.res
    }),
    http.put(`${B}/workspaces/:ws/migration-policy`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workspace.manage-access')
      if (!g.ok) return g.res
      const input = await json<{ required?: unknown; expectedVersion?: unknown; reason?: unknown }>(request)
      if (typeof input.required !== 'boolean' || !Number.isInteger(input.expectedVersion) || (input.expectedVersion as number) < 0 || typeof input.reason !== 'string' || !input.reason.trim() || input.reason.length > 1000)
        return problem(400, 'MALFORMED_REQUEST', 'Bad request', { detail: 'required, expectedVersion and a reason of 1 to 1000 characters are required.' })
      const current = policyView(g.ws)
      if (current.version !== input.expectedVersion) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'Conflict', { detail: 'The migration policy changed. Reload settings.', currentVersion: current.version })
      db.migrationPolicies.set(g.ws, { required: input.required, version: current.version + 1, reason: input.reason, updatedAt: clock.iso() })
      return HttpResponse.json(policyView(g.ws))
    }),
    http.get(`${B}/workspaces/:ws/workflows/:id/migration-review`, async ({ params }) => {
      await slow()
      const g = guard(params.ws, 'workflow.read')
      if (!g.ok) return g.res
      const wf = db.workflows.get(String(params.id))
      return wf && wf.wsId === g.ws ? HttpResponse.json(reviewView(wf, g.user)) : notFound()
    }),
    http.post(`${B}/workspaces/:ws/workflows/:id/migration-review`, async ({ params, request }) => {
      await slow()
      const g = guard(params.ws, 'workflow.publish')
      if (!g.ok) return g.res
      const wf = db.workflows.get(String(params.id))
      if (!wf || wf.wsId !== g.ws) return notFound()
      const input = await json<{ draftRevision?: unknown; expectedVersion?: unknown; reason?: unknown }>(request)
      if (!Number.isInteger(input.draftRevision) || (input.draftRevision as number) < 1 || !Number.isInteger(input.expectedVersion) || (input.expectedVersion as number) < 0 || typeof input.reason !== 'string' || !input.reason.trim() || input.reason.length > 1000)
        return problem(400, 'MALFORMED_REQUEST', 'Bad request', { detail: 'draftRevision, expectedVersion and a reason of 1 to 1000 characters are required.' })
      const denied = approveReview(wf, g.user, input.draftRevision as number, input.expectedVersion as number, input.reason)
      return denied ? denial(denied) : HttpResponse.json(reviewView(wf, g.user))
    }),
  ]
}
