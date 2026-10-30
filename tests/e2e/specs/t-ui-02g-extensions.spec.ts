import { type Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { BASE_URL, MOCK } from '../support/env'
import { apiGet, workspaceId } from '../support/app'
import { apiSend, createWorkflow, publish, uniq } from '../support/api'
import { expectNoSeriousViolations } from '../support/axe'
import { approvalView, createView, publishView, routePage, slugOf } from '../support/views'

/** Shows the granted request title and asks the host to run the granted chain. No markup, imports or network. */
const BADGE = `(function () {
  orqix.onData(function (data) {
    var root = document.getElementById('root');
    root.textContent = '';
    var line = document.createElement('p');
    line.textContent = 'Request: ' + data['task.requestTitle'];
    root.appendChild(line);
    var button = document.createElement('button');
    button.type = 'button';
    button.textContent = 'Approve from extension';
    button.onclick = function () { orqix.run('approveNow').then(function (result) { line.textContent = result.completed ? 'Approved by the chain' : 'Not approved'; }); };
    root.appendChild(button);
  });
})();`

/**
 * Leaves its approved document once it has data. The bridge reports unloading first, so the host removes the frame and
 * sends nothing to the next document. (A busy-looping bundle is not used: Chromium may run a same-site sandboxed frame
 * in the page's process, where the loop would stall the host page too.)
 */
const LEAVING = `(function () { orqix.onData(function () { setTimeout(function () { location.reload(); }, 300); }); })();`

async function register(page: Page, name: string, source: string): Promise<void> {
  await page.getByRole('button', { name: 'Register extension' }).click()
  const dialog = page.getByRole('dialog', { name: 'Register extension' })
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: 'Register' }).click()
  await expect(dialog).toBeHidden()
  const card = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) })
  await card.getByRole('button', { name: 'Submit version' }).click()
  const submit = page.getByRole('dialog', { name: `Submit a version of ${name}` })
  await submit.getByLabel('Bundle source (JavaScript using the orqix bridge)').fill(source)
  await submit.getByLabel('Data it may receive (one binding per line)').fill('task.requestTitle')
  await submit.getByLabel('Claim task').check()
  await submit.getByLabel('Approve', { exact: true }).check()
  await submit.getByRole('button', { name: 'Submit for approval' }).click()
  await expect(submit).toBeHidden()
  await expect(card).toContainText('pending')
}

async function decide(page: Page, name: string, decision: 'Approve' | 'Revoke'): Promise<void> {
  const card = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name }) })
  await card.getByRole('button', { name: decision, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: new RegExp(`^${decision} ${name} version 1`) })
  await dialog.getByLabel('Decision note').fill(decision === 'Approve' ? 'Reviewed source: renders granted data only' : 'Withdrawn for review')
  await dialog.getByRole('button', { name: decision, exact: true }).click()
  await expect(dialog).toBeHidden()
}

/**
 * UI02g (S111, S114): a designer registers extensions and submits versions, a different administrator approves them,
 * the server-served frame carries its own sandbox policy and framing headers, the extension receives only the granted
 * data and runs the granted chain through the bridge, an extension leaving its document falls back without breaking the page, and a
 * revoked version is not loaded at all.
 */
