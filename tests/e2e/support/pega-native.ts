import path from 'node:path'
import { expect, type Page } from '@playwright/test'
import { apiGet } from './app'
import { apiSend, uniq } from './api'

/** Synthetic native Pega rule archives (docs/build/S112, S115). They are not Pega exports. */
export const nativeFixtures = path.resolve('../../examples/migration/pega-native')

/** Upload a native rule archive through Import workflow and wait for the archive review; returns the migration id. */
export async function analyseNativeArchive(page: Page, ws: string, file: string): Promise<string> {
  await page.goto(`/w/${ws}/migrations`)
  await page.getByLabel('Source format').selectOption('pegaRules')
  await page.getByLabel(/Source file/).setInputFiles(path.join(nativeFixtures, file))
  await page.getByRole('button', { name: 'Analyze source' }).click()
  await expect(page.getByRole('region', { name: 'Native Pega rule archive' })).toBeVisible({ timeout: 30_000 })
  const id = page.url().match(/migrations\/([0-9a-f-]+)/)?.[1]
  if (!id) throw new Error('no migration id in ' + page.url())
  return id
}

/** A workflow key that is valid and unique per run. */
export const nativeKey = (prefix: string): string => uniq(prefix).toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 60)

/** Fill key and name, choose the approving flow action of each assignment, map work queues, acknowledge, convert. */
export async function convertNative(page: Page, options: {
  key: string
  approve?: Record<string, string>
  groups?: Record<string, string>
  children?: Record<string, { workflowRef: string }>
}): Promise<string> {
  await page.getByLabel('Workflow key', { exact: true }).fill(options.key)
  await page.getByLabel('Workflow name', { exact: true }).fill(`Native Pega ${options.key}`)
  for (const [assignment, connector] of Object.entries(options.approve ?? {})) await page.getByLabel(assignment, { exact: true }).selectOption(connector)
  for (const [queue, group] of Object.entries(options.groups ?? {})) await page.getByLabel(`Map source assignment: ${queue}`).selectOption({ label: group })
  if (options.children) {
    const mappings = Object.fromEntries(Object.entries(options.children).map(([call, m]) => [call, { workflowRef: m.workflowRef, inputMapping: {}, resultMapping: {} }]))
    await page.getByLabel('Child workflow mappings (JSON)').fill(JSON.stringify(mappings))
  }
  const form = page.locator('form').filter({ has: page.getByRole('button', { name: 'Convert to draft' }) })
  const acks = form.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) if (await acks.nth(i).isVisible() && !(await acks.nth(i).isChecked())) await acks.nth(i).check()
  await page.getByRole('button', { name: 'Convert to draft' }).click()
  await expect(page.getByTestId('studio')).toBeVisible({ timeout: 30_000 })
  const id = page.url().match(/workflows\/([0-9a-f-]+)/)?.[1]
  if (!id) throw new Error('no workflow id in ' + page.url())
  return id
}

/** Publish the converted draft on the publish page as the publisher (a separate action from conversion). */
export async function publishOnPage(publisher: Page, ws: string, workflowId: string): Promise<void> {
  await publisher.goto(`/w/${ws}/workflows/${workflowId}/publish`)
  const button = publisher.getByRole('button', { name: /Publish version \d+/ })
  await expect(button).toBeVisible({ timeout: 30_000 })
  const acks = publisher.getByRole('checkbox')
  for (let i = 0; i < await acks.count(); i++) if (await acks.nth(i).isVisible() && !(await acks.nth(i).isChecked())) await acks.nth(i).check()
  await button.click()
  await expect(publisher.getByTestId('publish-success')).toBeVisible({ timeout: 30_000 })
}

/** Start a request through the API as the signed-in requester; returns the run id. */
export async function startRun(requester: Page, ws: string, workflowId: string, input: Record<string, unknown>): Promise<string> {
  const r = await apiSend<{ id: string }>(requester, 'POST', `/workspaces/${ws}/runs`, { workflowId, input })
  if (r.status !== 201) throw new Error(`start run: ${r.status} ${JSON.stringify(r.body)}`)
  return r.body.id
}

/** Wait until the run reaches one of the lifecycles. */
export async function waitForLifecycle(page: Page, ws: string, runId: string, lifecycles: string[], timeout = 90_000): Promise<string> {
  let last = ''
  await expect.poll(async () => {
    last = (await apiGet<{ lifecycle: string }>(page, `/workspaces/${ws}/runs/${runId}`)).lifecycle
    return lifecycles.includes(last)
  }, { timeout, intervals: [1_000] }).toBe(true)
  return last
}
