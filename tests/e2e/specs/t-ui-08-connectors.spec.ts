import { expect, test } from '../support/fixtures'
import { createPurchaseWorkflow, selectStep, workspaceId } from '../support/app'

test('connector result rename is one undoable designer edit', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflow = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflow}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  await designer.getByRole('tab', { name: 'Steps' }).click()
  await selectStep(designer, 'procure')
  await expect(designer.getByLabel(/^Operation/)).toHaveValue('createOrder')
  await expect(designer.locator('[data-field="config.inputMapping.amount"]')).toHaveValue('/input/amount')
  const resultName = designer.getByRole('group', { name: 'Result fields to keep' }).getByRole('textbox', { name: 'as', exact: true })
  await expect(resultName).toHaveValue('procurementId')
  await resultName.fill('orderId')
  await resultName.press('Enter')
  await expect(resultName).toHaveValue('orderId')
  await designer.keyboard.press('Control+z')
  await expect(resultName).toHaveValue('procurementId')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/)
})

test('administrator can follow connection setup guidance and run a read-only test', async ({ sessions }) => {
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  await admin.goto(`/w/${ws}/settings`)
  await admin.getByRole('tab', { name: 'Connections' }).click()
  await expect(admin.getByRole('heading', { name: 'Connect a workflow to another system' })).toBeVisible()
  const testButton = admin.getByRole('button', { name: 'Test connection', exact: true })
  await expect(testButton).toBeEnabled()
  await testButton.click()
  await expect(admin.getByText('The other system answered', { exact: true })).toBeVisible()
})
