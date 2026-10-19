import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { createWorkflow, editDraft, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * DOC08 attachment quarantine (docs/build/S52; listed as not run in S62). A requester attaches a file; an operator
 * quarantines it with a reason (shown on the file), nobody can download it while quarantined, the operator releases it.
 * Real stack only (the mock keeps no request files across page loads).
 */
test.skip(MOCK, 'Needs the real attachment store.')

test('an operator quarantines and releases a request file; download is blocked while quarantined', async ({ sessions }) => {
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const wf = await createWorkflow(designer, ws, 'Purchase approval')
  await editDraft(designer, ws, wf.id, (d) => {
    d.inputSchema.properties.supplierQuote = { type: 'string', title: 'Supplier quote', format: 'attachment', acceptTypes: ['txt'], maxFileBytes: 1024 }
    d.inputSchema.required.push('supplierQuote')
    d.inputUi.order.push('supplierQuote')
  })
  await publish(await sessions.as('paul.publisher'), ws, wf.id)

  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/start/${wf.id}`)
  await requester.getByLabel('What are you buying?').fill(uniq('Quarantine'))
  await requester.getByLabel('Business purpose').fill('File quarantine')
  await requester.getByLabel('Cost center').selectOption('OPS')
  await requester.getByLabel('Amount (USD)').fill('900')
  await requester.getByLabel('Supplier quote', { exact: false }).setInputFiles({ name: 'quote.txt', mimeType: 'text/plain', buffer: Buffer.from('Supplier quote: 900 USD') })
  await requester.getByRole('button', { name: 'Review request' }).click()
  await requester.getByRole('button', { name: 'Submit request' }).click()
  const runUrl = (await requester.getByRole('link', { name: 'View your request' }).getAttribute('href')) as string
  await requester.goto(runUrl)
  await expect(requester.getByTestId('run-files')).toContainText('quote.txt')
  await expect(requester.getByRole('button', { name: 'Download quote.txt' })).toBeEnabled()
  await expect(requester.getByRole('button', { name: 'Quarantine quote.txt' })).toHaveCount(0)

  const operator = await sessions.as('olga.operator')
  await operator.goto(runUrl)
  await operator.getByRole('button', { name: 'Quarantine quote.txt' }).click()
  const dialog = operator.getByRole('dialog', { name: 'Quarantine this file' })
  await dialog.getByRole('button', { name: 'Quarantine file' }).click()
  await expect(dialog.getByText(/at least 3 characters/)).toBeVisible()
  await dialog.getByLabel('Reason').fill('Looks like the wrong supplier')
  await expectNoSeriousViolations(operator, 'Quarantine dialog')
  await dialog.getByRole('button', { name: 'Quarantine file' }).click()
  const files = operator.getByTestId('run-files')
  await expect(files.getByTestId('quarantine-status')).toBeVisible()
  await expect(files).toContainText('Looks like the wrong supplier')
  await expect(operator.getByRole('button', { name: 'Download quote.txt' })).toBeDisabled()
  await expectNoSeriousViolations(operator, 'Request files, quarantined')

  await requester.reload()
  await expect(requester.getByTestId('run-files').getByTestId('quarantine-status')).toBeVisible()
  await expect(requester.getByRole('button', { name: 'Download quote.txt' })).toBeDisabled()

  await operator.getByRole('button', { name: 'Release quote.txt from quarantine' }).click()
  await expect(files.getByTestId('quarantine-status')).toHaveCount(0)
  await expect(operator.getByRole('button', { name: 'Download quote.txt' })).toBeEnabled()
  await requester.reload()
  await expect(requester.getByRole('button', { name: 'Download quote.txt' })).toBeEnabled()
})
