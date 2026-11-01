import { expect, type Page } from '@playwright/test'

/**
 * S126: case types are authored on the case designer page (/case-designs), not on the Cases page. These helpers
 * start a draft and publish it after a clean check, the way an author does.
 */
export async function newCaseType(page: Page, ws: string, key: string, name = key): Promise<void> {
  await page.goto(`/w/${ws}/case-designs`)
  await page.getByRole('button', { name: 'New case type', exact: true }).click()
  await page.getByLabel('Key', { exact: true }).fill(key)
  await page.getByLabel('Name', { exact: true }).fill(name)
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name, exact: true })).toBeVisible()
}

/** Uses a CMMN file as it is instead of the designer. */
export async function useCmmnFile(page: Page, fileName: string, sourceXml: string): Promise<void> {
  await page.getByRole('tab', { name: 'Source', exact: true }).click()
  await page.getByLabel('CMMN file (maximum 64 KiB)').setInputFiles({ name: fileName, mimeType: 'application/xml', buffer: Buffer.from(sourceXml) })
  await expect(page.getByRole('region', { name: 'Uploaded CMMN file' })).toBeVisible()
}

/** Checks the draft (saving it first), expects no problems, and publishes the next version, from the editor header. */
export async function checkAndPublish(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Check', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Check results' })).toContainText('No problems found')
  await page.getByRole('button', { name: /^Publish version \d+$/ }).click()
  await expect(page.getByText(/^Published version \d+ of /)).toBeVisible()
}

/** The Start case button on the Cases page card of the case type with this key. */
export function startCaseButton(page: Page, key: string) {
  return page.locator(`[data-case-key="${key}"]`).getByRole('button', { name: 'Start case', exact: true })
}
