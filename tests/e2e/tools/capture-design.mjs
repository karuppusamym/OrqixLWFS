// Design evidence: captures the main screens at 1440x900 and 375x812 in mock mode.
//
//   cd tests/e2e
//   E2E_BROWSER_CHANNEL=msedge node tools/capture-design.mjs --out ../../evidence/design/<stamp>/before --base http://localhost:5174
//
// The mock API lives in the page, so no backend is needed; each persona gets its own browser context with the seeded demo data.
// Screens that do not exist yet (older builds) are skipped with a note instead of failing the run.
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith('--') ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), []),
)
const BASE = args.base ?? 'http://localhost:5174'
const OUT = path.resolve(args.out ?? 'evidence-out')
const WS = '11111111-1111-4111-8111-111111111111'
const WORKFLOW = 'bbbbbbbb-0000-4000-8000-000000000001'
const channel = process.env.E2E_BROWSER_CHANNEL || undefined
const only = args.only ? new Set(args.only.split(',')) : null

const VIEWPORTS = {
  desktop: { width: 1440, height: 900 },
  mobile: { width: 375, height: 812 },
}

fs.mkdirSync(OUT, { recursive: true })
const browser = await chromium.launch({ ...(channel ? { channel } : {}) })
const notes = []

async function session(persona, viewport) {
  const context = await browser.newContext({ baseURL: BASE, viewport, deviceScaleFactor: 1 })
  await context.addInitScript((user) => {
    localStorage.setItem('orqix.mock.user', user)
  }, persona)
  const page = await context.newPage()
  page.on('pageerror', (e) => notes.push(`pageerror (${persona}): ${e.message}`))
  // Mock data is only persisted on writes; reset() seeds AND persists so later navigations keep the same ids.
  await page.goto('/')
  await page.waitForFunction(() => window.__orqixMock)
  await page.evaluate(() => window.__orqixMock.reset())
  return { context, page }
}

async function settle(page) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.locator('[role="status"]:has-text("Loading")').first().waitFor({ state: 'detached', timeout: 8000 }).catch(() => {})
  await page.waitForTimeout(500)
}

async function api(page, url) {
  return page.evaluate(async (u) => (await fetch(`/api/v1${u}`)).json(), url)
}

async function shot(page, view, name, options = {}) {
  if (only && !only.has(name)) return
  const dir = path.join(OUT, view)
  fs.mkdirSync(dir, { recursive: true })
  await page.screenshot({ path: path.join(dir, `${name}.png`), fullPage: options.fullPage ?? true })
  console.log(`  ${view}/${name}`)
}

async function attempt(label, fn) {
  try {
    await fn()
  } catch (error) {
    notes.push(`skipped ${label}: ${String(error.message).split('\n')[0]}`)
    console.log(`  (skipped ${label}: ${String(error.message).split('\n')[0]})`)
  }
}

