import { http, HttpResponse, type HttpHandler } from 'msw'
import bridge from '@/features/extensions/bridge.js?raw'
import { buildFrameDocument, embeddable } from '@/features/extensions/frame'
import type { ChainAction, ExtensionInfo } from '@/features/pages/model'
import { STEP_ACTIONS, TASK_FIELDS } from '@/features/pages/model'
import { clock, isMember, newId, permissionsOf, userById, userByName, type MockUser, WS_PURCHASING } from './state'

/** Mock of the S111 extension registry. Mirrors the server's permission, approval and capability rules. */
export type ExtensionStatus = ExtensionInfo['status']
interface StoredVersion {
  version: number
  source: string
  sha256: string
  capabilities: { bindings: string[]; actions: ChainAction[] }
  height: number
  status: ExtensionStatus
  submittedBy: string
  submittedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}
interface StoredExtension { id: string; ws: string; name: string; description: string | null; createdAt: string; versions: StoredVersion[] }

export const SEEDED_EXTENSION_ID = '99999999-9999-4999-8999-999999999999'
export const SEEDED_EXTENSION_SOURCE = `(function () {
  var root = document.getElementById('root');
  var note = document.createElement('p');
  note.setAttribute('role', 'status');
  orqix.onData(function (data, chains) {
    root.textContent = '';
    var title = document.createElement('strong');
    title.textContent = data['task.requestTitle'] || 'No request selected';
    root.appendChild(title);
    var amount = document.createElement('p');
    amount.textContent = data['request.amount'] != null ? 'Amount: ' + data['request.amount'] : 'Amount not shared';
    root.appendChild(amount);
    chains.forEach(function (chain) {
      var button = document.createElement('button');
      button.textContent = chain.label;
      button.onclick = function () {
        button.disabled = true;
        orqix.run(chain.id).then(function (result) {
          button.disabled = false;
          note.textContent = result && result.completed ? 'Done' : 'Not completed';
        });
      };
      root.appendChild(button);
    });
    root.appendChild(note);
    orqix.resize(document.body.scrollHeight + 16);
  });
})();`

const extensions = new Map<string, StoredExtension>()
const clone = <T,>(value: T): T => structuredClone(value)
const problem = (status: number, code: string, detail: string) => HttpResponse.json({ status, code, title: detail, detail }, { status, headers: { 'Content-Type': 'application/problem+json' } })
const base = '*/api/v1/workspaces/:workspaceId/ui-extensions'

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

export function resetMockExtensions(): void {
  extensions.clear()
  extensions.set(SEEDED_EXTENSION_ID, {
    id: SEEDED_EXTENSION_ID, ws: WS_PURCHASING, name: 'Request badge', description: 'Shows the request title and amount with the granted actions.', createdAt: clock.iso(),
    versions: [{ version: 1, source: SEEDED_EXTENSION_SOURCE, sha256: 'f'.repeat(64), capabilities: { bindings: ['task.requestTitle', 'request.amount'], actions: ['claim', 'approve', 'reject', 'navigate'] }, height: 180, status: 'approved', submittedBy: userByName('dana.designer').id, submittedAt: clock.iso(), decidedBy: userByName('wade.admin').id, decidedAt: clock.iso(), decisionNote: 'Reviewed: renders text only and uses the bridge.' }],
  })
}
export function snapshotMockExtensions(): StoredExtension[] { return clone([...extensions.values()]) }
export function restoreMockExtensions(value: unknown): void {
  if (!Array.isArray(value)) { resetMockExtensions(); return }
  extensions.clear()
  for (const item of value as StoredExtension[]) if (item && typeof item.id === 'string' && Array.isArray(item.versions)) extensions.set(item.id, clone(item))
}

/** Extension facts for page validation and runtime in the mock. */
export function mockExtensionInfo(ws: string, id: string, version: number): (ExtensionInfo & { sha256: string }) | null {
  const extension = extensions.get(id)
  const found = extension?.ws === ws ? extension.versions.find(item => item.version === version) : undefined
  return found && extension ? { id, version, name: extension.name, status: found.status, sha256: found.sha256, capabilities: clone(found.capabilities), height: found.height } : null
}

