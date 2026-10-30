import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { createWorkflow, editDraft, publish } from '../support/api'
import { openFormTab, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * Shared forms (docs/build/S127): a designer saves the Purchase approval request form as a shared form, inserts it into
 * a new workflow that already asks its own "title" question (the clash is renamed, never overwritten), both workflows
 * are published, and Settings > Shared assets lists both under "Where it is used".
 *
 * Runs in mock mode and on the real stack.
 */
test('a designer reuses the purchase request form in another workflow and sees both where it is used', async ({ sessions }) => {
  test.setTimeout(240_000)
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const suffix = Date.now().toString(36)
  const formName = `Purchase request ${suffix}`

  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const purchase = await createWorkflow(page, ws, `Purchase approval ${suffix}`)
  const equipment = await createWorkflow(page, ws, `Equipment request ${suffix}`)
  // The new workflow starts with one question of its own; its key clashes with the purchase form's "title".
  await editDraft(page, ws, equipment.id, (d) => {
    d.inputSchema = { type: 'object', additionalProperties: false, required: ['title'], properties: { title: { type: 'string', title: 'Job title', maxLength: 80 } } }
    d.inputUi = { order: ['title'], currency: 'USD' }
  })

  // 1. Save the purchase request form as a shared form ---------------------------------------------------------------
  await page.goto(`/w/${ws}/workflows/${purchase.id}/edit`)
  await expect(page.getByTestId('studio')).toBeVisible()
  await openFormTab(page)
  await page.getByRole('button', { name: 'Save as shared form' }).click()
  const share = page.getByRole('form', { name: 'Save as shared form' })
  await share.getByLabel(/Shared name/).fill(formName)
  const key = await share.getByLabel(/Shared key/).inputValue()
  expect(key).toBe(`purchase-request-${suffix}`)
  await expectNoSeriousViolations(page, 'Studio Form tab, save as shared form')
  await share.getByRole('button', { name: 'Save shared form' }).click()
  await expect(page.getByText(`Saved as shared form ${formName}, version 1`)).toBeVisible()
  const link = page.getByTestId('shared-form-link')
  await expect(link).toContainText('Up to date')
  await expect(link).toContainText('4 questions copied')
  await page.keyboard.press('Control+s')
  await expect(page.getByTestId('save-state')).toHaveText(/Saved/)

  // 2. Insert it into the new workflow: the clashing key must be renamed or skipped first ---------------------------
  await page.goto(`/w/${ws}/workflows/${equipment.id}/edit`)
  await expect(page.getByTestId('studio')).toBeVisible()
  await openFormTab(page)
  const insert = page.getByTestId('insert-shared-form')
  await insert.getByLabel('Insert a shared form').selectOption(key)
  const clashes = insert.getByTestId('shared-form-clashes')
  await expect(clashes).toContainText('This form already asks “Job title” with the key title.')
  const rename = clashes.getByLabel('New key for What are you buying?')
  await rename.fill('purpose')
  await expect(clashes.getByRole('alert')).toContainText('also has a question with the key "purpose"')
  await expect(insert.getByRole('button', { name: 'Insert shared form v1' })).toBeDisabled()
  await rename.fill('item')
  await expectNoSeriousViolations(page, 'Studio Form tab, insert a shared form with a key clash')
  await insert.getByRole('button', { name: 'Insert shared form v1' }).click()
  await expect(page.getByText(`Inserted 4 questions from ${formName}, version 1. Renamed: title as item.`)).toBeVisible()
  await expect(page.getByTestId('question-title')).toContainText('Job title')
  await expect(page.getByTestId('question-item')).toContainText('What are you buying?')
  await expect(page.getByTestId('shared-form-link')).toContainText('renamed here: title as item')
  await page.getByRole('button', { name: 'Check workflow' }).click()
  await expect(page.getByTestId('check-summary')).toContainText(/No problems found|0 blockers/)
  await page.keyboard.press('Control+s')
  await expect(page.getByTestId('save-state')).toHaveText(/Saved/)

  // 3. Both workflows are published (the copied questions pass the same checks as any other) -----------------------
  page = await sessions.as('paul.publisher')
  await publish(page, ws, purchase.id)
  await publish(page, ws, equipment.id)

  // 4. Settings > Shared assets lists the form with both workflows under "Where it is used" -------------------------
  page = await sessions.as('dana.designer')
  await page.goto(`/w/${ws}/settings`)
  await page.getByRole('tab', { name: 'Shared assets' }).click()
  const search = page.getByRole('search', { name: 'Search shared assets' })
  await search.getByLabel('Kind').selectOption('form')
  await search.getByLabel('Name, key or owner').fill(key)
  await search.getByRole('button', { name: 'Search' }).click()
  const row = page.getByTestId('asset-row').filter({ hasText: formName })
  await expect(row).toHaveCount(1)
  await expect(row).toContainText('Form')
  await row.getByRole('button').first().click()
  const detail = row.getByTestId('asset-detail')
  await expect(detail.getByTestId('form-version-summary')).toContainText('4 questions: What are you buying?, Business purpose, Cost center, Amount (USD)')
  const usage = detail.getByTestId('asset-usage')
  await expect(usage).toHaveCount(2, { timeout: 20_000 })
  for (const wf of [purchase, equipment]) {
    await expect(usage.filter({ hasText: wf.name })).toContainText('Version 1 (release 1)')
  }
  await expectNoSeriousViolations(page, 'Shared assets, shared form where it is used')
})
