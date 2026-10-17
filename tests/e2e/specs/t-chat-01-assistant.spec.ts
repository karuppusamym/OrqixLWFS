import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

/**
 * T-CHAT-01: a requester starts a purchase through the Assistant by answering one question at a time, reviews it and
 * submits it explicitly; a manager then reviews the request and approves it in a separate confirmation. Runs in mock
 * mode (the Assistant's in-page API follows the backend's guided commands) and, with the matching build, on the real
 * stack. Keyboard only for the answers, a phone-width check, and an accessibility scan on the way.
 */
test('purchase through the Assistant, then a manager decides the task', async ({ sessions }) => {
  test.setTimeout(120_000)
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const title = `Assistant desk ${Date.now()}`

  // Requester ------------------------------------------------------------------------------------------------------
  const rhea = await sessions.as('rhea.requester')
  const ws = await workspaceId(rhea)
  await rhea.goto(`/w/${ws}/assistant`)
  await rhea.getByRole('button', { name: 'New conversation' }).click()
  await expect(rhea.getByRole('heading', { name: 'How can I help?' })).toBeVisible()
  await expect(rhea.getByText(/JSON|fieldName/)).toHaveCount(0)
  await expectNoSeriousViolations(rhea, 'Assistant start')

  await rhea.getByRole('button', { name: 'Start a request' }).click()
  await rhea.getByRole('button', { name: 'Purchase approval', exact: true }).click()

  const answer = async (label: string, value: string) => {
    const box = rhea.getByLabel(`Your answer: ${label}`)
    await expect(box).toBeFocused() // focus follows each reply, so the whole flow works from the keyboard
    await rhea.keyboard.type(value)
    await rhea.keyboard.press('Enter')
  }
  await answer('What are you buying?', title)
  await answer('Business purpose', 'Back support for a long day.')
  await rhea.getByRole('button', { name: 'IT', exact: true }).click()
  await answer('Amount (USD)', 'lots')
  await expect(rhea.getByText(/Please enter Amount \(USD\) as a number/).first()).toBeVisible()
  await answer('Amount (USD)', '1,200')

  const review = rhea.getByRole('heading', { name: /^Review Purchase approval/ })
  await expect(review).toBeVisible()
  await expect(rhea.getByText('Cost center', { exact: true })).toBeVisible()
  await expect(rhea.getByText('costCenter')).toHaveCount(0)
  await expect(rhea.getByRole('link', { name: 'Open request' })).toHaveCount(0)
  await expectNoSeriousViolations(rhea, 'Assistant review')

  // Phone width: no sideways scrolling and the primary action stays reachable.
  await rhea.setViewportSize({ width: 375, height: 800 })
  await expect.poll(() => rhea.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
  await expect(rhea.getByRole('button', { name: 'Submit request' })).toBeVisible()
  await rhea.setViewportSize({ width: 1440, height: 900 })

  await rhea.getByRole('button', { name: 'Change Amount (USD)' }).click()
  await rhea.getByLabel('New value for Amount (USD)').fill('1300')
  await rhea.getByRole('button', { name: 'Save change' }).click()
  await expect(rhea.getByText('1300', { exact: true })).toBeVisible()

  await rhea.getByRole('button', { name: 'Submit request' }).click()
  const open = rhea.getByRole('link', { name: 'Open request' })
  await expect(open).toBeVisible()
  await expect(rhea.getByRole('button', { name: 'Submit request' })).toHaveCount(0)

  // Manager ----------------------------------------------------------------------------------------------------------
  const mani = await sessions.as('mani.manager')
  await mani.goto(`/w/${ws}/assistant`)
  await mani.getByRole('button', { name: 'New conversation' }).click()
  await mani.getByRole('button', { name: 'My tasks' }).click()
  await mani.getByRole('button', { name: new RegExp(title) }).click()
  await expect(mani.getByRole('heading', { name: /^Review ORQ-/ })).toBeVisible()
  await expect(mani.getByText(title, { exact: true }).first()).toBeVisible()
  await expect(mani.getByRole('button', { name: /^Confirm/ })).toHaveCount(0) // the request is shown first, nothing prepared
  await expectNoSeriousViolations(mani, 'Assistant task review')

  await mani.getByRole('button', { name: 'Approve', exact: true }).click()
  await expect(mani.getByRole('button', { name: 'Confirm approval' })).toBeVisible() // a separate, explicit step
  await mani.getByRole('button', { name: 'Confirm approval' }).click()
  await expect(mani.getByText(/You approved ORQ-/)).toBeVisible()
  await expect(mani.getByRole('button', { name: 'Confirm approval' })).toHaveCount(0)

  // The same task is no longer offered.
  await mani.getByRole('button', { name: 'My tasks' }).click()
  await expect(mani.getByRole('button', { name: new RegExp(title) })).toHaveCount(0)
})
