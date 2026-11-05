import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import type { MockUser } from './state'
import { addCreatedWorkspace, clock, db, newId, ORG_DEMO, SEEDED_ORGANIZATION, USERS, WORKSPACES, type MockOrganization } from './state'

/**
 * Platform administration for mock mode and unit tests (contracts/openapi.yaml, tag Administration): list and create
 * organizations and workspaces. Mirrors AdminService: platform administrators only (403 otherwise), keys unique (409
 * DUPLICATE_KEY), self-approval only in test workspaces, and the initial administrators get the Workspace admin role.
 */
const B = '*/api/v1/admin'
const KEY = /^[a-z][a-z0-9-]{0,62}[a-z0-9]$/
const SEEDED_WORKSPACE_CREATED_AT = '2026-10-01T09:00:00Z'

type PrincipalRef = components['schemas']['PrincipalRef']

function problem(status: number, code: string, detail: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: `https://orqix.example/problems/${code.toLowerCase().replace(/_/g, '-')}`, title: detail, status, code, detail, correlationId: newId(), ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const forbidden = () => problem(403, 'FORBIDDEN', 'Only platform administrators can do this.')
const malformed = (pointer: string) => problem(400, 'MALFORMED_REQUEST', 'The request does not match the operation schema.', { fieldErrors: [{ pointer, code: 'invalid', message: 'Invalid value' }] })
const invalid = (pointer: string, message: string) => problem(422, 'VALIDATION_FAILED', message, { fieldErrors: [{ pointer, code: 'enum', message }] })

/** Newest first, like the server (created_at DESC); the seeded rows are the oldest. */
function organizations(): MockOrganization[] {
  return [...(db.platform?.organizations ?? [])].reverse().concat(SEEDED_ORGANIZATION)
}

function workspaces(): components['schemas']['WorkspaceAdminView'][] {
  const created = [...(db.platform?.workspaces ?? [])].reverse().map((w) => ({
    id: w.id, organizationId: w.organizationId, key: w.key, name: w.name, isTest: w.isTest, allowSelfApprovalWorkflows: w.allowSelfApprovalWorkflows, createdAt: w.createdAt,
  }))
  const createdIds = new Set(created.map((w) => w.id))
  const seeded = WORKSPACES.filter((w) => !createdIds.has(w.id)).map((w) => ({
    id: w.id, organizationId: ORG_DEMO, key: w.key, name: w.name, isTest: w.isTest, allowSelfApprovalWorkflows: w.allowSelfApprovalWorkflows, createdAt: SEEDED_WORKSPACE_CREATED_AT,
  }))
  return [...created, ...seeded]
}

function page<T>(items: T[], url: URL): { items: T[]; nextCursor: string | null } {
  const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') ?? 25) || 25))
  const offset = Math.max(0, Number.parseInt(url.searchParams.get('cursor') ?? '0', 10) || 0)
  return { items: items.slice(offset, offset + limit), nextCursor: offset + limit < items.length ? String(offset + limit) : null }
}

async function body<T>(request: Request): Promise<Partial<T>> {
  try {
    return ((await request.json()) ?? {}) as Partial<T>
  } catch {
    return {}
  }
}

export function createPlatformHandlers(currentUser: () => MockUser): HttpHandler[] {
  return [
    http.get(`${B}/organizations`, ({ request }) => {
      if (!currentUser().platformAdmin) return forbidden()
      return HttpResponse.json(page(organizations(), new URL(request.url)))
    }),
    http.post(`${B}/organizations`, async ({ request }) => {
      if (!currentUser().platformAdmin) return forbidden()
      const input = await body<{ key: string; name: string }>(request)
      if (typeof input.key !== 'string' || !KEY.test(input.key)) return malformed('/key')
      if (typeof input.name !== 'string' || !input.name.trim() || input.name.length > 200) return malformed('/name')
      if (organizations().some((o) => o.key === input.key)) return problem(409, 'DUPLICATE_KEY', 'An organization with this key already exists.')
      const created: MockOrganization = { id: newId(), key: input.key, name: input.name, createdAt: clock.iso() }
      ;(db.platform ??= { organizations: [], workspaces: [] }).organizations.push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.get(`${B}/workspaces`, ({ request }) => {
      if (!currentUser().platformAdmin) return forbidden()
      return HttpResponse.json(page(workspaces(), new URL(request.url)))
    }),
    http.post(`${B}/workspaces`, async ({ request }) => {
      const user = currentUser()
      if (!user.platformAdmin) return forbidden()
      const input = await body<components['schemas']['CreateWorkspaceRequest']>(request)
      if (typeof input.organizationId !== 'string') return malformed('/organizationId')
      if (typeof input.key !== 'string' || !KEY.test(input.key)) return malformed('/key')
      if (typeof input.name !== 'string' || !input.name.trim() || input.name.length > 200) return malformed('/name')
      if (typeof input.isTest !== 'boolean') return malformed('/isTest')
      const admins: PrincipalRef[] = Array.isArray(input.initialAdmins) ? input.initialAdmins : []
      if (admins.length < 1 || admins.length > 20) return malformed('/initialAdmins')
      const organization = organizations().find((o) => o.id === input.organizationId)
      if (!organization) return invalid('/organizationId', 'Unknown organization.')
      if (input.allowSelfApprovalWorkflows && !input.isTest) return invalid('/allowSelfApprovalWorkflows', 'Self-approval workflows are only allowed in test workspaces.')
      for (const admin of admins) {
        if (admin.type !== 'subject' && admin.type !== 'idpGroup') return invalid('/initialAdmins', 'Use subject or idpGroup principals.')
        if (admin.type === 'subject' && !/^[0-9a-f-]{36}$/i.test(admin.value)) return invalid('/initialAdmins', 'Subject principals are subject ids.')
      }
      if (WORKSPACES.some((w) => w.key === input.key)) return problem(409, 'DUPLICATE_KEY', 'A workspace with this key already exists.')
      const now = clock.iso()
      const id = newId()
      addCreatedWorkspace({ id, key: input.key, name: input.name, organizationName: organization.name, organizationId: organization.id, isTest: input.isTest, allowSelfApprovalWorkflows: !!input.allowSelfApprovalWorkflows, createdAt: now })
      // The first administrators are ordinary role bindings, so Settings → Members and groups lists and removes them.
      const edits = (db.accessEdits ??= { roleBindings: [], grants: [], removed: [], groups: [] })
      const seen = new Set<string>()
      for (const admin of admins) {
        const ref = `${admin.type}:${admin.value}`
        if (seen.has(ref)) continue
        seen.add(ref)
        const displayName = admin.type === 'subject' ? USERS.find((u) => u.id === admin.value)?.displayName : admin.value
        edits.roleBindings.push({ id: newId(), wsId: id, role: 'WORKSPACE_ADMIN', principal: { type: admin.type, value: admin.value, ...(displayName ? { displayName } : {}) }, source: 'manual', createdAt: now })
      }
      return HttpResponse.json({ id, organizationId: organization.id, key: input.key, name: input.name, isTest: input.isTest, allowSelfApprovalWorkflows: !!input.allowSelfApprovalWorkflows, createdAt: now }, { status: 201 })
    }),
  ]
}
