import type { Page } from '@playwright/test'
import { expect, test } from '../support/fixtures'
import { MOCK } from '../support/env'
import { apiGet, createPurchaseWorkflow, ensureTemplatePublished, submitPurchase, workflowIdByKey, workspaceId } from '../support/app'

/**
 * T-UI-10: the red / gold theme is applied from tokens, the studio canvas opens readable and tidy, auto-layout is
 * undoable, and the lists have a card layout on a phone. Uses the mock API only where the real stack has no fixture.
 */

interface Box {
  id: string
  x: number
  y: number
  width: number
  height: number
}

async function nodeBoxes(page: Page): Promise<Box[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('.react-flow__node')).map((n) => {
      const r = n.getBoundingClientRect()
      return { id: n.getAttribute('data-id') ?? '', x: r.x, y: r.y, width: r.width, height: r.height }
    }),
  )
}

function overlaps(boxes: Box[]): string[] {
  const out: string[] = []
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i] as Box
      const b = boxes[j] as Box
      if (a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height) out.push(`${a.id}/${b.id}`)
    }
  }
  return out
}

test('theme: brand attribute, red top bar with the gold stripe, tokens on the page', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })
  const page = await sessions.as('rhea.requester')
  const ws = await workspaceId(page)
  await page.goto(`/w/${ws}`)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  const look = await page.evaluate(() => {
    const css = (el: Element | null, prop: string) => (el ? getComputedStyle(el).getPropertyValue(prop) : '')
    const header = document.querySelector('header')
    const stripe = document.querySelector('.topbar-stripe')
    return {
      brand: document.documentElement.dataset.brand,
      bar: css(header, 'background-color'),
      stripe: css(stripe, 'background-color'),
      stripeHeight: css(stripe, 'height'),
      page: css(document.body, 'background-color'),
    }
  })
  expect(look.brand).toBe('wf')
  expect(look.bar).toBe('rgb(215, 30, 40)')
  expect(look.stripe).toBe('rgb(255, 205, 65)')
  expect(look.stripeHeight).toBe('4px')
  expect(look.page).toBe('rgb(244, 240, 237)')
  // Primary actions are red, and the Start button says so in text.
  const start = page.getByRole('link', { name: /^Start / }).first()
  await expect(start).toBeVisible()
  expect(await start.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(215, 30, 40)')
})

test('studio canvas: readable text on open, no overlapping steps, Auto-layout is one undoable edit', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const designer = await sessions.as('dana.designer')
  const ws = await workspaceId(designer)
  const workflowId = await createPurchaseWorkflow(designer, ws)
  await designer.goto(`/w/${ws}/workflows/${workflowId}/edit`)
  await expect(designer.getByTestId('studio')).toBeVisible()
  await expect(designer.locator('.react-flow__node').first()).toBeVisible()
  // The layout is prepared (and the "Arranging" notice gone) before the canvas is shown.
  await expect(designer.getByText('Arranging the steps…')).toHaveCount(0)
  await expect(designer.getByTestId('save-state')).toHaveText(/Saved/) // arranging on open does not make the draft unsaved

  // Step text is 14 px or more on screen at the first view: font size times the viewport zoom.
  const effective = await designer.evaluate(() => {
    const scale = Number(/scale\(([\d.]+)\)/.exec((document.querySelector('.react-flow__viewport') as HTMLElement).style.transform)?.[1] ?? '1')
    // Cards (node-card) or BPMN shapes (shape-label, the default since STU01): whichever the canvas draws.
    const label = document.querySelector('.react-flow__node [data-testid^="node-card"] p, .react-flow__node [data-testid="shape-label"] span') as HTMLElement
    return { scale, size: parseFloat(getComputedStyle(label).fontSize) * scale }
  })
  expect(effective.size).toBeGreaterThanOrEqual(14)
  expect(await nodeBoxes(designer).then(overlaps)).toEqual([])

  // The start of the workflow is in view at that readable size.
  const canvas = await designer.locator('.orqix-flow').boundingBox()
  const start = (await nodeBoxes(designer)).find((b) => b.id === 'start')
  expect(canvas && start && start.x >= canvas.x - 1 && start.x + start.width <= canvas.x + canvas.width + 1).toBe(true)

  // Drag a step on top of another, then Auto-layout puts everything right and Ctrl+Z brings the mess back.
  const manager = designer.locator('.react-flow__node[data-id="manager"]')
  const startNode = designer.locator('.react-flow__node[data-id="start"]')
  const from = await manager.boundingBox()
  const onto = await startNode.boundingBox()
  if (!from || !onto) throw new Error('nodes not visible')
  await designer.mouse.move(from.x + 20, from.y + 20)
  await designer.mouse.down()
  await designer.mouse.move(onto.x + 30, onto.y + 30, { steps: 8 })
  await designer.mouse.up()
  await expect(designer.getByTestId('save-state')).toHaveText('Unsaved changes')
  expect(await nodeBoxes(designer).then(overlaps)).not.toEqual([])

  await designer.getByRole('button', { name: 'Auto-layout' }).click()
  await expect(designer.getByTestId('announcer')).toContainText('Steps arranged')
  await expect.poll(async () => (await nodeBoxes(designer).then(overlaps)).length).toBe(0)

  await designer.keyboard.press('Control+z')
  await expect.poll(async () => (await nodeBoxes(designer).then(overlaps)).length).toBeGreaterThan(0) // undone: back to the dragged state
})

