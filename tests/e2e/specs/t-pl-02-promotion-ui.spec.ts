import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiRead, apiSend, createWorkflow, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * PL02 promotion screen and production approval gate (docs/build/S74). A designer checks and promotes a release from the
 * sandbox into production through the screen and finds it in the history. With the gate on, the same designer can only
 * request; a different person holding the grant-only workflow.approve-promotion approves; the requester cannot approve
 * their own request (screen offers no button, API answers 403 SELF_APPROVAL_DENIED).
 * Real stack only (the mock double is simplified and has no second approver).
 */
test.skip(MOCK, 'The mock promotion double is simplified (docs/build/S74).')

interface Env { stage: string; version: number; requireApproval: boolean }

test('promotion screen, approval gate, second-person approval and refused self-approval', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const sandbox = await workspaceId(designer, 'purchasing-sandbox')
  const prod = await workspaceId(designer, 'purchasing')
  const admin = await sessions.as('wade.admin')
  sessions.allowHttpErrors()

  // Make sure the gate starts off and the sandbox is an earlier stage.
  let prodEnv = await apiGet<Env>(admin, `/workspaces/${prod}/environment`)
  if (prodEnv.requireApproval) {
    const r = await apiSend<Env>(admin, 'PUT', `/workspaces/${prod}/environment`, { stage: 'production', expectedVersion: prodEnv.version, requireApproval: false })
    expect(r.status).toBe(200)
  }

  const wf = await createWorkflow(designer, sandbox, uniq('Promote screen'))
  const publisher = await sessions.as('paul.publisher')
  const release = await publish(publisher, sandbox, wf.id)

  // Direct promotion through the screen.
  await designer.goto(`/w/${sandbox}/workflows/${wf.id}`)
  await designer.getByRole('link', { name: /Promote version 1 to another workspace/ }).click()
  await expect(designer.getByRole('heading', { level: 1 })).toContainText('Promote version 1')
  const target = designer.getByLabel('Promote to')
  const options = await target.locator('option').allInnerTexts()
  const prodOption = options.find((o) => /Production/.test(o) && !/not a later stage/.test(o))
  expect(prodOption, `options: ${options.join(' | ')}`).toBeTruthy()
  await target.selectOption({ label: prodOption! })
  await designer.getByRole('button', { name: 'Run compatibility check' }).click()
  await expect(designer.getByTestId('compatibility')).toHaveText('Compatible')
  await expectNoSeriousViolations(designer, 'Promotion screen, compatibility report')
  await designer.getByRole('button', { name: 'Promote as draft' }).click()
  await expect(designer.getByTestId('promotion-outcome')).toContainText('draft revision')
  await expect(designer.getByRole('table', { name: 'Promotions of this workflow' })).toContainText('Sent')
  await expectNoSeriousViolations(designer, 'Promotion screen, outcome and history')

  // Turn the gate on (administrator, on the Promotions page) and request instead.
  await admin.goto(`/w/${prod}/promotions`)
  await expect(admin.getByTestId('stage-summary')).toContainText('Production')
  await admin.getByLabel('Require approval before a promotion into this workspace').check()
  await admin.getByRole('button', { name: 'Save stage' }).click()
  await expect(admin.getByTestId('stage-summary')).toContainText('needs a second person')
  await expectNoSeriousViolations(admin, 'Promotions page with gate on')

  const wf2 = await createWorkflow(designer, sandbox, uniq('Gated promote'))
  const release2 = await publish(publisher, sandbox, wf2.id)
  const direct = await apiSend<{ code?: string }>(designer, 'POST', `/workspaces/${sandbox}/workflows/${wf2.id}/releases/${release2}/promote`, { targetWorkspaceId: prod })
  expect(direct.status, 'direct promotion into a gated workspace').toBe(409)

  await designer.goto(`/w/${sandbox}/workflows/${wf2.id}/releases/${release2}/promote`)
  await designer.getByLabel('Promote to').selectOption({ label: prodOption! })
  await expect(designer.getByTestId('approval-gate-note')).toContainText('Approval required')
  await designer.getByRole('button', { name: 'Run compatibility check' }).click()
  await expect(designer.getByText('Approval needed', { exact: true })).toBeVisible()
  await designer.getByLabel('Note for the approver (optional)').fill('Please approve')
  await expectNoSeriousViolations(designer, 'Promotion screen, approval needed')
  await designer.getByRole('button', { name: 'Request approval' }).click()
  await expect(designer.getByTestId('promotion-outcome')).toContainText('waiting for approval')

  // The requester sees no approve button; the API refuses even when the grant is held.
  const pending = await apiGet<{ items: Array<{ id: string; workflowKey: string }> }>(designer, `/workspaces/${prod}/promotion-requests`)
  const request = pending.items.find((r) => r.workflowKey === wf2.key)
  expect(request).toBeTruthy()
  const me = await apiGet<{ subject: { id: string } }>(designer, '/me')
  const approverMe = await apiGet<{ subject: { id: string } }>(publisher, '/me')
  for (const subject of [me.subject.id, approverMe.subject.id]) {
    const granted = await apiSend(admin, 'POST', `/workspaces/${prod}/access/grants`, { permission: 'workflow.approve-promotion', principal: { type: 'subject', value: subject } })
    expect([201, 409]).toContain(granted.status)
  }
  await designer.goto(`/w/${prod}/promotions`)
  await expect(designer.getByText('You asked for this, so someone else must decide.')).toBeVisible()
  await expect(designer.getByRole('button', { name: /^Approve / })).toHaveCount(0)
  await expect(designer.getByRole('button', { name: /^Withdraw / })).toBeVisible()
  const self = await apiSend<{ code?: string }>(designer, 'POST', `/workspaces/${prod}/promotion-requests/${request!.id}/approve`, {})
  expect(self.status).toBe(403)
  expect(JSON.stringify(self.body)).toContain('SELF_APPROVAL_DENIED')
  await expectNoSeriousViolations(designer, 'Promotions page, requester view')

  // A second person approves in the browser.
  await publisher.goto(`/w/${prod}/promotions`)
  await expect(publisher.getByRole('button', { name: new RegExp(`^Approve ${wf2.key} version 1`) })).toBeVisible()
  await expectNoSeriousViolations(publisher, 'Promotions page, approver view')
  await publisher.getByRole('button', { name: new RegExp(`^Approve ${wf2.key} version 1`) }).click()
  await expect(publisher.getByText('No request is waiting for approval.')).toBeVisible()
  await publisher.getByLabel('Show decided requests').check()
  await expect(publisher.getByRole('table', { name: 'Promotion requests' })).toContainText('Approved')

  // History on the source workflow names the approver.
  await designer.goto(`/w/${sandbox}/workflows/${wf2.id}/releases/${release2}/promote`)
  await expect(designer.getByRole('table', { name: 'Promotions of this workflow' })).toContainText('Sent')
  const history = await apiRead<{ items: Array<{ approvedBy?: string | null }> }>(designer, `/workspaces/${sandbox}/workflows/${wf2.id}/promotions`)
  expect(history.body.items.some((p) => p.approvedBy)).toBe(true)

  // Leave the shared workspace as found: gate off.
  prodEnv = await apiGet<Env>(admin, `/workspaces/${prod}/environment`)
  const off = await apiSend(admin, 'PUT', `/workspaces/${prod}/environment`, { stage: 'production', expectedVersion: prodEnv.version, requireApproval: false })
  expect(off.status).toBe(200)
})
