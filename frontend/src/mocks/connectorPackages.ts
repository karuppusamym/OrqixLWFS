import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'
import { newId } from './state'

type S = components['schemas']

/**
 * Simplified UI double for connector packages, the catalog, the palette and package triggers (docs/build/S133), mock
 * mode and unit tests only. Archives are not read here: an import adds a fixed ServiceNow-shaped review. Signatures,
 * validation, maker-checker and installs are the backend's and are tested there.
 */
const B = '/api/v1/workspaces/:ws'

let packages: S['ConnectorPackageDetail'][] = []
let triggers: S['PackageTrigger'][] = []
let palette: S['ConnectorPalette']['items'] = []

export function resetPackageMocks(): void {
  packages = []
  triggers = []
  palette = []
}

export function mockPackages(): S['ConnectorPackageDetail'][] {
  return packages
}

export function seedPackageMocks(seed: { packages?: S['ConnectorPackageDetail'][]; triggers?: S['PackageTrigger'][]; palette?: S['ConnectorPalette']['items'] }): void {
  packages = seed.packages ?? []
  triggers = seed.triggers ?? []
  palette = seed.palette ?? []
}

/** A review of the ServiceNow example package, pending approval and imported by someone else. */
export function sampleDetail(overrides: Partial<S['ConnectorPackageSummary']> = {}): S['ConnectorPackageDetail'] {
  return {
    summary: {
      id: newId(), packageId: 'com.orqix.servicenow-itsm', name: 'ServiceNow ITSM', version: '1.0.0', publisher: 'Orqix examples',
      keyId: 'orqix-example-2026', sha256: 'a'.repeat(64), signatureStatus: 'verified', status: 'pending',
      importedBy: { id: '00000000-0000-7000-8000-00000000000a', displayName: 'Wade Admin' }, importedAt: '2026-11-05T10:00:00Z',
      decidedBy: null, decidedAt: null, decisionNote: null, installStatus: null, revision: 1, ...overrides,
    },
    manifest: {},
    review: ({
      hosts: [{ pattern: 'https://*.service-now.com', purpose: "The customer's ServiceNow instance" }],
      authSchemes: ['oauth2ClientCredentials', 'bearerToken'],
      dataLeavingOrqix: ['Incident short description and description (from the request form)', 'Work notes written by the workflow'],
      connections: [{ key: 'servicenow-itsm' }],
      operations: [
        { connection: 'servicenow-itsm', operation: 'createIncident', label: 'Raise an incident', method: 'POST', path: '/api/now/table/incident', effectClass: 'idempotentByLookup', inputs: [], asynchronous: false },
        { connection: 'servicenow-itsm', operation: 'getIncident', label: 'Get an incident', method: 'GET', path: '/api/now/table/incident/{sysId}', effectClass: 'readOnly', inputs: [], asynchronous: false },
        { connection: 'servicenow-itsm', operation: 'addWorkNote', label: 'Add a work note', method: 'PATCH', path: '/api/now/table/incident/{sysId}', effectClass: 'nonIdempotent', inputs: [], asynchronous: false },
      ],
      operationsByEffect: { readOnly: 1, idempotentByLookup: 1, nonIdempotent: 1 },
      triggers: [
        { key: 'incident-updated', label: 'Incident resolved, closed or cancelled', kind: 'webhook', action: 'message', message: 'servicenow-incident-updated', verification: 'hmacSha256', weaker: false },
        { key: 'incident-basic', label: 'Incident outcome (basic)', kind: 'webhook', action: 'message', message: 'servicenow-incident-updated', verification: 'basic', weaker: true },
      ],
      stepTemplates: [{ key: 'raise-incident', label: 'Raise an incident' }],
      workflowTemplates: [{ key: 'it-access-request', label: 'IT access request' }],
      fixtures: { count: 5, passed: 5 },
      docs: ['docs/README.md'],
    }) as unknown as S['ConnectorPackageReview'],
    comparison: { installedVersion: null, addedOperations: [], removedOperations: [], changes: [], compatible: true },
    whereUsed: [],
    unsignedReason: null,
    retireReason: null,
    canManage: true,
    canApprove: true,
    canDecide: overrides.status === undefined || overrides.status === 'pending',
  }
}

function find(id: unknown) {
  return packages.find((p) => p.summary.id === String(id))
}