for (const [view, viewport] of Object.entries(VIEWPORTS)) {
  console.log(view)

  // Requester: home, form, my requests, run detail.
  {
    const { context, page } = await session('rhea.requester', viewport)
    await page.goto(`/w/${WS}`)
    await settle(page)
    await shot(page, view, '01-home-requester')
    await attempt('request form', async () => {
      await page.goto(`/w/${WS}/start/${WORKFLOW}`)
      await settle(page)
      await shot(page, view, '02-request-form')
      await page.getByRole('button', { name: /Review request/ }).click()
      await page.waitForTimeout(400)
      await shot(page, view, '03-request-form-errors')
    })
    await attempt('my requests', async () => {
      await page.goto(`/w/${WS}/requests`)
      await settle(page)
      await shot(page, view, '04-my-requests')
    })
    await attempt('run detail', async () => {
      const runs = await api(page, `/workspaces/${WS}/me/requests`)
      const run = runs.items.find((r) => r.lifecycle === 'completed' && r.outcomeKind === 'rejected') ?? runs.items[0]
      await page.goto(`/w/${WS}/runs/${run.id}`)
      await settle(page)
      await shot(page, view, '05-run-detail-rejected')
      const waiting = runs.items.find((r) => r.lifecycle === 'waiting')
      if (waiting) {
        await page.goto(`/w/${WS}/runs/${waiting.id}`)
        await settle(page)
        await shot(page, view, '06-run-detail-waiting')
      }
    })
    await context.close()
  }

  // Manager: home with work waiting, tasks, task panel.
  {
    const { context, page } = await session('max.manager', viewport)
    await page.goto(`/w/${WS}`)
    await settle(page)
    await shot(page, view, '07-home-approver')
    await attempt('tasks', async () => {
      await page.goto(`/w/${WS}/tasks?view=claimable`)
      await settle(page)
      await shot(page, view, '08-tasks')
      const tasks = await api(page, `/workspaces/${WS}/tasks?view=claimable`)
      const task = tasks.items[0]
      await page.goto(`/w/${WS}/tasks/${task.id}`)
      await settle(page)
      await shot(page, view, '09-task-panel')
    })
    await context.close()
  }

  // Operator: runs, needs-attention run detail.
  {
    const { context, page } = await session('olga.operator', viewport)
    await attempt('runs', async () => {
      await page.goto(`/w/${WS}/runs`)
      await settle(page)
      await shot(page, view, '10-runs')
      await page.goto(`/w/${WS}/runs?lifecycle=needsAttention`)
      await settle(page)
      await shot(page, view, '25-runs-needs-attention')
      await page.goto(`/w/${WS}/runs`)
      await settle(page)
      const runs = await api(page, `/workspaces/${WS}/runs`)
      const stuck = runs.items.find((r) => r.lifecycle === 'needsAttention')
      if (stuck) {
        await page.goto(`/w/${WS}/runs/${stuck.id}`)
        await settle(page)
        await shot(page, view, '11-run-detail-operator')
      }
    })
    await context.close()
  }

  // Designer: workflows, overview, studio.
  {
    const { context, page } = await session('dana.designer', viewport)
    await attempt('workflows', async () => {
      await page.goto(`/w/${WS}/workflows`)
      await settle(page)
      await shot(page, view, '12-workflows')
    })
    await attempt('overview', async () => {
      await page.goto(`/w/${WS}/workflows/${WORKFLOW}`)
      await settle(page)
      await shot(page, view, '13-workflow-overview')
    })
    await attempt('new workflow and describe-it', async () => {
      await page.goto(`/w/${WS}/workflows`)
      await settle(page)
      await page.getByRole('button', { name: /New workflow/ }).click()
      await page.waitForTimeout(600)
      await shot(page, view, '23-new-workflow-dialog', { fullPage: false })
      await page.getByRole('button', { name: 'Describe it' }).click()
      await page.waitForTimeout(700)
      await shot(page, view, '24-describe-it-dialog', { fullPage: false })
    })
    await attempt('import', async () => {
      await page.goto(`/w/${WS}/migrations`)
      await settle(page)
      await shot(page, view, '21-import')
    })
    await attempt('studio', async () => {
      await page.goto(`/w/${WS}/workflows/${WORKFLOW}/edit`)
      await settle(page)
      await page.waitForTimeout(800)
      await shot(page, view, '14-studio-canvas', { fullPage: false })
      if (view === 'desktop') {
        await attempt('studio inspector', async () => {
          await page.locator('.react-flow__node', { hasText: 'Review request' }).first().click()
          await page.waitForTimeout(500)
          await shot(page, view, '15-studio-inspector', { fullPage: false })
        })
        await attempt('studio check', async () => {
          await page.getByRole('button', { name: 'Check workflow' }).click()
          await page.waitForTimeout(1200)
          await shot(page, view, '16-studio-check', { fullPage: false })
        })
      }
      await page.getByRole('tab', { name: 'Steps' }).click()
      await page.waitForTimeout(400)
      await shot(page, view, '17-studio-steps', { fullPage: view !== 'desktop' })
    })
    await context.close()
  }

  // Publisher: publish review.
  {
    const { context, page } = await session('paul.publisher', viewport)
    await attempt('publish review', async () => {
      await page.goto(`/w/${WS}/workflows/${WORKFLOW}/publish?revision=1`)
      await settle(page)
      await shot(page, view, '18-publish-review')
    })
    await context.close()
  }

  // Workspace admin: settings.
  {
    const { context, page } = await session('wade.admin', viewport)
    await attempt('settings', async () => {
      await page.goto(`/w/${WS}/settings`)
      await settle(page)
      await shot(page, view, '19-settings-access')
      await page.getByRole('tab', { name: /Connections/ }).click()
      await page.waitForTimeout(500)
      await shot(page, view, '20-settings-connections')
      await page.getByRole('tab', { name: /^AI$|AI$/ }).click()
      await page.waitForTimeout(800)
      await shot(page, view, '22-settings-ai')
    })
    await context.close()
  }
}

await browser.close()
if (notes.length) fs.writeFileSync(path.join(OUT, 'notes.txt'), notes.join('\n') + '\n')
console.log(notes.length ? `done with ${notes.length} note(s), see notes.txt` : 'done')