test('phone: requests and tasks are cards, and nothing scrolls sideways', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  if (!MOCK) {
    // Real stack: make sure there is at least one request and one claimable task to render as cards.
    const templateId = await ensureTemplatePublished(await sessions.as('paul.publisher'), ws)
    await submitPurchase(requester, ws, templateId, { title: 'Phone card check', purpose: 'Narrow screen lists', costCenter: 'OPS', amount: '250' })
  }
  await requester.setViewportSize({ width: 375, height: 812 })
  await requester.goto(`/w/${ws}/requests`)
  const list = requester.getByRole('list', { name: 'Your requests' })
  await expect(list).toBeVisible()
  await expect(list.getByRole('link', { name: /^ORQ-\d+$/ }).first()).toBeVisible()
  await expect.poll(() => requester.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await expect(requester.getByRole('table')).toHaveCount(0)

  const manager = await sessions.as('mani.manager')
  await manager.setViewportSize({ width: 375, height: 812 })
  await manager.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
  await expect(manager.getByRole('list', { name: 'Available to claim' })).toBeVisible()
  await expect.poll(() => manager.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

/** One page of the app on a phone: it fits the screen width (nothing scrolls sideways). */
async function expectFits(page: Page, label: string): Promise<void> {
  await expect(page.locator('#main')).toBeVisible()
  await page.waitForLoadState('networkidle')
  const overflow = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, inner: innerWidth }))
  expect(overflow.scroll, `${label} is wider than the screen (${overflow.scroll} > ${overflow.inner})`).toBeLessThanOrEqual(overflow.inner)
}

test('phone: no main screen scrolls sideways at 375 px', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: true, demo: true })
  const view = { width: 375, height: 812 }
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  const workflowId = await workflowIdByKey(requester, ws)
  await requester.setViewportSize(view)
  const mine = await apiGet<{ items: Array<{ id: string }> }>(requester, `/workspaces/${ws}/me/requests`)
  for (const [label, path] of [
    ['home', `/w/${ws}`],
    ['request form', `/w/${ws}/start/${workflowId}`],
    ['request detail', `/w/${ws}/runs/${mine.items[0]?.id}`],
  ] as const) {
    await requester.goto(path)
    await expectFits(requester, label)
  }

  const manager = await sessions.as('mani.manager')
  await manager.setViewportSize(view)
  const tasks = await apiGet<{ items: Array<{ id: string }> }>(manager, `/workspaces/${ws}/tasks?view=claimable`)
  await manager.goto(`/w/${ws}/tasks/${tasks.items[0]?.id}`)
  await expect(manager.getByTestId('task-actions')).toBeVisible()
  await expectFits(manager, 'task panel')

  const operator = await sessions.as('olga.operator')
  await operator.setViewportSize(view)
  const runs = await apiGet<{ items: Array<{ id: string; lifecycle: string }> }>(operator, `/workspaces/${ws}/runs`)
  const stuck = runs.items.find((r) => r.lifecycle === 'needsAttention')
  await operator.goto(`/w/${ws}/runs`)
  await expectFits(operator, 'runs')
  if (stuck) {
    await operator.goto(`/w/${ws}/runs/${stuck.id}`)
    await expect(operator.getByRole('heading', { name: 'Operator actions' })).toBeVisible()
    await expectFits(operator, 'operator run detail')
  }

  const designer = await sessions.as('dana.designer')
  await designer.setViewportSize(view)
  for (const [label, path] of [
    ['workflows', `/w/${ws}/workflows`],
    ['workflow overview', `/w/${ws}/workflows/${workflowId}`],
    ['import', `/w/${ws}/migrations`],
  ] as const) {
    await designer.goto(path)
    await expectFits(designer, label)
  }
  const admin = await sessions.as('wade.admin')
  await admin.setViewportSize(view)
  await admin.goto(`/w/${ws}/settings`)
  await expectFits(admin, 'settings')
})

test('phone: a very long title without spaces wraps instead of widening request, task list or task panel', async ({ sessions }) => {
  if (MOCK) await sessions.mockCall('reset', { published: true, sampleRuns: false })
  const requester = await sessions.as('rhea.requester')
  const ws = await workspaceId(requester)
  const workflowId = await workflowIdByKey(requester, ws)
  await requester.setViewportSize({ width: 375, height: 812 })
  const title = 'Replacement of the audio system Supercalifragilisticexpialidociousequipmentbundle'
  const { runUrl } = await submitPurchase(requester, ws, workflowId, { title, purpose: 'Audio ' + 'Longwordwithoutspaces'.repeat(6), costCenter: 'OPS', amount: '250' })
  await requester.goto(runUrl)
  await expect(requester.getByTestId('run-reference')).toBeVisible()
  await expectFits(requester, 'request detail with a long title')

  const manager = await sessions.as('mani.manager')
  await manager.setViewportSize({ width: 375, height: 812 })
  await manager.goto(`/w/${ws}/tasks?view=claimable&sort=newest`)
  await expect(manager.getByRole('link', { name: new RegExp(title.slice(0, 20)) }).first()).toBeVisible()
  await expectFits(manager, 'task list with a long title')
  await manager.getByRole('link', { name: new RegExp(title.slice(0, 20)) }).first().click()
  await expect(manager.getByTestId('task-actions')).toBeVisible()
  await expectFits(manager, 'task panel with a long title')
})
