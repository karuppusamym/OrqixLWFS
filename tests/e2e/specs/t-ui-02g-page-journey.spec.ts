import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { workspaceId } from '../support/app'
import { apiRead, createWorkflow, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import { createView, publishView, requestView, slugOf } from '../support/views'

/**
 * UI02g (S111) authoring to runtime: a designer composes an application page from a published view in the page
 * designer, a publisher reviews, publishes and routes it, a requester submits through the pinned chain, a viewer sees
 * the form read-only and the server refuses the same chain, and an outsider cannot open the route.
 */
test('an application page is composed, published, routed and run with the server deciding who may act', async ({ sessions }) => {
  test.setTimeout(240_000)
  if (MOCK) await sessions.mockCall('reset', { demo: true })
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const workflow = await createWorkflow(page, ws, uniq('Page journey'))
  const formName = uniq('Page form')
  const formId = await createView(page, ws, formName, requestView('Purchase', ['submit']))
  const pageId = await createView(page, ws, uniq('Purchase page'), { schemaVersion: 1, kind: 'page', title: 'Buy equipment', context: 'none', layout: 'single', breakpoint: 'md',
    regions: [{ id: 'main', label: 'Main', components: [{ id: 'heading1', type: 'heading', text: 'Buy equipment' }] }], chains: [] })
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  expect(await publishView(page, ws, formId, 1)).toBe(1)

  // Compose in the page designer: a pinned request view submitted to the workflow, a chain and an action bar.
  page = await sessions.as('dana.designer')
  await page.goto(`/w/${ws}/views/${pageId}`)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Purchase page')
  const palette = page.getByRole('region', { name: 'Component palette' })
  await palette.getByRole('button', { name: 'Reusable view' }).click()
  const inspector = page.getByRole('region', { name: 'Component properties' })
  await inspector.getByLabel('Published view').selectOption(formId)
  await expect(inspector.getByLabel('Pinned version')).toHaveValue('1')
  await inspector.getByLabel('Submit to workflow').selectOption(workflow.id)
  const chains = page.getByRole('region', { name: 'Action chains' })
  await chains.getByRole('button', { name: 'Add chain' }).click()
  await chains.getByLabel('Button label').fill('Send request')
  await expect(chains.getByLabel('Step 1 action')).toHaveValue('submit')
  await palette.getByRole('button', { name: 'Action bar' }).click()
  await expect(page.getByRole('region', { name: 'Bindings and problems' })).toContainText('Local checks passed')
  await page.getByRole('group', { name: 'Designer actions' }).getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByText(/Draft revision 2\./)).toBeVisible()
  await expectNoSeriousViolations(page, 'Application page designer')

  // Review, publish and route as the publisher.
  page = await sessions.as('paul.publisher')
  await page.goto(`/w/${ws}/views/${pageId}`)
  await page.getByRole('button', { name: 'Review and publish' }).click()
  const review = page.getByRole('dialog', { name: 'Review publication' })
  await expect(review).toContainText('Buy equipment')
  await review.getByRole('button', { name: 'Publish version 1' }).click()
  await expect(page.getByText(/Published version 1\./)).toBeVisible()
  const slug = slugOf(uniq('buy'))
  const routes = page.getByRole('region', { name: 'Routes' })
  await routes.getByLabel('Route').fill(slug)
  await routes.getByRole('button', { name: 'Pin to route' }).click()
  await expect(routes).toContainText(`/${slug} pins version 1`)

  // A requester submits through the pinned chain; each step runs on the server as the requester.
  page = await sessions.as('rhea.requester')
  await page.goto(`/w/${ws}/apps/${slug}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Buy equipment' })).toBeVisible()
  const title = uniq('Page request')
  await page.getByLabel('What are you buying?').fill(title)
  await page.getByLabel('Business purpose').fill('Desk for the new hire')
  await page.getByLabel('Cost center').selectOption('IT')
  await page.getByLabel('Amount (USD)').fill('240')
  await expectNoSeriousViolations(page, 'Application page runtime')
  await page.getByRole('button', { name: 'Send request' }).click()
  await expect(page.getByText('Send request: completed')).toBeVisible()
  if (!MOCK) {
    const mine = await apiRead<{ items: Array<{ requestTitle?: string | null }> }>(page, `/workspaces/${ws}/me/requests?limit=50`)
    expect(mine.status).toBe(200)
    expect(mine.body.items.some((run) => run.requestTitle === title)).toBe(true)
  }

  // A viewer may open the page but not start the workflow: the form is read-only and the server refuses the chain.
  page = await sessions.as('vic.viewer')
  await page.goto(`/w/${ws}/apps/${slug}`)
  await expect(page.getByText('You cannot start this request.')).toBeVisible()
  await expect(page.getByLabel('What are you buying?')).toBeDisabled()
  await page.getByRole('button', { name: 'Send request' }).click()
  await expect(page.getByText('Send request: stopped')).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: 'Send request: stopped' })).toContainText('failed')

  if (!MOCK) {
    // Not a member of the workspace: the route does not exist for them.
    sessions.allowHttpErrors()
    page = await sessions.as('otto.outsider')
    expect((await apiRead(page, `/workspaces/${ws}/app-pages/${slug}`)).status).toBe(404)
  }
})
