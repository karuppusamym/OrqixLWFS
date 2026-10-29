import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/schema'
import { PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { server } from '@/test/server'
import { renderAppReady, signInAs, WS } from '@/test/utils'

type Archive = components['schemas']['PegaRuleArchive']
type Job = components['schemas']['MigrationJob']
type Issue = components['schemas']['CompatibilityIssue']
type Result = components['schemas']['PegaRuleResult']
const id = '00000000-0000-7000-8000-000000000961'
const sha = (c: string) => c.repeat(64)

const issue = (sourceElementId: string, severity: Issue['severity'], reasonCode: string, explanation: string): Issue =>
  ({ sourceElementId, sourceName: sourceElementId, sourceKind: 'Rule-Obj-Flow', severity, reasonCode, explanation, resolutionStatus: 'open', sourcePath: sourceElementId + '#/pyShapes/3' })
const report = (issues: Issue[]): components['schemas']['CompatibilityReport'] => ({
  sourceSystem: 'pega', sourceVersion: 'Rule-Application Purchasing 01-01-01 (declared; synthetic grammar pega-native-0.1)', adapterVersion: 'pega-native-0.1', sourceHash: sha('a'),
  processId: 'PurchaseRequest', processName: 'PurchaseRequest', elementCounts: { total: issues.length }, issues, assumptions: ['Synthetic grammar.'],
  requiredMappings: { groups: ['Managers'], approvalTasks: ['ManagerReview'], serviceTasks: [] }, publishable: false })
const rule = (path: string, ruleType: string, name: string, resolution: string, conversion: Result['conversion'], extra: Partial<Result> = {}): Result => ({
  rule: path, ruleType, className: 'ACME-Purchasing-Work', ruleName: name, ruleset: 'Purchasing', version: '01-01-05', availability: 'Yes',
  sourceSha256: sha('1'), resolution, conversion, targets: [], reasons: [], ...extra })
const archive = (blocked = false): Archive => ({
  migrationId: id, status: 'analyzed', decoder: 'pega-native-0.1', adapterVersion: 'pega-native-0.1', sourceSha256: sha('a'),
  application: 'Purchasing 01-01-01', stack: [{ name: 'PurchasingOrg', version: '01-02-01' }, { name: 'Purchasing', version: '01-01-10' }],
  archiveBlockers: blocked ? [{ code: 'PEGA_NATIVE_ARCHIVE_INCOMPLETE', paths: ['binary/legacy.bin'] }] : [],
  entries: [
    { path: 'flows/ApproveRequest.xml', sha256: sha('1'), status: 'rule', ruleType: 'Rule-Obj-Flow', className: 'ACME-Purchasing-Work', ruleName: 'ApproveRequest', ruleset: 'Purchasing', version: '01-01-05', availability: 'Yes', circumstanced: false },
    ...(blocked ? [{ path: 'binary/legacy.bin', sha256: sha('9'), status: 'refused' as const, reason: 'PEGA_NATIVE_ENTRY_UNDECODED', detail: 'Binary or non-XML entry.', circumstanced: false }] : []),
  ],
  contexts: [{ className: 'ACME-Purchasing-Work-Request', chain: ['ACME-Purchasing-Work-Request', 'ACME-Purchasing-Work', '@baseclass'], keys: [
    { ruleType: 'Rule-Obj-Flow', ruleName: 'ApproveRequest', status: 'resolved', chosen: 'flows/ApproveRequest.xml', candidates: [
      { rule: 'flows/ApproveRequest.xml', outcome: 'selected' }, { rule: 'flows/ApproveRequest-sandbox.xml', outcome: 'rulesetNotInApplication' }] }] }],
  startingPoints: [{ rule: 'casetypes/PurchaseRequest.xml', kind: 'caseType', context: 'ACME-Purchasing-Work-Request', name: 'PurchaseRequest', label: 'PurchaseRequest', blocked,
    report: report(blocked ? [issue('activities/SendNotice.xml', 'blocker', 'PEGA_NATIVE_ACTIVITY_NOT_EXECUTED', 'Activities are never executed.')] : [issue('transforms/pyDefault.xml', 'review', 'PEGA_NATIVE_TRANSFORM_DEFAULT_NOT_APPLIED', 'Request forms have no default values.')]),
    rules: [
      rule('flows/ApproveRequest.xml', 'Rule-Obj-Flow', 'ApproveRequest', 'selected', 'converted', { targetKind: 'workflowNodes', targets: ['ManagerReview'], targetSha256: sha('2') }),
      rule('flows/ApproveRequest-sandbox.xml', 'Rule-Obj-Flow', 'ApproveRequest', 'rulesetNotInApplication', 'shadowed'),
      rule('activities/SendNotice.xml', 'Rule-Obj-Activity', 'SendNotice', 'selected', blocked ? 'blocked' : 'notUsed', blocked ? { reasons: ['PEGA_NATIVE_ACTIVITY_NOT_EXECUTED'] } : {}),
    ],
    assignments: [{ id: 'ManagerReview', label: 'Manager review', workQueue: 'Managers', flow: 'ApproveRequest', actions: [{ connectorId: 'managerApprove', flowAction: 'Approve' }, { connectorId: 'managerReject', flowAction: 'Reject' }] }] }],
  conversion: [],
})
const job = (blocked = false): Job => ({ id, fileName: 'purchase-request.zip', status: 'analyzed', report: archive(blocked).startingPoints[0]!.report, createdAt: '2026-10-29T00:00:00Z' })

describe('Native Pega rule archive import', () => {
  it('uploads the archive as base64, shows resolution and per-rule results, and converts with explicit approval meanings', async () => {
    signInAs('dana.designer')
    let uploaded: Record<string, unknown> | null = null
    let converted: Record<string, unknown> | null = null
    server.use(
      http.post('*/api/v1/workspaces/:ws/migrations', async ({ request }) => { uploaded = await request.json() as Record<string, unknown>; return HttpResponse.json(job(), { status: 201 }) }),
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/pega-rules', () => HttpResponse.json(archive())),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', async ({ request }) => {
        converted = await request.json() as Record<string, unknown>
        return HttpResponse.json({ workflow: { id: PURCHASE_WORKFLOW_ID }, draft: {}, report: job().report }, { status: 201 })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${WS}/migrations`)
    await user.selectOptions(await screen.findByLabelText('Source format'), 'pegaRules')
    await user.upload(screen.getByLabelText(/Source file/), new File(['PK\u0003\u0004synthetic'], 'purchase-request.zip', { type: 'application/zip' }))
    await user.click(screen.getByRole('button', { name: 'Analyze source' }))
    const review = await screen.findByRole('region', { name: 'Native Pega rule archive' })
    expect(uploaded).toMatchObject({ fileName: 'purchase-request.zip', sourceFormat: 'pegaRules', content: btoa('PK\u0003\u0004synthetic') })
    expect(within(review).getByText('PurchasingOrg:01-02-01 → Purchasing:01-01-10')).toBeInTheDocument()
    expect(within(review).getByText(/Fidelity against genuine Pega exports still needs customer qualification/)).toBeInTheDocument()
    // Per-rule results: converted with hashes, shadowed with the reason, unused rules listed.
    expect(within(review).getByText('Converted')).toBeInTheDocument()
    expect(within(review).getByText('Shadowed')).toBeInTheDocument()
    expect(within(review).getByText('Ruleset not in the application')).toBeInTheDocument()
    expect(within(review).getAllByText(sha('2').slice(0, 12))).toHaveLength(1)
    await user.selectOptions(within(review).getByLabelText('Show'), 'shadowed')
    expect(within(review).queryByText('Converted')).not.toBeInTheDocument()
    await user.click(within(review).getByRole('tab', { name: 'Effective rules' }))
    expect(await within(review).findByText(/Classes searched, closest first: ACME-Purchasing-Work-Request → ACME-Purchasing-Work → @baseclass/)).toBeInTheDocument()
    await user.click(within(review).getByRole('tab', { name: 'Archive inventory' }))
    expect(await within(review).findByText(/Rule-Obj-Flow ACME-Purchasing-Work.ApproveRequest Purchasing:01-01-05 \(Yes\)/)).toBeInTheDocument()
    // The route JSON field is replaced by an explicit choice; nothing is inferred from the action names.
    expect(screen.queryByLabelText(/Approval routes \(JSON/)).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Manager review (ApproveRequest, work queue Managers)'), 'managerApprove')
    await user.selectOptions(screen.getByLabelText('Map source assignment: Managers'), 'managers')
    expect(screen.queryByLabelText(/Request schema/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /I reviewed the assumptions/ }))
    await user.click(screen.getByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(converted).not.toBeNull())
    expect(converted).toMatchObject({ pegaStartRule: 'casetypes/PurchaseRequest.xml', groupMappings: { Managers: 'managers' },
      approvalRoutes: { ManagerReview: { approvedFlowId: 'managerApprove', rejectedFlowId: 'managerReject' } }, acknowledgeInfo: true })
    expect(converted).not.toHaveProperty('inputSchema')
  })

  it('shows blockers and refused entries for an archive that cannot be converted', async () => {
    signInAs('dana.designer')
    server.use(
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job(true))),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/pega-rules', () => HttpResponse.json(archive(true))),
    )
    await renderAppReady(`/w/${WS}/migrations/${id}`)
    const review = await screen.findByRole('region', { name: 'Native Pega rule archive' })
    expect(within(review).getByText('The archive cannot be fully resolved')).toBeInTheDocument()
    expect(within(review).getByText(/PEGA_NATIVE_ARCHIVE_INCOMPLETE: binary\/legacy.bin/)).toBeInTheDocument()
    expect(within(review).getByText(/blocked until the findings below are resolved/)).toBeInTheDocument()
    expect(within(review).getByText('Blocked')).toBeInTheDocument()
    expect(screen.getByText('Activities are never executed.')).toBeInTheDocument()
    expect(screen.getByText(/This report has blockers/)).toBeInTheDocument()
  })

  it('mock mode refuses a native archive clearly', async () => {
    signInAs('dana.designer')
    const user = userEvent.setup(); await renderAppReady(`/w/${WS}/migrations`)
    await user.selectOptions(await screen.findByLabelText('Source format'), 'pegaRules')
    await user.upload(screen.getByLabelText(/Source file/), new File(['PK'], 'a.zip', { type: 'application/zip' }))
    await user.click(screen.getByRole('button', { name: 'Analyze source' }))
    expect(await screen.findByText(/native rule archive and XPDL conversion require the real backend/)).toBeInTheDocument()
  })
})