test('approved extensions run sandboxed with granted data, fall back when they leave their document and stop when revoked', async ({ sessions }) => {
  test.skip(MOCK, 'The extension frame is served by the server with its own policy; mock mode would load it as srcdoc under the app policy')
  test.setTimeout(300_000)
  let page = await sessions.as('dana.designer')
  const ws = await workspaceId(page)
  const badge = uniq('Request badge')
  const leaving = uniq('Leaving badge')
  await page.goto(`/w/${ws}/extensions`)
  await register(page, badge, BADGE)
  await register(page, leaving, LEAVING)
  // The submitter cannot decide their own version.
  await expect(page.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0)

  page = await sessions.as('wade.admin')
  await page.goto(`/w/${ws}/extensions`)
  await decide(page, badge, 'Approve')
  await decide(page, leaving, 'Approve')
  await expectNoSeriousViolations(page, 'Extension registry')
  const list = await apiGet<{ items: Array<{ id: string; name: string }> }>(page, `/workspaces/${ws}/ui-extensions`)
  const badgeId = list.items.find((item) => item.name === badge)!.id
  const leavingId = list.items.find((item) => item.name === leaving)!.id

  // The frame document: its own policy (hash-pinned scripts, no network, sandbox) and framable only by this origin.
  const headers = await page.evaluate(async (url) => {
    const response = await fetch(url, { credentials: 'same-origin' })
    const value = (name: string) => response.headers.get(name) ?? ''
    return { status: response.status, type: value('Content-Type'), csp: value('Content-Security-Policy'), frame: value('X-Frame-Options'), permissions: value('Permissions-Policy'), referrer: value('Referrer-Policy'), body: await response.text() }
  }, `/api/v1/workspaces/${ws}/ui-extensions/${badgeId}/versions/1/frame`)
  expect(headers.status).toBe(200)
  expect(headers.type).toContain('text/html')
  expect(headers.csp).toContain("default-src 'none'")
  expect(headers.csp).toContain("connect-src 'none'")
  expect(headers.csp).toContain("form-action 'none'")
  expect(headers.csp).toContain("frame-ancestors 'self'")
  expect(headers.csp).toContain('sandbox allow-scripts')
  expect(headers.csp).not.toContain('allow-same-origin')
  expect(headers.csp.match(/'sha256-[A-Za-z0-9+/=]+'/g)).toHaveLength(2)
  expect(headers.frame).toBe('SAMEORIGIN')
  expect(headers.permissions).toContain('camera=()')
  expect(headers.referrer).toBe('no-referrer')
  expect(headers.body).toContain('Approve from extension')

  // A task page granting the badge one binding and one chain, plus the leaving extension.
  page = await sessions.as('dana.designer')
  const workflow = await createWorkflow(page, ws, uniq('Extension journey'))
  const reviewId = await createView(page, ws, uniq('Extension review'), approvalView())
  page = await sessions.as('paul.publisher')
  await publish(page, ws, workflow.id)
  await publishView(page, ws, reviewId, 1)
  page = await sessions.as('dana.designer')
  const pageId = await createView(page, ws, uniq('Extension desk'), { schemaVersion: 1, kind: 'page', title: 'Extension desk', context: 'task', layout: 'split', breakpoint: 'md',
    regions: [
      { id: 'start', label: 'Decision', components: [{ id: 'decision', type: 'view', view: { id: reviewId, version: 1 } }] },
      { id: 'end', label: 'Extensions', components: [
        { id: 'badge', type: 'extension', label: 'Request badge', extension: { id: badgeId, version: 1 }, bindings: ['task.requestTitle'], chains: ['approveNow'], height: 160 },
        { id: 'leaving', type: 'extension', label: 'Leaving badge', extension: { id: leavingId, version: 1 }, bindings: [], chains: [], height: 120 },
      ] },
    ],
    chains: [{ id: 'approveNow', label: 'Claim and approve', stopOnFailure: true, steps: [{ action: 'claim', component: 'decision' }, { action: 'approve', component: 'decision' }] }] })
  page = await sessions.as('paul.publisher')
  await publishView(page, ws, pageId, 1)
  const slug = slugOf(uniq('ext'))
  await routePage(page, ws, slug, 0, pageId, 1)

  page = await sessions.as('rhea.requester')
  const title = uniq('Extension request')
  const started = await apiSend<{ id: string }>(page, 'POST', `/workspaces/${ws}/runs`, { workflowId: workflow.id, input: { title, purpose: 'Extension acceptance', costCenter: 'OPS', amount: 60 } })
  expect(started.status).toBe(201)
  const run = await apiGet<{ tasks: Array<{ id: string; nodeId: string }> }>(page, `/workspaces/${ws}/runs/${started.body.id}`)
  const taskId = run.tasks.find((task) => task.nodeId === 'manager')!.id

  // The leaving extension reloads itself. Its sandboxed frame has an opaque origin, so the reload carries no session
  // cookie (SameSite=Lax) and the server answers 401 under the application policy (frame-ancestors 'none'). The host
  // removes the frame on the bridge's "unloading" message, usually before that answer arrives; when the answer comes
  // first, Chromium blocks it and logs these two errors. Either way no second document runs in the frame.
  sessions.allowConsoleError(`Framing '${new URL(BASE_URL).origin}/' violates the following Content Security Policy directive: "frame-ancestors 'none'". The request has been blocked.`)
  sessions.allowConsoleError('Failed to load resource: the server responded with a status of 401 ()', 'chrome-error://chromewebdata/')
  page = await sessions.as('mani.manager')
  await page.goto(`/w/${ws}/apps/${slug}/${taskId}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Extension desk' })).toBeVisible()
  const frameElement = page.locator('iframe[title="Request badge"]')
  await expect(frameElement).toHaveAttribute('sandbox', 'allow-scripts')
  await expect(frameElement).toHaveAttribute('src', `/api/v1/workspaces/${ws}/ui-extensions/${badgeId}/versions/1/frame`)
  const frame = page.frameLocator('iframe[title="Request badge"]')
  await expect(frame.getByText(`Request: ${title}`)).toBeVisible({ timeout: 20_000 })
  // The leaving extension navigates away from its approved document; the host removes it and the rest of the page keeps working.
  await expect(page.getByTestId('extension-fallback').filter({ hasText: 'Leaving badge' })).toContainText('navigated away', { timeout: 20_000 })
  await expect(page.locator('iframe[title="Leaving badge"]')).toHaveCount(0)
  await expect(frame.getByText(`Request: ${title}`)).toBeVisible()
  await expectNoSeriousViolations(page, 'Task page with sandboxed extensions')

  await frame.getByRole('button', { name: 'Approve from extension' }).click()
  await expect(frame.getByText('Approved by the chain')).toBeVisible({ timeout: 20_000 })
  expect((await apiGet<{ status: string }>(page, `/workspaces/${ws}/tasks/${taskId}`)).status).toBe('completed')

  // Revoked: the page shows the fallback without loading the frame.
  page = await sessions.as('wade.admin')
  await page.goto(`/w/${ws}/extensions`)
  await decide(page, badge, 'Revoke')
  page = await sessions.as('mani.manager')
  await page.reload()
  await expect(page.getByTestId('extension-fallback').filter({ hasText: 'Request badge' })).toContainText('its version is revoked')
  await expect(page.locator('iframe[title="Request badge"]')).toHaveCount(0)
})
