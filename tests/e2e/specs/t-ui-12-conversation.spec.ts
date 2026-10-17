import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

test('guided request survives reload, requires confirmation, and supports request comments', async ({ sessions }) => {
  test.skip(MOCK, 'Real conversation persistence and permissions are required')
  const page = await sessions.as('rhea.requester')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/assistant`)
  await page.getByRole('button', { name: 'New conversation' }).click()
  await expectNoSeriousViolations(page, 'Guided assistant')
  const send = async (message: string) => {
    await page.locator('#conversation-message').fill(message)
    await page.getByRole('button', { name: 'Send', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeVisible()
  }
  await send('start purchase-approval')
  await expect(page.getByLabel('Your answer: What are you buying?')).toBeVisible()
  const title = `Conversation ${Date.now()}`
  await send(title)
  await page.reload()
  await expect(page.getByLabel('Your answer: Business purpose')).toBeVisible()
  await send('Browser acceptance for the guided assistant.')
  await send('IT')
  await send('invalid amount')
  await expect(page.getByLabel('Your answer: Amount (USD)')).toBeVisible()
  await send('123.45')
  await expect(page.getByRole('heading', { name: /^Review Purchase approval/ })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open request' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Submit request', exact: true }).click()
  const link = page.getByRole('link', { name: 'Open request' })
  await expect(link).toBeVisible()
  const href = await link.getAttribute('href')
  await link.click()
  await expect(page.getByRole('heading', { name: 'Comments', exact: true })).toBeVisible()
  const note = `Reviewed input in assistant ${Date.now()}`
  await page.getByLabel('Add a comment').fill(note)
  await page.getByRole('button', { name: 'Save comment', exact: true }).click()
  await expect(page.getByText(note, { exact: true })).toBeVisible()
  await expectNoSeriousViolations(page, 'Request comments')
  await page.reload()
  await expect(page.getByText(note, { exact: true })).toBeVisible()
  const runId = href!.split('/').at(-1)
  const run = await apiGet<{ input: { title: string; amount: number } }>(page, `/workspaces/${ws}/runs/${runId}`)
  expect(run.input).toMatchObject({ title, amount: 123.45 })
})
