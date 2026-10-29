import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiSend, createWorkflow, publish, uniq } from '../support/api'
import { approvalView, createView, publishView, routePage, slugOf } from '../support/views'

const MAIN = "import { label } from './util.js';\norqix.onData(function (data) { document.getElementById('root').textContent = label(data); });\n"
const UTIL = "export function label(data) { return 'Module says: ' + data['task.requestTitle']; }\n"
const CSS = '#root { font-weight: 600; }\n'
const base64 = (text: string) => Buffer.from(text, 'utf8').toString('base64')

/**
 * UI02 round 3 (S116): a multi-file bundle (an entry module that imports a sibling module, and a stylesheet) is approved
 * with its file hashes and runs in the server-served sandboxed frame, which loads the files only from the version's
 * signed path; anything outside the bundle stays unreachable.
 */
test('an approved multi-file extension bundle runs its modules in the sandboxed frame', async ({ sessions }) => {
  test.skip(MOCK, 'Bundle files are served by the server; mock mode cannot load them into a frame')
  test.setTimeout(240_000)
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const created = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/ui-extensions`, { name: uniq('Module badge') })
  expect(created.status).toBe(201)
  const submitted = await apiSend<{ files: Array<{ path: string; sha256: string }> }>(page, 'POST', `/workspaces/${ws}/ui-extensions/${created.body.id}/versions`, {
    source: '', entry: 'main.js', height: 120, capabilities: { bindings: ['task.requestTitle'], actions: [] },
    files: [{ path: 'main.js', content: base64(MAIN) }, { path: 'util.js', content: base64(UTIL) }, { path: 'theme.css', content: base64(CSS) }],
  })
  expect(submitted.status).toBe(201)
  expect(submitted.body.files.map((file) => file.path)).toEqual(['main.js', 'theme.css', 'util.js'])
  const workflow = await createWorkflow(page, ws, uniq('Bundle journey'))
  const reviewId = await createView(page, ws, uniq('Bundle review'), approvalView())

  page = await sessions.as('wade.admin')
  expect((await apiSend(page, 'POST', `/workspaces/${ws}/ui-extensions/${created.body.id}/versions/1/decision`, { decision: 'approve', note: 'Reviewed the three files', expectedStatus: 'pending' })).status).toBe(200)
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  await publishView(page, ws, reviewId, 1)
  page = await sessions.as('dana.designer')
  const pageId = await createView(page, ws, uniq('Bundle desk'), { schemaVersion: 1, kind: 'page', title: 'Bundle desk', context: 'task', layout: 'single', breakpoint: 'md',
    regions: [{ id: 'main', label: 'Main', components: [
      { id: 'decision', type: 'view', view: { id: reviewId, version: 1 } },
      { id: 'badge', type: 'extension', label: 'Module badge', extension: { id: created.body.id, version: 1 }, bindings: ['task.requestTitle'], chains: [], height: 120 },
    ] }], chains: [] })
  page = await sessions.as('paul.publisher')
  await publishView(page, ws, pageId, 1)
  const slug = slugOf(uniq('bundle'))
  await routePage(page, ws, slug, 0, pageId, 1)

  page = await sessions.as('rhea.requester')
  const title = uniq('Bundle request')
  const started = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/runs`, { workflowId: workflow.id, input: { title, purpose: 'Bundle acceptance', costCenter: 'OPS', amount: 40 } })
  const taskId = (await apiGet<{ tasks: Array<{ id: string; nodeId: string }> }>(page, `/workspaces/${ws}/runs/${started.body.id}`)).tasks.find((task) => task.nodeId === 'manager')!.id

  page = await sessions.as('mani.manager')
  const frameDocument = await page.evaluate(async (url) => {
    const response = await fetch(url, { credentials: 'same-origin' })
    return { csp: response.headers.get('Content-Security-Policy') ?? '', html: await response.text() }
  }, `/api/v1/workspaces/${ws}/ui-extensions/${created.body.id}/versions/1/frame`)
  expect(frameDocument.html).toContain('type="module"')
  expect(frameDocument.html).toMatch(/integrity="sha256-[A-Za-z0-9+/=]+"/)
  expect(frameDocument.csp).toContain("connect-src 'none'")
  expect(frameDocument.csp).toMatch(/script-src [^;]*\/files\/[0-9]+\.[0-9a-f]{64}\//)
  await page.goto(`/w/${ws}/apps/${slug}/${taskId}`)
  const frame = page.frameLocator('iframe[title="Module badge"]')
  await expect(frame.getByText(`Module says: ${title}`)).toBeVisible({ timeout: 20_000 })
})