/** Mock-mode stand-in for the server frame endpoint, with the same access rule. */
export async function mockFrameDocument(ws: string, id: string, version: number, user?: MockUser): Promise<string> {
  const { getCurrentUser } = await import('./handlers')
  const caller = user ?? getCurrentUser()
  const found = extensions.get(id)?.ws === ws ? extensions.get(id)!.versions.find(item => item.version === version) : undefined
  const permissions = permissionsOf(caller, ws)
  const reviewer = permissions.includes('workflow.edit') || permissions.includes('workspace.manage-access')
  if (!found || !isMember(caller, ws) || !(found.status === 'approved' || found.status === 'pending' && reviewer)) throw new Error('This extension version is not available.')
  return buildFrameDocument(bridge, found.source)
}

function subject(id: string | null) {
  if (!id) return null
  const user = userById(id)
  return { id, displayName: user?.displayName ?? 'Unknown user' }
}
function summary(version: StoredVersion) {
  return { version: version.version, status: version.status, sha256: version.sha256, capabilities: clone(version.capabilities), height: version.height, submittedBy: subject(version.submittedBy)!, submittedAt: version.submittedAt, decidedBy: subject(version.decidedBy), decidedAt: version.decidedAt, decisionNote: version.decisionNote }
}
function detail(extension: StoredExtension, version: StoredVersion) {
  return { extensionId: extension.id, name: extension.name, source: version.source, ...summary(version) }
}
function listed(extension: StoredExtension) {
  return { id: extension.id, name: extension.name, description: extension.description, createdAt: extension.createdAt, versions: [...extension.versions].reverse().map(summary) }
}

