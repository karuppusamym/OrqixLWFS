import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'

/**
 * Demo 7 through the browser (mock mode; the real provider path is tools/api-e2e/e2e_ai.py):
 * Describe it -> generation runs in the background -> AI review panel -> Go to step -> fix the missing group ->
 * Check workflow -> the publisher reviews and publishes.
 *
 * The mock API answers like the server: 202 with a job, then polling until it succeeds after a few seconds.
 */
test.skip(!MOCK, 'The AI draft path against the real provider is tools/api-e2e/e2e_ai.py (scenarios 30-31)')

test('describe it, review the AI draft, fix the missing group, publish', async ({ sessions }) => {
  test.setTimeout(120_000)
  await sessions.mockCall('reset', { published: false, sampleRuns: false })

  // 1. The designer describes the workflow ---------------------------------------------------------------------------
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  await designer.goto(`/w/${ws}/workflows`)
  await designer.getByRole('button', { name: /New workflow/ }).click()
  await designer.getByTestId('describe-it-choice').getByRole('button', { name: 'Describe it' }).click()
  const dialog = designer.getByRole('dialog', { name: 'Describe your workflow' })
  await dialog.getByRole('button', { name: /Use the example: Access request/ }).click()
  await dialog.getByRole('button', { name: /Generate draft/ }).click()
  await expect(dialog.getByTestId('describe-progress')).toContainText(/This can take up to a minute/)

  // 2. The studio opens with the review panel ------------------------------------------------------------------------
  await expect(designer.getByTestId('studio')).toBeVisible({ timeout: 30_000 })
  const review = designer.getByTestId('ai-review')
  await expect(review.getByRole('heading', { name: /AI-drafted — review every step before publishing/ })).toBeVisible()
  const missing = review.getByRole('list', { name: 'Missing configuration' })
  await expect(missing).toContainText('Create the group "security-team"')
  await expect(review.getByRole('list', { name: 'Assumptions' })).toContainText('one after the other')
  const workflowId = new URL(designer.url()).pathname.split('/')[4]!

  // 3. Go to step opens the step that needs the group; the designer picks an existing one ------------------------------
  await missing.getByRole('button', { name: /Go to step Second review/ }).click()
  const group = designer.getByTestId('inspector').locator('[data-field="config.candidateGroupRef"]')
  await expect(group).toBeVisible()
  await expect(group).toHaveValue('security-team')
  await group.selectOption('finance-approvers')
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/, { timeout: 10_000 })

  // 4. Check workflow: no blockers left -------------------------------------------------------------------------------
  await designer.getByRole('button', { name: 'Check workflow' }).click()
  await expect(designer.getByTestId('check-summary')).toContainText(/No problems found/)

  // 5. Explain, in plain language ---------------------------------------------------------------------------------------
  await designer.getByRole('button', { name: /^Explain$/ }).click()
  await expect(designer.getByTestId('explain-summary')).toContainText(/reviewed by/)
  await designer.getByRole('button', { name: 'Close the explanation' }).click()

  // 6. The publisher reviews and publishes; AI never publishes by itself -----------------------------------------------
  const publisher = await sessions.as('paul.publisher')
  await publisher.goto(`/w/${ws}/workflows/${workflowId}`)
  await publisher.getByRole('link', { name: /Review and publish/ }).click()
  await expect(publisher.getByTestId('check-clean')).toBeVisible()
  await publisher.getByRole('button', { name: /Publish version \d+/ }).click()
  await expect(publisher.getByTestId('publish-success')).toContainText(/Version \d+ published/)
})

test('closing the dialog keeps drafting and a notice opens the draft when it is ready', async ({ sessions }) => {
  test.setTimeout(120_000)
  await sessions.mockCall('reset', { published: false, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  await designer.goto(`/w/${ws}/workflows`)
  await designer.getByRole('button', { name: /New workflow/ }).click()
  await designer.getByTestId('describe-it-choice').getByRole('button', { name: 'Describe it' }).click()
  const dialog = designer.getByRole('dialog', { name: 'Describe your workflow' })
  await dialog.getByRole('button', { name: /Use the example: Purchase approval/ }).click()
  await dialog.getByRole('button', { name: /Generate draft/ }).click()
  await dialog.getByRole('button', { name: 'Close and keep drafting' }).click()

  const toast = designer.getByTestId('ai-job-toast')
  await expect(toast).toContainText(/We'll let you know when the draft is ready/)
  await expect(toast).toContainText('Your draft is ready', { timeout: 30_000 })
  await toast.getByRole('button', { name: 'Open draft' }).click()
  await expect(designer.getByTestId('studio')).toBeVisible()
  await expect(designer.getByTestId('ai-review')).toBeVisible()
})
