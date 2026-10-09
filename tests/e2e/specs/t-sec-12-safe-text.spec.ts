import { expect, test } from '../support/fixtures'
import { apiGet, decideOpenTask, openTask, workspaceId } from '../support/app'
import { MOCK } from '../support/env'

test('HTML and scripts in labels, request values and decision reasons render as text', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })
  const label = '<img src=x onerror="window.__orqixXss=1">'
  const reason = '<script>window.__orqixXss=2</script>'
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflow = await designer.evaluate(async ({ ws, label }) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=')
    const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'application/json' }
    if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    const base = '/api/v1/workspaces/' + ws + '/workflows'
    const created = await fetch(base, { method: 'POST', headers, body: JSON.stringify({
      key: 'xss-' + crypto.randomUUID().slice(0, 8), name: label, templateId: 'purchase-approval',
    }) })
    if (created.status !== 201) throw new Error('Create test workflow: ' + created.status)
    const workflow = await created.json() as { id: string }
    const response = await fetch(base + '/' + workflow.id + '/draft')
    const draft = await response.json() as { revision: number; definition: { inputSchema: { properties: { title: { title: string } } } } }
    draft.definition.inputSchema.properties.title.title = label
    const saved = await fetch(base + '/' + workflow.id + '/draft', { method: 'PUT',
      headers: { ...headers, 'If-Match': '"r' + draft.revision + '"' }, body: JSON.stringify({ definition: draft.definition }),
    })
    if (saved.status !== 200) throw new Error('Save test label: ' + saved.status)
    const next = await saved.json() as { revision: number }
    return { id: workflow.id, revision: next.revision }
  }, { ws, label })
  const publisher = await sessions.as('paul.publisher')
  await publisher.evaluate(async ({ ws, workflow }) => {
    const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=')
    const headers: Record<string, string> = { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }
    if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
    const response = await fetch('/api/v1/workspaces/' + ws + '/workflows/' + workflow.id + '/publish', {
      method: 'POST', headers, body: JSON.stringify({ draftRevision: workflow.revision, acknowledgeWarnings: true }),
    })
    if (response.status !== 201) throw new Error('Publish test workflow: ' + response.status)
  }, { ws, workflow })
  const requester = await sessions.as('rhea.requester')
  await requester.goto(`/w/${ws}/start/${workflow.id}`)
  await expect(requester.getByRole('heading', { level: 1, name: label })).toBeVisible()
  await requester.getByRole('textbox', { name: label }).fill(label)
  await requester.getByLabel('Business purpose').fill(reason)
  await requester.getByLabel('Cost center').selectOption('OPS')
  await requester.getByLabel('Amount (USD)').fill('100')
  await requester.getByRole('button', { name: 'Review request' }).click()
  await expect(requester.locator('main img, main script')).toHaveCount(0)
  await requester.getByRole('button', { name: 'Submit request' }).click()
  await expect(requester.getByRole('heading', { name: 'Request submitted' })).toBeVisible()
  const href = await requester.getByRole('link', { name: 'View your request' }).getAttribute('href')
  expect(href).toBeTruthy()
  const manager = await sessions.as('mani.manager')
  await openTask(manager, ws, label)
  await decideOpenTask(manager, 'reject', reason)
  await expect(manager.locator('main img, main script')).toHaveCount(0)
  const returned = await sessions.as('rhea.requester')
  await returned.goto(href!)
  await expect(returned.getByText(reason, { exact: true }).first()).toBeVisible()
  await expect(returned.locator('main img, main script')).toHaveCount(0)
  expect(await returned.evaluate(() => (window as unknown as Record<string, unknown>).__orqixXss)).toBeUndefined()
  const runId = href!.split('/').pop()!
  const run = await apiGet<{ input: { title: string; purpose: string } }>(returned, `/workspaces/${ws}/runs/${runId}`)
  expect(run.input.title).toBe(label)
  expect(run.input.purpose).toBe(reason)
})
