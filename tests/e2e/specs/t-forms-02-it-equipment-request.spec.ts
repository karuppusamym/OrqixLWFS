import type { Locator, Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { editDraft } from '../support/api'
import { apiGet, decideOpenTask, openFormTab, openTask, workspaceId } from '../support/app'

/**
 * A real use case built from scratch (docs/build/S120): an IT equipment request.
 *
 * - Designer: starts an empty workflow, lays out the steps, then authors the request form on the Form tab: a choice
 *   with an "Other" detail, a cost that asks for a justification from USD 1,000, and a patterned asset tag for
 *   replacements, in three groups. Every rule is checked in the live preview before publishing.
 * - Requests: a USD 900 laptop needs only the manager. A USD 2,600 "other" item replacing a broken device also needs
 *   finance, and the asset tag must match IT-12345.
 * - Server: hidden answers are never stored.
 *
 * Runs in mock mode and on the real stack.
 */

type Kind = 'text' | 'longText' | 'money' | 'yesNo' | 'choice'

async function addQuestion(page: Page, q: { label: string; kind: Kind; key: string; required?: boolean; choices?: string[] }): Promise<Locator> {
  await page.getByRole('button', { name: 'Add a question' }).click()
  let card = page.locator('[data-testid^="field-card-"]').last()
  await card.getByLabel(/^Label/).fill(q.label)
  await card.getByLabel(/^Label/).press('Enter')
  await card.getByLabel('Type', { exact: true }).selectOption(q.kind)
  if (q.choices) {
    await card.getByLabel('Choices').fill(q.choices.join('\n'))
    await card.getByLabel('Choices').blur()
  }
  if (q.required) await card.getByRole('checkbox', { name: 'Required', exact: true }).check()
  await card.locator('summary', { hasText: 'Advanced' }).click()
  await card.getByLabel('Field key').fill(q.key)
  await card.getByLabel('Field key').press('Enter')
  card = page.getByTestId(`field-card-${q.key}`)
  await expect(card).toBeVisible()
  return card
}

/** One rule on an open question. The "Show only when…" editor comes first, "Require an answer when…" after it. */
async function addRule(card: Locator, which: 'show' | 'require', rule: { field: string; operator?: string; value: string; select?: boolean }): Promise<void> {
  const pick = (l: Locator) => (which === 'show' ? l.first() : l.last())
  await card.getByRole('checkbox', { name: which === 'show' ? 'Show only when a condition matches' : 'Require an answer when a condition matches' }).check()
  await pick(card.getByLabel('Value to look at')).selectOption(rule.field)
  if (rule.operator) await pick(card.getByLabel('Comparison')).selectOption(rule.operator)
  const value = pick(card.getByLabel('Value', { exact: true }))
  if (rule.select) await value.selectOption(rule.value)
  else {
    await value.fill(rule.value)
    await value.press('Enter')
  }
}

async function submitRequest(page: Page, ws: string, workflowId: string, name: string, fill: (page: Page) => Promise<void>): Promise<string> {
  await page.goto(`/w/${ws}/start/${workflowId}`)
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
  await fill(page)
  await page.getByRole('button', { name: 'Review request', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Check your request' })).toBeVisible()
  await page.getByRole('button', { name: 'Submit request', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Request submitted' })).toBeVisible()
  await expect(page.getByText(/Waiting for Managers/)).toBeVisible()
  return (await page.getByRole('link', { name: 'View your request' }).getAttribute('href')) ?? ''
}

test('IT equipment request: form rules authored on the Form tab, published, and requests routed by cost', async ({ sessions }) => {
  test.setTimeout(300_000)
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const suffix = Date.now().toString(36)
  const name = `IT equipment request ${suffix}`

  // 1. Designer starts an empty workflow from the New workflow dialog ------------------------------------------------
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}/workflows`)
  await page.getByRole('button', { name: 'New workflow' }).click()
  await page.getByRole('button', { name: /Start empty/ }).click()
  await page.getByLabel(/^Name/).fill(name)
  await page.getByRole('button', { name: 'Create workflow' }).click()
  await expect(page.getByTestId('studio')).toBeVisible()
  const workflowId = new URL(page.url()).pathname.split('/').at(-2)!

  // The steps: manager review, finance sign-off from USD 2,000, a notification on each outcome.
  await editDraft(page, ws, workflowId, (d) => {
    const start = d.nodes.find((n: { type: string }) => n.type === 'start')
    // "Start empty" must give a request form step: the Studio cannot add one (fixed in the backend with S120).
    if (!start) throw new Error('Start empty created no request form step')
    start.label = 'Equipment requested'
    d.nodes = [
      start,
      { id: 'manager', type: 'approval', label: 'Manager review', config: { candidateGroupRef: 'managers', rejectReasonRequired: true, dueAfterHours: 24 } },
      { id: 'costCheck', type: 'branch', label: 'Finance sign-off needed?', config: {} },
      { id: 'finance', type: 'approval', label: 'Finance sign-off', config: { candidateGroupRef: 'finance-approvers', rejectReasonRequired: true, dueAfterHours: 48 } },
      { id: 'notifyApproved', type: 'notification', label: 'Tell the requester it is approved', config: { channel: 'inApp', recipient: 'initiator', message: 'Your IT equipment request was approved. IT will contact you about delivery.' } },
      { id: 'approved', type: 'end', label: 'Approved', config: { businessOutcome: 'Approved', outcomeKind: 'success' } },
      { id: 'notifyRejected', type: 'notification', label: 'Tell the requester it was rejected', config: { channel: 'inApp', recipient: 'initiator', message: 'Your IT equipment request was rejected. Open the request to see the reason.' } },
      { id: 'rejected', type: 'end', label: 'Rejected', config: { businessOutcome: 'Rejected', outcomeKind: 'rejected' } },
    ]
    d.transitions = [
      { id: 't1', from: start.id, to: 'manager', on: 'next' },
      { id: 't2', from: 'manager', to: 'costCheck', on: 'approved' },
      { id: 't3', from: 'manager', to: 'notifyRejected', on: 'rejected' },
      { id: 't4', from: 'costCheck', to: 'finance', on: 'condition', label: 'USD 2,000 or more', condition: { fieldRef: '/input/estimatedCost', operator: 'gte', value: 2000 } },
      { id: 't5', from: 'costCheck', to: 'notifyApproved', on: 'default', label: 'Under USD 2,000' },
      { id: 't6', from: 'finance', to: 'notifyApproved', on: 'approved' },
      { id: 't7', from: 'finance', to: 'notifyRejected', on: 'rejected' },
      { id: 't8', from: 'notifyApproved', to: 'approved', on: 'next' },
      { id: 't9', from: 'notifyRejected', to: 'rejected', on: 'next' },
    ]
    delete d.uiLayout
  })
  await page.reload()
  await expect(page.getByTestId('studio')).toBeVisible()

  // 2. The request form on the Form tab ---------------------------------------------------------------------------
  await openFormTab(page)
  // An empty workflow may start with a placeholder question; the form below is built from nothing.
  for (const remove of await page.getByRole('button', { name: /^Delete "/ }).all()) await remove.click()
  await addQuestion(page, { label: 'Short title', kind: 'text', key: 'title', required: true })
  await addQuestion(page, { label: 'What do you need?', kind: 'choice', key: 'item', required: true, choices: ['Laptop', 'Monitor', 'Phone', 'Headset', 'Other'] })
  const other = await addQuestion(page, { label: 'Describe the item', kind: 'text', key: 'otherItem' })
  await addRule(other, 'show', { field: '/input/item', value: 'Other', select: true })
  await addRule(other, 'require', { field: '/input/item', value: 'Other', select: true })
  await addQuestion(page, { label: 'Estimated cost (USD)', kind: 'money', key: 'estimatedCost', required: true })
  const justification = await addQuestion(page, { label: 'Business justification', kind: 'longText', key: 'justification' })
  await addRule(justification, 'show', { field: '/input/estimatedCost', operator: 'gte', value: '1000' })
  await addRule(justification, 'require', { field: '/input/estimatedCost', operator: 'gte', value: '1000' })
  await addQuestion(page, { label: 'Replacing a broken device?', kind: 'yesNo', key: 'replacement' })
  const tag = await addQuestion(page, { label: 'Asset tag of the old device', kind: 'text', key: 'assetTag' })
  await tag.getByLabel(/Answer pattern/).fill('^IT-\\d{5}$')
  await tag.getByLabel(/Answer pattern/).press('Enter')
  await tag.getByLabel(/Message when the answer does not match/).fill('Enter the tag on the device, for example IT-12345.')
  await tag.getByLabel(/Message when the answer does not match/).press('Enter')
  await addRule(tag, 'show', { field: '/input/replacement', value: 'true', select: true })
  await addRule(tag, 'require', { field: '/input/replacement', value: 'true', select: true })
  // Grouped questions come before ungrouped ones on the form, so every question goes in a group, in order.
  const groups: Array<[string, string[]]> = [
    ['What you need', ['Short title', 'What do you need?', 'Describe the item']],
    ['Cost', ['Estimated cost (USD)', 'Business justification']],
    ['Replacement', ['Replacing a broken device?', 'Asset tag of the old device']],
  ]
  for (const [index, [title, questions]] of groups.entries()) {
    await page.getByRole('button', { name: 'Add a group' }).click()
    const group = page.getByRole('group', { name: `Group ${index + 1}` })
    await group.getByLabel('Group title').fill(title)
    await group.getByLabel('Group title').press('Enter')
    for (const question of questions) await group.getByRole('checkbox', { name: `Include ${question}` }).check()
  }

  // Every rule reads in plain words on its row ...
  await expect(page.getByTestId('question-otherItem')).toContainText('Shown only when What do you need? is "Other"')
  await expect(page.getByTestId('question-justification')).toContainText('Required when Estimated cost (USD) is at least 1000')
  await expect(page.getByTestId('question-assetTag')).toContainText('In group "Replacement"')
  await expect(page.getByTestId('question-assetTag')).toContainText('Shown only when Replacing a broken device? is Yes')
  // ... and works in the live preview.
  const preview = page.getByTestId('form-preview')
  const status = page.getByTestId('form-preview-status')
  await expect(status).toHaveText('Showing 4 of 7 questions. Hidden by rules with these answers: Describe the item, Business justification, Asset tag of the old device.')
  await preview.getByLabel(/What do you need\?/).selectOption('Other')
  await preview.getByLabel(/Estimated cost/).fill('1500')
  await preview.getByLabel(/Replacing a broken device\?/).check()
  await expect(status).toHaveText('Showing all 7 questions.')
  await expect(preview.getByRole('group', { name: 'Replacement' })).toBeVisible()
  await preview.getByLabel(/Asset tag of the old device/).fill('IT-12')
  await preview.getByRole('button', { name: 'Check my answers' }).click()
  await expect(preview.locator('[data-field-error]').filter({ hasText: 'Enter the tag on the device, for example IT-12345.' })).toBeVisible()
  await preview.getByRole('button', { name: 'Clear answers' }).click()
  await expect(status).toContainText('Showing 4 of 7 questions.')

  await page.getByRole('button', { name: 'Check workflow' }).click()
  await expect(page.getByTestId('check-summary')).toContainText(/No problems found|0 blockers/)
  await page.keyboard.press('Control+s')
  await expect(page.getByTestId('save-state')).toHaveText(/Saved/)
  const draft = await apiGet<{ revision: number }>(page, `/workspaces/${ws}/workflows/${workflowId}/draft`)

  // 3. Publisher publishes version 1 ----------------------------------------------------------------------------------
  page = await sessions.as('paul.publisher')
  await page.goto(`/w/${ws}/workflows/${workflowId}/publish?revision=${draft.revision}`)
  const warnings = page.getByRole('checkbox', { name: /warnings/i })
  if (await warnings.isVisible()) await warnings.check()
  await page.getByRole('button', { name: /^Publish version/ }).click()
  await expect(page.getByRole('heading', { name: 'Version 1 published', exact: true })).toBeVisible()

  // 4. A USD 900 laptop: no justification asked, manager only -----------------------------------------------------------
  let requester = await sessions.as('rhea.requester')
  const laptopTitle = `Laptop for new analyst ${suffix}`
  const laptopUrl = await submitRequest(requester, ws, workflowId, name, async (p) => {
    await p.getByLabel('Short title').fill(laptopTitle)
    await p.getByLabel('What do you need?').selectOption('Laptop')
    await expect(p.getByLabel(/Describe the item/)).toHaveCount(0)
    await p.getByLabel(/Estimated cost/).fill('900')
    await expect(p.getByLabel(/Business justification/)).toHaveCount(0)
    await expect(p.getByLabel(/Asset tag/)).toHaveCount(0)
  })
  // Mock mode shares one page between people: every step names who acts (sessions.as) right before acting.
  let manager = await sessions.as('max.manager')
  await openTask(manager, ws, laptopTitle)
  await decideOpenTask(manager, 'approve', 'Standard analyst laptop.')
  requester = await sessions.as('rhea.requester')
  await requester.goto(laptopUrl)
  await expect(requester.getByTestId('run-status')).toContainText('Completed · Approved', { timeout: 90_000 })
  const laptopRun = await apiGet<{ input: Record<string, unknown> }>(requester, `/workspaces/${ws}/runs/${laptopUrl.split('/').at(-1)}`)
  expect(laptopRun.input).toMatchObject({ title: laptopTitle, item: 'Laptop', estimatedCost: 900 })
  for (const hidden of ['otherItem', 'justification', 'assetTag']) expect(laptopRun.input).not.toHaveProperty(hidden)

  // 5. A USD 2,600 replacement docking station: justification, asset tag pattern, manager then finance ----------------
  const dockTitle = `Docking station replacement ${suffix}`
  const dockUrl = await submitRequest(requester, ws, workflowId, name, async (p) => {
    await p.getByLabel('Short title').fill(dockTitle)
    await p.getByLabel('What do you need?').selectOption('Other')
    await p.getByLabel(/Describe the item/).fill('Docking station with two 27-inch monitors')
    await p.getByLabel(/Estimated cost/).fill('2600')
    await p.getByLabel(/Business justification/).fill('The current dock failed; the analyst works on large models across two screens.')
    await p.getByLabel(/Replacing a broken device\?/).check()
    await p.getByLabel(/Asset tag of the old device/).fill('IT-12')
    await p.getByRole('button', { name: 'Review request', exact: true }).click()
    await expect(p.getByTestId('error-summary')).toContainText('Enter the tag on the device, for example IT-12345.')
    await p.getByLabel(/Asset tag of the old device/).fill('IT-00421')
  })
  manager = await sessions.as('max.manager')
  await openTask(manager, ws, dockTitle)
  await decideOpenTask(manager, 'approve', 'Needed for the analytics desk.')
  const finance = await sessions.as('fin.finance')
  await openTask(finance, ws, dockTitle)
  await decideOpenTask(finance, 'approve', 'Within the IT budget.')
  requester = await sessions.as('rhea.requester')
  await requester.goto(dockUrl)
  await expect(requester.getByTestId('run-status')).toContainText('Completed · Approved', { timeout: 90_000 })
  await expect(requester.getByTestId('timeline')).toContainText('Finance sign-off')
  const dockRun = await apiGet<{ input: Record<string, unknown> }>(requester, `/workspaces/${ws}/runs/${dockUrl.split('/').at(-1)}`)
  expect(dockRun.input).toMatchObject({ item: 'Other', estimatedCost: 2600, replacement: true, assetTag: 'IT-00421' })
})
