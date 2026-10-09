// Screenshots for the design judge (S10 section 7.2), taken from the frontend in mock mode (no backend needed).
//
//   cd frontend && npm run dev:mock -- --port 5176
//   E2E_BROWSER_CHANNEL=msedge node tools/ai-eval/capture.mjs --base http://localhost:5176 --out evidence/ai-eval/<stamp>/screens
//
// Uses the Playwright installed by the E2E kit (tests/e2e). Viewport-sized shots keep the vision-model input bounded.
import { createRequire } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const { chromium } = createRequire(path.join(repo, 'tests/e2e/package.json'))('@playwright/test')
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith('--') ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), []),
)
const BASE = args.base ?? 'http://localhost:5176'
const OUT = path.resolve(args.out ?? 'screens')
const WS = '11111111-1111-4111-8111-111111111111'
const WORKFLOW = 'bbbbbbbb-0000-4000-8000-000000000001'
const channel = process.env.E2E_BROWSER_CHANNEL || undefined
const manifest = []

fs.mkdirSync(OUT, { recursive: true })
const browser = await chromium.launch({ ...(channel ? { channel } : {}) })

async function session(persona, viewport) {
  const context = await browser.newContext({ baseURL: BASE, viewport, deviceScaleFactor: 1 })
  await context.addInitScript((user) => localStorage.setItem('orqix.mock.user', user), persona)
  const page = await context.newPage()
  await page.goto('/')
  await page.waitForFunction(() => window.__orqixMock)
  await page.evaluate(() => window.__orqixMock.reset({ published: true, sampleRuns: true }))
  return { context, page }
}

async function settle(page) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.locator('[role="status"]:has-text("Loading")').first().waitFor({ state: 'detached', timeout: 8000 }).catch(() => {})
  await page.waitForTimeout(600)
}

async function shot(page, name, screen, persona, viewport) {
  const file = path.join(OUT, `${name}.png`)
  await page.screenshot({ path: file, fullPage: false })
  manifest.push({ name, file: path.basename(file), screen, persona, viewport: `${viewport.width}x${viewport.height}` })
  console.log(`  ${name}`)
}

async function attempt(label, fn) {
  try {
    await fn()
  } catch (error) {
    console.log(`  (skipped ${label}: ${String(error.message).split('\n')[0]})`)
  }
}

const desktop = { width: 1440, height: 900 }
const mobile = { width: 375, height: 812 }

await attempt('requester home', async () => {
  const { context, page } = await session('rhea.requester', desktop)
  await page.goto(`/w/${WS}`)
  await settle(page)
  await shot(page, '01-home-requester', 'Home (requester): start a request, see my requests', 'rhea.requester', desktop)
  await context.close()
})

await attempt('approver tasks', async () => {
  const { context, page } = await session('mani.manager', desktop)
  await page.goto(`/w/${WS}/tasks`)
  await settle(page)
  await shot(page, '02-tasks-approver', 'My tasks (approver): claim and decide requests', 'mani.manager', desktop)
  await context.close()
})

await attempt('designer AI flow', async () => {
  const { context, page } = await session('dana.designer', desktop)
  await page.goto(`/w/${WS}/workflows`)
  await settle(page)
  await shot(page, '03-workflows-designer', 'Workflows list (designer)', 'dana.designer', desktop)
  await page.getByRole('button', { name: /New workflow/ }).click()
  await page.getByTestId('describe-it-choice').waitFor()
  await settle(page)
  await shot(page, '04-new-workflow-dialog', 'New workflow dialog with Template / Blank / Describe it (AI)', 'dana.designer', desktop)
  await page.getByTestId('describe-it-choice').getByRole('button', { name: 'Describe it' }).click()
  await page.getByRole('button', { name: /Use the example: Access request/ }).click()
  await settle(page)
  await shot(page, '05-describe-it-dialog', 'Describe it (AI draft) dialog: description, examples, model profile', 'dana.designer', desktop)
  await page.getByRole('button', { name: /Generate draft/ }).click()
  await page.getByTestId('ai-review').waitFor({ timeout: 30000 })
  await settle(page)
  await shot(page, '06-studio-ai-review', 'Studio after an AI draft: review panel with missing configuration and assumptions', 'dana.designer', desktop)
  await page.getByRole('button', { name: /^Explain$/ }).click()
  await page.getByTestId('explain-summary').waitFor({ timeout: 15000 })
  await settle(page)
  await shot(page, '07-studio-explain', 'Studio: Explain side panel (plain-language explanation)', 'dana.designer', desktop)
  await context.close()
})

await attempt('studio template', async () => {
  const { context, page } = await session('dana.designer', desktop)
  await page.goto(`/w/${WS}/workflows/${WORKFLOW}/edit`)
  await page.getByText(/Draft revision/).first().waitFor({ timeout: 15000 })
  await settle(page)
  await shot(page, '08-studio-canvas', 'Studio canvas with the purchase approval template', 'dana.designer', desktop)
  await context.close()
})

await attempt('settings AI', async () => {
  const { context, page } = await session('wade.admin', desktop)
  await page.goto(`/w/${WS}/settings`)
  await settle(page)
  await page.getByRole('tab', { name: /^AI$/ }).click()
  await page.getByTestId('ai-settings').waitFor()
  await settle(page)
  await shot(page, '09-settings-ai', 'Settings > AI (read-only): profiles, caps, spend', 'wade.admin', desktop)
  await context.close()
})

await attempt('operator run detail', async () => {
  const { context, page } = await session('olga.operator', desktop)
  await page.goto(`/w/${WS}/runs`)
  await settle(page)
  await shot(page, '10-runs-operator', 'Runs list (operator)', 'olga.operator', desktop)
  const link = page.locator(`a[href*="/w/${WS}/runs/"]`).first()
  if (await link.count()) {
    await link.click()
    await settle(page)
    await shot(page, '11-run-detail', 'Run detail (operator): timeline, current step, actions', 'olga.operator', desktop)
  }
  await context.close()
})

await attempt('mobile', async () => {
  const { context, page } = await session('mani.manager', mobile)
  await page.goto(`/w/${WS}/tasks`)
  await settle(page)
  await shot(page, '12-mobile-tasks', 'My tasks on a phone (approver)', 'mani.manager', mobile)
  await context.close()
})

fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 2))
await browser.close()
console.log(`${manifest.length} screens -> ${OUT}`)
