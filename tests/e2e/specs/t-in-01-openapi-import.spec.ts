import { expect, test } from '../support/fixtures'
import { apiGet, workspaceId } from '../support/app'
import { expectNoSeriousViolations } from '../support/axe'

test('real administrator reviews an OpenAPI file and separately registers the confirmed definition without binding it', async ({ sessions }) => {
  test.skip(process.env.E2E_MOCK === 'true', 'Requires the real OpenAPI converter and connection registration API.')
  const admin = await sessions.as('wade.admin')
  const ws = await workspaceId(admin)
  const originalBinding = await apiGet<{ binding: unknown }>(admin, `/workspaces/${ws}/connections/procurement`)
  const key = `browser-openapi-${Date.now().toString(36)}`
  const source = {
    openapi: '3.1.0',
    info: { title: 'Browser-reviewed inventory', version: '1' },
    servers: [{ url: 'https://example.invalid/never-contact-this-server' }],
    paths: {
      '/health': {
        get: { operationId: 'healthRead', summary: 'Read system health', responses: { '200': { description: 'Healthy', content: { 'application/json': { schema: { type: 'object', required: ['status'], properties: { status: { type: 'string', maxLength: 40 } } } } } } } },
      },
      '/items/{id}': {
        get: { operationId: 'getItem', summary: 'Unsupported dynamic address', responses: { '200': { description: 'Item', content: { 'application/json': { schema: { type: 'object', properties: { id: { type: 'string' } } } } } } } },
      },
    },
  }
  const remoteRequests: string[] = []
  admin.on('request', request => { if (new URL(request.url()).hostname === 'example.invalid') remoteRequests.push(request.url()) })
  await admin.goto(`/w/${ws}/settings`)
  await admin.getByRole('tab', { name: 'Connections', exact: true }).click()
  const panel = admin.getByRole('region', { name: 'Import an OpenAPI connection', exact: true })
  await expect(panel).toBeVisible()
  await panel.getByLabel('OpenAPI file (maximum 2 MiB)', { exact: true }).setInputFiles({ name: 'browser-review.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(source)) })
  await panel.getByRole('button', { name: 'Review operations', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('2 operations:')
  await expect(panel.getByRole('status')).toContainText('1 blocked')
  const allowed = panel.getByRole('checkbox', { name: /GET \/health/ })
  const blocked = panel.getByRole('checkbox', { name: /GET \/items\/\{id\}/ })
  await expect(allowed).toBeEnabled()
  await expect(blocked).toBeDisabled()
  await expect(panel.getByText(/address contains a variable part/)).toBeVisible()
  await expect(panel.getByRole('button', { name: 'Register reviewed connection', exact: true })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: 'Build reviewed definition', exact: true })).toBeDisabled()
  await allowed.check()
  await expect(panel.getByLabel('Retry behavior for healthRead', { exact: true })).toHaveValue('readOnly')
  await panel.getByLabel('Connection key', { exact: true }).fill(key)
  await panel.getByLabel('Connection name', { exact: true }).fill('Browser reviewed health')
  await expect(panel.getByRole('button', { name: 'Build reviewed definition', exact: true })).toBeDisabled()
  await panel.getByRole('checkbox', { name: 'I confirmed the retry behavior of the selected operations with the system owner', exact: true }).check()
  await panel.getByRole('button', { name: 'Build reviewed definition', exact: true }).click()
  await expect(panel.getByRole('button', { name: 'Register reviewed connection', exact: true })).toBeVisible()
  await panel.getByText('Review connection definition', { exact: true }).click()
  await admin.keyboard.press('Tab')
  await expect(panel.getByRole('region', { name: 'Reviewed connection definition', exact: true })).toBeFocused()
  const candidate = JSON.parse(await panel.locator('pre').innerText()) as { key: string; operations: Record<string, { effectClass: string; method: string; path: string }> }
  expect(candidate.key).toBe(key)
  expect(Object.keys(candidate.operations)).toEqual(['healthRead'])
  expect(candidate.operations.healthRead).toMatchObject({ effectClass: 'readOnly', method: 'GET', path: '/health' })
  expect(candidate).not.toHaveProperty('baseUrl')
  expect(candidate).not.toHaveProperty('binding')
  expect(JSON.stringify(candidate)).not.toContain('example.invalid')
  await expectNoSeriousViolations(admin, 'OpenAPI operation review and confirmed candidate')
  const registration = admin.waitForResponse(response => response.url().endsWith(`/connections/${key}/versions`) && response.request().method() === 'POST')
  await panel.getByRole('button', { name: 'Register reviewed connection', exact: true }).click()
  expect((await registration).status()).toBe(201)
  await expect(panel.getByRole('status').filter({ hasText: 'Connection version registered.' })).toBeVisible()
  const stored = await apiGet<{ definitions: Array<{ version: number; definition: { key: string } }>; binding: unknown | null }>(admin, `/workspaces/${ws}/connections/${key}`)
  expect(stored.definitions).toHaveLength(1)
  expect(stored.definitions[0]?.definition.key).toBe(key)
  expect(stored.binding).toBeNull()
  const summaries = await apiGet<{ items: Array<{ key: string; binding: { configured: boolean; enabled: boolean } }> }>(admin, `/workspaces/${ws}/connections`)
  expect(summaries.items.find(item => item.key === key)?.binding).toMatchObject({ configured: false, enabled: false })
  expect((await apiGet<{ binding: unknown }>(admin, `/workspaces/${ws}/connections/procurement`)).binding).toEqual(originalBinding.binding)
  expect(remoteRequests).toEqual([])
})
