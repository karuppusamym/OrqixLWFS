import { expect, test } from '../support/fixtures'
import { apiGet, decideOpenTask, workflowIdByKey, workspaceId } from '../support/app'
import { apiRead, openTaskOfRun } from '../support/api'
import { MOCK } from '../support/env'
import { expectNoSeriousViolations } from '../support/axe'
import { analyseNativeArchive, convertNative, nativeKey, publishOnPage, startRun, waitForLifecycle } from '../support/pega-native'

/**
 * Native Pega rule archive conversion (MIG11, docs/build/S112): upload a synthetic archive of <pagedata> rule pages,
 * inspect the inventory and the effective-rule resolution with its provenance, review and convert to a compiled draft,
 * publish separately, and run the result through routed paths as the right personas. The refusals archive must show its
 * blockers with source paths and create no workflow. Real backend only; the mock backend rejects Pega conversion.
 */
test.skip(MOCK, 'The mock backend rejects Pega conversion by design (docs/build/S112).')
test.setTimeout(300_000)

type Archive = {
  entries: Array<{ path: string; status: string; sha256: string }>
  contexts: Array<{ className: string; keys: Array<{ ruleType: string; ruleName: string; chosen: string | null; candidates: Array<{ rule: string; outcome: string }> }> }>
  startingPoints: Array<{ rule: string; rules: Array<{ rule: string; conversion: string; targetSha256: string | null }> }>
  conversion: Array<{ rule: string; targetSha256: string | null }>
  convertedStartRule: string | null
}

test('purchase-request archive: effective rules, reviewed conversion, separate publication and routed runs', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const migration = await analyseNativeArchive(dana, ws, 'purchase-request.zip')
  const review = dana.getByRole('region', { name: 'Native Pega rule archive' })
  await expect(review.getByText('PurchasingOrg:01-02-01 → Purchasing:01-01-10')).toBeVisible()
  await expect(review.getByText(/Fidelity against genuine Pega exports still needs customer qualification/)).toBeVisible()
  // Per-rule results: converted rules with target hashes, shadowed candidates with the reason.
  await expect(review.getByText('Shadowed', { exact: true }).first()).toBeVisible()
  await expect(review.getByText('Ruleset not in the application')).toBeVisible()
  await expect(review.getByText('Not available', { exact: true }).first()).toBeVisible()
  await expectNoSeriousViolations(dana, 'native Pega per-rule results')
  // Effective rules: the class chain and every candidate's outcome.
  await review.getByRole('tab', { name: 'Effective rules' }).click()
  await expect(review.getByText(/Classes searched, closest first: ACME-Purchasing-Work-Request → ACME-Purchasing-Work/)).toBeVisible()
  await expect(review.getByText('Hidden by a withdrawn version').first()).toBeVisible()
  await expectNoSeriousViolations(dana, 'native Pega effective rules')
  await review.getByRole('tab', { name: 'Archive inventory' }).click()
  await expect(review.getByText('flows/ApproveRequest-sandbox.xml')).toBeVisible()
  // The same provenance through the API.
  const archive = await apiGet<Archive>(dana, `/workspaces/${ws}/migrations/${migration}/pega-rules`)
  const flowKey = archive.contexts[0]!.keys.find(k => k.ruleType === 'Rule-Obj-Flow' && k.ruleName === 'ApproveRequest')!
  expect(flowKey.chosen).toBe('flows/ApproveRequest.xml')
  expect(flowKey.candidates).toContainEqual({ rule: 'flows/ApproveRequest-sandbox.xml', outcome: 'rulesetNotInApplication' })
  expect(archive.startingPoints[0]!.rules.find(r => r.rule === 'decisions/ApprovalLevel-01-01-01.xml')!.targetSha256).toMatch(/^[0-9a-f]{64}$/)

  // Review and convert: approval meanings and work queue mappings are explicit.
  const key = nativeKey('pega-native')
  const workflowId = await convertNative(dana, {
    key,
    approve: {
      'Manager review (ApproveRequest, work queue Managers)': 'managerApprove',
      'Finance review (ApproveRequest, work queue Finance)': 'financeApprove',
      'Confirm order (Fulfil, work queue Procurement)': 'confirm',
    },
    groups: { Managers: 'Managers', Finance: 'Finance approvers', Procurement: 'Managers' },
  })
  const job = await apiGet<{ status: string; convertedWorkflowId: string; report: { adapterVersion: string } }>(dana, `/workspaces/${ws}/migrations/${migration}`)
  expect(job.status).toBe('converted')
  expect(job.report.adapterVersion).toBe('pega-native-0.1')
  expect((await apiGet<{ activeRelease: unknown }>(dana, `/workspaces/${ws}/workflows/${workflowId}`)).activeRelease).toBeNull()
  const recorded = await apiGet<Archive>(dana, `/workspaces/${ws}/migrations/${migration}/pega-rules`)
  expect(recorded.convertedStartRule).toBe('casetypes/PurchaseRequest.xml')
  expect(recorded.conversion.some(r => r.targetSha256 && /^[0-9a-f]{64}$/.test(r.targetSha256))).toBe(true)

  const paul = await sessions.as('paul.publisher')
  await publishOnPage(paul, ws, workflowId)

  const rhea = await sessions.as('rhea.requester')
  const base = { description: 'Laptops for the new team', category: 'Hardware', vendorName: 'Acme', vendorPreferred: true }
  // 4,999.99 with a preferred vendor: manager review, no vendor check, order confirmation, completed.
  const standard = await startRun(rhea, ws, workflowId, { ...base, amount: 4999.99 })
  const mani = await sessions.as('mani.manager')
  await openTaskOfRun(mani, ws, standard)
  await expect(mani.getByText('Manager review').first()).toBeVisible()
  await decideOpenTask(mani, 'approve')
  await openTaskOfRun(mani, ws, standard)
  await expect(mani.getByText('Confirm order').first()).toBeVisible()
  await decideOpenTask(mani, 'approve')
  expect(await waitForLifecycle(rhea, ws, standard, ['completed', 'needsAttention'])).toBe('completed')
  await rhea.goto(`/w/${ws}/runs/${standard}`)
  await expect(rhea.getByTestId('run-status')).toContainText(/Completed/, { timeout: 60_000 })
  await expectNoSeriousViolations(rhea, 'native Pega converted run')

  // 5,000: the converted decision table routes to finance, which rejects with a reason.
  const finance = await startRun(rhea, ws, workflowId, { ...base, amount: 5000 })
  const fin = await sessions.as('fin.finance')
  await openTaskOfRun(fin, ws, finance)
  await expect(fin.getByText('Finance review').first()).toBeVisible()
  await decideOpenTask(fin, 'reject', 'Over the quarterly budget')
  await waitForLifecycle(rhea, ws, finance, ['completed', 'rejected', 'needsAttention'])
  const ended = await apiGet<{ outcome: string; outcomeKind: string }>(rhea, `/workspaces/${ws}/runs/${finance}`)
  expect(ended.outcome).toBe('Rejected')
  expect(ended.outcomeKind).toBe('rejected')
})

