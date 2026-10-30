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
  sourceSha256: sha('1'), resolution, conversion, targets: [], reasons: [], delegatedTo: [], ...extra })
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
      { rule: 'flows/ApproveRequest.xml', outcome: 'selected' }, { rule: 'flows/ApproveRequest-sandbox.xml', outcome: 'rulesetNotInApplication' }] },
    { ruleType: 'Rule-Declare-DecisionTable', ruleName: 'ApprovalLevel', status: 'resolved', chosen: 'decisions/ApprovalLevel.xml', candidates: [
      { rule: 'decisions/ApprovalLevel.xml', outcome: 'selected' }, { rule: 'decisions/ApprovalLevel-Services.xml', outcome: 'variant' }],
      variants: [{ rule: 'decisions/ApprovalLevel-Services.xml', kind: 'property', property: '.Category', value: 'Services' }] }] }],
  startingPoints: [{ rule: 'casetypes/PurchaseRequest.xml', kind: 'caseType', context: 'ACME-Purchasing-Work-Request', name: 'PurchaseRequest', label: 'PurchaseRequest', blocked,
    report: report(blocked ? [issue('activities/SendNotice.xml', 'blocker', 'PEGA_NATIVE_ACTIVITY_NOT_EXECUTED', 'Activities are never executed.')] : [issue('transforms/pyDefault.xml', 'review', 'PEGA_NATIVE_TRANSFORM_DEFAULT_NOT_APPLIED', 'Request forms have no default values.')]),
    rules: [
      rule('flows/ApproveRequest.xml', 'Rule-Obj-Flow', 'ApproveRequest', 'selected', 'converted', { targetKind: 'workflowNodes', targets: ['ManagerReview'], targetSha256: sha('2') }),
      rule('flows/ApproveRequest-sandbox.xml', 'Rule-Obj-Flow', 'ApproveRequest', 'rulesetNotInApplication', 'shadowed'),
      rule('activities/SendNotice.xml', 'Rule-Obj-Activity', 'SendNotice', 'selected', blocked ? 'blocked' : 'notUsed', blocked ? { reasons: ['PEGA_NATIVE_ACTIVITY_NOT_EXECUTED'] } : {}),
      rule('decisions/ApprovalLevel-Services.xml', 'Rule-Declare-DecisionTable', 'ApprovalLevel', 'variant', 'converted', { circumstance: '.Category = Services', delegatedTo: ['Purchasing:Managers'] }),
    ],
    subflows: [{ id: 'Record', label: 'Record the order', flow: 'RecordOrder', source: 'Fulfil' }],
    calls: [],
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
    expect(within(review).getAllByText('Converted')).toHaveLength(2)
    expect(within(review).getByText('Shadowed')).toBeInTheDocument()
    expect(within(review).getByText('Ruleset not in the application')).toBeInTheDocument()
    expect(within(review).getByText('Variant: .Category = Services')).toBeInTheDocument()
    expect(within(review).getByText('Delegated to Purchasing:Managers (provenance only)')).toBeInTheDocument()
    expect(within(review).getByRole('region', { name: 'Child workflows to map' })).toHaveTextContent('Record: subflow RecordOrder in Fulfil')
    expect(within(review).getAllByText(sha('2').slice(0, 12))).toHaveLength(1)
    // Each table scrolls sideways on a narrow screen, so its wrapper is a named, keyboard-focusable region (axe scrollable-region-focusable).
    expect(within(review).getByRole('region', { name: 'Table of per-rule results' })).toHaveAttribute('tabindex', '0')
    await user.selectOptions(within(review).getByLabelText('Show'), 'shadowed')
    expect(within(review).queryByText('Converted')).not.toBeInTheDocument()
    await user.click(within(review).getByRole('tab', { name: 'Effective rules' }))
    expect(await within(review).findByText(/Classes searched, closest first: ACME-Purchasing-Work-Request → ACME-Purchasing-Work → @baseclass/)).toBeInTheDocument()
    expect(within(review).getByText(/Variants: .Category = Services \(decisions\/ApprovalLevel-Services.xml\)/)).toBeInTheDocument()
    expect(within(review).getByRole('region', { name: 'Table of candidates for ACME-Purchasing-Work-Request' })).toHaveAttribute('tabindex', '0')
    await user.click(within(review).getByRole('tab', { name: 'Archive inventory' }))
    expect(await within(review).findByText(/Rule-Obj-Flow ACME-Purchasing-Work.ApproveRequest Purchasing:01-01-05 \(Yes\)/)).toBeInTheDocument()
    expect(within(review).getByRole('region', { name: 'Table of archive entries' })).toHaveAttribute('tabindex', '0')
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

  it('maps each call step to a workspace connection and converts a case type to a case definition (S117)', async () => {
    signInAs('dana.designer')
    const base = archive()
    const start = base.startingPoints[0]!
    const connected: Archive = { ...base, startingPoints: [{ ...start, label: 'Connected purchase', name: 'ConnectedPurchase', rule: 'casetypes/ConnectedPurchase.xml',
      calls: [{ id: 'PlaceOrder', label: 'Place the order', connector: 'CreateOrder', dataPage: null, method: 'POST', endpoint: 'https://procurement.example.invalid/orders',
        inputMapping: { title: '/input/title', costCenter: '/input/costCenter' }, resultMapping: { OrderId: '/id' }, timeoutSeconds: 10, flow: 'Order' }],
      caseTarget: { processes: [
        { taskId: 't_intake_Order', stageId: 'intake', stageLabel: 'Intake', flow: 'Order', rule: 'flows/Order.xml', optional: false, alternate: false },
        { taskId: 't_intake_Escalate', stageId: 'intake', stageLabel: 'Intake', flow: 'Escalate', rule: 'flows/Escalate.xml', optional: true, alternate: false },
        { taskId: 't_withdrawn_Withdraw', stageId: 'withdrawn', stageLabel: 'Withdrawn', flow: 'Withdraw', rule: 'flows/Withdraw.xml', optional: false, alternate: true }],
      alternates: [{ stageId: 'withdrawn', label: 'Withdrawn' }] } }] }
    let converted: Record<string, unknown> | null = null
    const caseRequests: Record<string, unknown>[] = []
    server.use(
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/pega-rules', () => HttpResponse.json(connected)),
      http.get('*/api/v1/workspaces/:ws/connections', () => HttpResponse.json({ items: [{ key: 'procurement', name: 'Procurement', latestVersion: 2, versions: [1, 2], binding: null,
        operations: [{ name: 'createOrder', label: 'Create order', effectClass: 'nonIdempotent', testable: false }] }] })),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/convert', async ({ request }) => {
        converted = await request.json() as Record<string, unknown>
        return HttpResponse.json({ workflow: { id: PURCHASE_WORKFLOW_ID }, draft: {}, report: job().report }, { status: 201 })
      }),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/case-definition', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>
        caseRequests.push(body)
        if (!(body.workflowMappings as Record<string, string>).Withdraw)
          return HttpResponse.json({ type: 'about:blank', title: 'Migration blocked', status: 422, code: 'MIGRATION_BLOCKED', detail: 'The case definition has open findings.',
            openItems: [issue('casetypes/ConnectedPurchase.xml', 'blocker', 'PEGA_NATIVE_WORKFLOW_MAPPING_REQUIRED', 'Name the published workflow converted from Withdraw.')] },
          { status: 422, headers: { 'content-type': 'application/problem+json' } })
        return HttpResponse.json({ dryRun: body.dryRun, key: body.key, name: body.name, sourceXml: '<definitions><case id="connected"/></definitions>', sourceSha256: sha('c'),
          dataFields: [{ key: 'title', label: 'Title', type: 'text', required: true }], workflowTasks: [{ taskId: 't_intake_Order', inputMapping: {}, resultMapping: {} }],
          report: job().report, definition: null }, { status: 200 })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${WS}/migrations/${id}`)
    const panel = await screen.findByRole('region', { name: 'Convert to a case definition' })
    expect(within(panel).getByText('Intake: Escalate (optional)')).toBeInTheDocument()
    expect(within(panel).getByText('Withdrawn: Withdraw (alternate stage)')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Create case definition' })).toBeDisabled()
    // The case definition: a refusal lists the open findings; a complete preview shows the proposal and enables creation.
    await user.type(within(panel).getByLabelText('Published workflow for Order'), 'pega-order')
    await user.type(within(panel).getByLabelText('Published workflow for Escalate'), 'pega-escalate')
    await waitFor(() => expect(within(panel).getAllByRole('option', { name: 'Finance approvers' }).length).toBeGreaterThan(0))
    await user.selectOptions(within(panel).getByLabelText('Group that plans Escalate'), 'finance-approvers')
    await user.selectOptions(within(panel).getByLabelText('Group that moves the case to Withdrawn'), 'managers')
    await user.click(within(panel).getByRole('checkbox', { name: /I reviewed the case definition findings/ }))
    await user.click(within(panel).getByRole('button', { name: 'Preview case definition' }))
    expect(await within(panel).findByText(/Name the published workflow converted from Withdraw/)).toBeInTheDocument()
    await user.type(within(panel).getByLabelText('Published workflow for Withdraw'), 'pega-withdraw')
    await user.click(within(panel).getByRole('button', { name: 'Preview case definition' }))
    expect(await within(panel).findByText(/passed the case designer's checks/)).toBeInTheDocument()
    expect(within(panel).getByText('Workflow tasks: t_intake_Order')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Create case definition' })).toBeEnabled()
    expect(caseRequests.at(-1)).toMatchObject({ key: 'connectedpurchase', name: 'Connected purchase', pegaStartRule: 'casetypes/ConnectedPurchase.xml', dryRun: true,
      workflowMappings: { Order: 'pega-order', Escalate: 'pega-escalate', Withdraw: 'pega-withdraw' }, roleMappings: { Escalate: 'finance-approvers', withdrawn: 'managers' },
      acknowledgeInfo: true, acknowledgeReview: true })
    // The call step: the source endpoint is shown; the connection and operation are chosen explicitly.
    expect(screen.getByText(/connector CreateOrder, POST https:\/\/procurement.example.invalid\/orders/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('option', { name: 'Procurement' })).toBeInTheDocument())
    await user.selectOptions(screen.getByLabelText('Connection for Place the order'), 'procurement')
    await user.selectOptions(screen.getByLabelText('Operation for Place the order'), 'createOrder')
    await user.selectOptions(screen.getByLabelText('Manager review (ApproveRequest, work queue Managers)'), 'managerApprove')
    await user.selectOptions(screen.getByLabelText('Map source assignment: Managers'), 'managers')
    await user.click(screen.getByRole('checkbox', { name: /I reviewed the assumptions/ }))
    await user.click(screen.getByRole('button', { name: 'Convert to draft' }))
    await waitFor(() => expect(converted).not.toBeNull())
    expect(converted).toMatchObject({ connectionMappings: { PlaceOrder: { connectionRef: 'procurement', operation: 'createOrder', definitionVersion: 2 } } })
  })

  it('shows the recorded case definition once a case type was converted', async () => {
    signInAs('dana.designer')
    const base = archive()
    const start = base.startingPoints[0]!
    const done: Archive = { ...base, caseConversion: { startRule: start.rule, caseDefinitionId: id, caseKey: 'purchase-case', caseVersion: 1, sourceSha256: sha('d') },
      startingPoints: [{ ...start, caseTarget: { processes: [{ taskId: 't_a_Approve', stageId: 'a', stageLabel: 'A', flow: 'ApproveRequest', rule: null, optional: false, alternate: false }], alternates: [] } }] }
    server.use(
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/pega-rules', () => HttpResponse.json(done)),
    )
    await renderAppReady(`/w/${WS}/migrations/${id}`)
    expect(await screen.findByText(/Case definition purchase-case version 1 was created from casetypes\/PurchaseRequest.xml/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Preview case definition' })).not.toBeInTheDocument()
  })

  it('keeps the created case definition on screen after the archive refetch records the conversion', async () => {
    signInAs('dana.designer')
    const base = archive()
    const start = base.startingPoints[0]!
    const caseType: Archive = { ...base, startingPoints: [{ ...start, caseTarget: { processes: [{ taskId: 't_a_Approve', stageId: 'a', stageLabel: 'A', flow: 'ApproveRequest', rule: null, optional: false, alternate: false }], alternates: [] } }] }
    let created = false
    let archiveReads = 0
    server.use(
      http.get('*/api/v1/workspaces/:ws/migrations/:id', () => HttpResponse.json(job())),
      http.get('*/api/v1/workspaces/:ws/migrations/:id/pega-rules', () => {
        archiveReads++
        return HttpResponse.json(created ? { ...caseType, caseConversion: { startRule: start.rule, caseDefinitionId: id, caseKey: 'purchase-case', caseVersion: 1, sourceSha256: sha('d') } } : caseType)
      }),
      http.post('*/api/v1/workspaces/:ws/migrations/:id/case-definition', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>
        if (!body.dryRun) created = true
        return HttpResponse.json({ dryRun: body.dryRun, key: body.key, name: body.name, sourceXml: '<definitions><case id="purchase-case"/></definitions>', sourceSha256: sha('d'),
          dataFields: [], workflowTasks: [{ taskId: 't_a_Approve', inputMapping: {}, resultMapping: {} }], report: job().report,
          definition: body.dryRun ? null : { id, key: 'purchase-case', version: 1 } }, { status: 200 })
      }),
    )
    const user = userEvent.setup(); await renderAppReady(`/w/${WS}/migrations/${id}`)
    const panel = await screen.findByRole('region', { name: 'Convert to a case definition' })
    await user.clear(within(panel).getByLabelText('Case key'))
    await user.type(within(panel).getByLabelText('Case key'), 'purchase-case')
    await user.type(within(panel).getByLabelText('Published workflow for ApproveRequest'), 'pega-approve')
    await user.click(within(panel).getByRole('checkbox', { name: /I reviewed the case definition findings/ }))
    await user.click(within(panel).getByRole('button', { name: 'Preview case definition' }))
    expect(await within(panel).findByText(/passed the case designer's checks/)).toBeInTheDocument()
    const readsBeforeCreate = archiveReads
    await user.click(within(panel).getByRole('button', { name: 'Create case definition' }))
    expect(await within(panel).findByText('Case definition created')).toBeInTheDocument()
    // The refetch now returns the recorded conversion; the panel and its creation result stay put.
    await waitFor(() => expect(archiveReads).toBeGreaterThan(readsBeforeCreate))
    const after = screen.getByRole('region', { name: 'Convert to a case definition' })
    expect(within(after).getByText('Case definition created')).toBeInTheDocument()
    expect(within(after).queryByRole('button', { name: 'Create case definition' })).not.toBeInTheDocument()
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