export function capabilityProblem(capabilities: unknown): string | null {
  if (!capabilities || typeof capabilities !== 'object') return 'Declare capabilities with bindings and actions.'
  const { bindings, actions } = capabilities as { bindings?: unknown; actions?: unknown }
  if (!Array.isArray(bindings) || bindings.length > 20 || !Array.isArray(actions) || actions.length > 7) return 'Declare at most 20 bindings and 7 actions.'
  if (new Set(bindings).size !== bindings.length || bindings.some(binding => typeof binding !== 'string' || !/^(task|request|case)\.[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(binding) || binding.startsWith('task.') && !(TASK_FIELDS as readonly string[]).includes(binding.slice(5)))) return 'Bindings must be distinct task summary, request or case fields.'
  if (new Set(actions).size !== actions.length || actions.some(action => !STEP_ACTIONS.includes(action as ChainAction))) return 'Actions must be distinct allowlisted chain actions.'
  return null
}

export function createExtensionHandlers(currentUser: () => MockUser): HttpHandler[] {
  const access = (ws: string) => {
    const user = currentUser()
    return { user, member: isMember(user, ws), permissions: permissionsOf(user, ws) }
  }
  const find = (ws: string, id: string) => {
    const extension = extensions.get(id)
    return extension?.ws === ws ? extension : null
  }
  return [
    http.get(base, ({ params }) => {
      const ws = String(params.workspaceId), { member, permissions } = access(ws)
      if (!member) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissions.includes('workflow.read') && !permissions.includes('workspace.manage-access')) return problem(403, 'FORBIDDEN', 'Forbidden')
      return HttpResponse.json({ items: [...extensions.values()].filter(item => item.ws === ws).sort((a, b) => a.name.localeCompare(b.name)).map(listed), canApprove: permissions.includes('workspace.manage-access') })
    }),
    http.post(base, async ({ params, request }) => {
      const ws = String(params.workspaceId), { member, permissions } = access(ws)
      if (!member) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissions.includes('workflow.edit')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const body = await request.json() as { name?: string; description?: string }
      const name = body.name ?? ''
      if (!name.trim() || name !== name.trim() || name.length > 80) return problem(422, 'VALIDATION_FAILED', 'Give the extension a trimmed name of at most 80 characters.')
      if ([...extensions.values()].some(item => item.ws === ws && item.name.toLowerCase() === name.toLowerCase())) return problem(409, 'DUPLICATE_KEY', 'An extension with this name already exists.')
      const extension: StoredExtension = { id: newId(), ws, name, description: body.description?.trim() || null, createdAt: clock.iso(), versions: [] }
      extensions.set(extension.id, extension)
      return HttpResponse.json(listed(extension), { status: 201 })
    }),
    http.post(`${base}/:extensionId/versions`, async ({ params, request }) => {
      const ws = String(params.workspaceId), { user, member, permissions } = access(ws)
      if (!member) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissions.includes('workflow.edit')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const extension = find(ws, String(params.extensionId))
      if (!extension) return problem(404, 'NOT_FOUND', 'Not found')
      const body = await request.json() as { source?: string; capabilities?: unknown; height?: number }
      const source = body.source ?? ''
      if (!source.trim() || new TextEncoder().encode(source).length > 65_536) return problem(422, 'VALIDATION_FAILED', 'The bundle source must contain 1 to 65536 bytes.')
      if (!embeddable(source)) return problem(422, 'VALIDATION_FAILED', 'The bundle source cannot contain script or comment markup.')
      const capability = capabilityProblem(body.capabilities)
      if (capability) return problem(422, 'VALIDATION_FAILED', capability)
      const height = body.height ?? 240
      if (height < 80 || height > 800) return problem(422, 'VALIDATION_FAILED', 'Height must be 80 to 800 pixels.')
      const version: StoredVersion = { version: extension.versions.length + 1, source, sha256: await sha256Hex(source), capabilities: clone(body.capabilities as StoredVersion['capabilities']), height, status: 'pending', submittedBy: user.id, submittedAt: clock.iso(), decidedBy: null, decidedAt: null, decisionNote: null }
      extension.versions.push(version)
      return HttpResponse.json(detail(extension, version), { status: 201 })
    }),
    http.get(`${base}/:extensionId/versions/:version`, ({ params }) => {
      const ws = String(params.workspaceId), { member, permissions } = access(ws)
      if (!member) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissions.includes('workflow.read') && !permissions.includes('workspace.manage-access')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const extension = find(ws, String(params.extensionId))
      const version = extension?.versions.find(item => item.version === Number(params.version))
      return extension && version ? HttpResponse.json(detail(extension, version)) : problem(404, 'NOT_FOUND', 'Not found')
    }),
    http.post(`${base}/:extensionId/versions/:version/decision`, async ({ params, request }) => {
      const ws = String(params.workspaceId), { user, member, permissions } = access(ws)
      if (!member) return problem(404, 'NOT_FOUND', 'Not found')
      if (!permissions.includes('workspace.manage-access')) return problem(403, 'FORBIDDEN', 'Forbidden')
      const extension = find(ws, String(params.extensionId))
      const version = extension?.versions.find(item => item.version === Number(params.version))
      if (!extension || !version) return problem(404, 'NOT_FOUND', 'Not found')
      const body = await request.json() as { decision?: string; note?: string; expectedStatus?: string }
      if (!body.note?.trim() || body.note !== body.note.trim() || body.note.length > 500) return problem(422, 'VALIDATION_FAILED', 'Give a trimmed decision note of at most 500 characters.')
      if (body.expectedStatus !== version.status) return problem(409, 'WORKFLOW_VERSION_CONFLICT', 'The extension version changed. Reload before deciding.')
      if (version.submittedBy === user.id) return problem(403, 'FORBIDDEN', 'The person who submitted an extension version cannot decide it.')
      const next = ({ 'pending:approve': 'approved', 'pending:reject': 'rejected', 'approved:revoke': 'revoked' } as Record<string, ExtensionStatus>)[`${version.status}:${body.decision}`]
      if (!next) return problem(422, 'VALIDATION_FAILED', `A ${version.status} version cannot be ${body.decision}d.`)
      Object.assign(version, { status: next, decidedBy: user.id, decidedAt: clock.iso(), decisionNote: body.note })
      return HttpResponse.json(detail(extension, version))
    }),
  ]
}