test('refusals archive: blockers name their source paths and no workflow is created', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  sessions.allowHttpErrors()
  const ws = await workspaceId(dana)
  const migration = await analyseNativeArchive(dana, ws, 'refusals.zip')
  const review = dana.getByRole('region', { name: 'Native Pega rule archive' })
  await expect(review.getByText('The archive cannot be fully resolved')).toBeVisible()
  await expect(review.getByText(/PEGA_NATIVE_ARCHIVE_INCOMPLETE: binary\/legacy.bin/)).toBeVisible()
  await expect(review.getByText(/blocked until the findings below are resolved/)).toBeVisible()
  await expect(review.getByText('Blocked', { exact: true }).first()).toBeVisible()
  await expect(dana.getByText(/This report has blockers/)).toBeVisible()
  await expect(dana.getByText(/flows\/Escalate.xml#\/pyShapes\/2/).first()).toBeVisible()
  await expectNoSeriousViolations(dana, 'native Pega refusals')
  await review.getByRole('tab', { name: 'Effective rules' }).click()
  await expect(review.getByText(/Blocked \(stops resolution\)/).first()).toBeVisible()

  const key = nativeKey('pega-refused')
  await dana.getByLabel('Workflow key', { exact: true }).fill(key)
  const form = dana.locator('form').filter({ has: dana.getByRole('button', { name: 'Convert to draft' }) })
  const acks = form.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) if (await acks.nth(i).isVisible()) await acks.nth(i).check()
  await dana.getByRole('button', { name: 'Convert to draft' }).click()
  const issues = dana.getByRole('list', { name: 'Unresolved conversion issues' })
  await expect(issues).toBeVisible({ timeout: 30_000 })
  await expect(issues).toContainText('activities/SendNotice.xml')
  await expect(issues).toContainText('binary/legacy.bin')
  await expect(dana.getByTestId('studio')).toHaveCount(0)

  const job = await apiGet<{ status: string; convertedWorkflowId: string | null }>(dana, `/workspaces/${ws}/migrations/${migration}`)
  expect(job.status).toBe('analyzed')
  expect(job.convertedWorkflowId).toBeNull()
  await expect(workflowIdByKey(dana, ws, key)).rejects.toThrow(/not found/)
})

test('a requester cannot upload a native archive and an outsider cannot read one', async ({ sessions }) => {
  const dana = await sessions.as('dana.designer')
  const ws = await workspaceId(dana)
  const migration = await analyseNativeArchive(dana, ws, 'purchase-request.zip')
  sessions.allowHttpErrors()
  const rhea = await sessions.as('rhea.requester')
  await rhea.goto(`/w/${ws}/migrations`)
  await expect(rhea.getByLabel('Source format')).toHaveCount(0)
  const otto = await sessions.as('otto.outsider')
  await otto.goto('/')
  const read = await apiRead(otto, `/workspaces/${ws}/migrations/${migration}/pega-rules`)
  expect([403, 404]).toContain(read.status)
})