export function createPackageHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/connector-packages`, () => HttpResponse.json({
      items: packages.map((p) => p.summary), canManage: true, canApprove: true,
    } satisfies S['ConnectorPackageList'])),
    http.post(`${B}/connector-packages`, async ({ request }) => {
      const body = await request.json() as S['ConnectorPackageImport']
      if (!body.archive) return HttpResponse.json({ status: 422, code: 'VALIDATION_FAILED', title: 'Some values are not valid', fieldErrors: [{ pointer: '/archive', code: 'required', message: 'Upload a .orqpkg file.' }] }, { status: 422 })
      const detail = sampleDetail({ importedBy: { id: 'me', displayName: 'You' } })
      detail.canDecide = false
      packages = [detail, ...packages]
      return HttpResponse.json(detail, { status: 201 })
    }),
    http.get(`${B}/connector-packages/:id`, ({ params }) => {
      const p = find(params.id)
      return p ? HttpResponse.json(p) : HttpResponse.json({ status: 404, code: 'NOT_FOUND', title: 'Not found' }, { status: 404 })
    }),
    http.post(`${B}/connector-packages/:id/decision`, async ({ params, request }) => {
      const p = find(params.id)
      if (!p) return HttpResponse.json({ status: 404, code: 'NOT_FOUND', title: 'Not found' }, { status: 404 })
      const body = await request.json() as S['ConnectorPackageDecision']
      if (body.sha256 !== p.summary.sha256) return HttpResponse.json({ status: 409, code: 'CONFIGURATION_VERSION_CONFLICT', title: 'Changed' }, { status: 409 })
      p.summary = { ...p.summary, status: body.decision === 'approve' ? 'approved' : 'rejected', decisionNote: body.note, revision: p.summary.revision + 1,
        decidedBy: { id: 'me', displayName: 'You' }, decidedAt: new Date().toISOString() }
      p.canDecide = false
      return HttpResponse.json(p)
    }),
    http.post(`${B}/connector-packages/:id/install`, ({ params }) => {
      const p = find(params.id)
      if (!p) return HttpResponse.json({ status: 404, code: 'NOT_FOUND', title: 'Not found' }, { status: 404 })
      p.summary = { ...p.summary, installStatus: 'active' }
      return HttpResponse.json({ packageId: p.summary.packageId, version: p.summary.version, previousVersion: null,
        connections: [{ key: 'servicenow-itsm', version: 1, reused: false }], hostsToAllow: ['https://*.service-now.com'] } satisfies S['ConnectorPackageInstall'], { status: 201 })
    }),
    http.post(`${B}/connector-packages/:id/retire`, async ({ params, request }) => {
      const p = find(params.id)
      if (!p) return HttpResponse.json({ status: 404, code: 'NOT_FOUND', title: 'Not found' }, { status: 404 })
      const body = await request.json() as S['ConnectorPackageRetire']
      p.summary = { ...p.summary, status: 'retired', revision: p.summary.revision + 1 }
      p.retireReason = body.reason
      return HttpResponse.json(p)
    }),
    http.get(`${B}/connector-catalog`, () => HttpResponse.json({
      items: [...new Set(packages.map((p) => p.summary.packageId))].map((packageId) => {
        const versions = packages.filter((p) => p.summary.packageId === packageId)
        return {
          packageId, name: versions[0]!.summary.name, publisher: versions[0]!.summary.publisher,
          latestVersion: versions.find((v) => v.summary.status === 'approved')?.summary.version ?? null,
          versions: versions.map((v) => ({ id: v.summary.id, version: v.summary.version, status: v.summary.status, signatureStatus: v.summary.signatureStatus, importedAt: v.summary.importedAt })),
          installs: versions.filter((v) => v.summary.installStatus === 'active').map((v) => ({ workspaceId: 'ws', workspaceName: 'This workspace', version: v.summary.version, status: 'active' as const })),
          outdatedPins: [],
        }
      }),
    } satisfies S['ConnectorCatalog'])),
    http.get(`${B}/connector-palette`, ({ request }) => {
      const q = (new URL(request.url).searchParams.get('q') ?? '').toLowerCase()
      return HttpResponse.json({ items: palette.filter((i) => !q || i.label.toLowerCase().includes(q) || i.operation.toLowerCase().includes(q)) } satisfies S['ConnectorPalette'])
    }),
    http.get(`${B}/package-triggers`, () => HttpResponse.json({ items: triggers } satisfies S['PackageTriggerList'])),
    http.post(`${B}/package-triggers`, async ({ request }) => {
      const body = await request.json() as S['PackageTriggerConfigure']
      const trigger: S['PackageTrigger'] = {
        id: newId(), packageId: body.packageId ?? '', packageVersion: '1.0.0', triggerKey: body.triggerKey ?? '', label: body.triggerKey ?? '',
        kind: 'webhook', action: 'message', workflowId: body.workflowId ?? null, messageName: 'servicenow-incident-updated',
        verification: 'hmacSha256', weaker: false, secretRef: body.secretRef ?? null, jwtIssuer: body.jwtIssuer ?? null,
        jwtAudience: body.jwtAudience ?? null, connectionKey: null, every: null, enabled: body.enabled ?? true, version: 1,
        receivePath: `/api/v1/workspaces/ws/package-triggers/${newId()}/events`,
        status: { watermark: null, nextRunAt: null, lastRunAt: null, lastSuccessAt: null, lastErrorCode: null, lagSeconds: null, polls: 0, recordsHandled: 0, duplicates: 0, deadRecords: 0, leased: false },
      }
      triggers = [...triggers, trigger]
      return HttpResponse.json(trigger, { status: 201 })
    }),
    http.put(`${B}/package-triggers/:id`, async ({ params, request }) => {
      const body = await request.json() as S['PackageTriggerConfigure']
      const t = triggers.find((x) => x.id === String(params.id))
      if (!t) return HttpResponse.json({ status: 404, code: 'NOT_FOUND', title: 'Not found' }, { status: 404 })
      Object.assign(t, { enabled: body.enabled ?? t.enabled, version: t.version + 1 })
      return HttpResponse.json(t)
    }),
    http.get(`${B}/package-triggers/:id/dead-records`, () => HttpResponse.json({ items: [] })),
  ]
}
