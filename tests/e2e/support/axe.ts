import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/**
 * Scan the current page against WCAG 2.2 AA. Serious and critical findings fail the test (T-UI-05);
 * moderate and minor ones are logged in the test output so they stay visible.
 */
export async function expectNoSeriousViolations(page: Page, label: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  const advisory = results.violations.filter((v) => v.impact !== 'serious' && v.impact !== 'critical')
  for (const v of advisory) console.log(`[axe ${v.impact}] ${label}: ${v.id} (${v.nodes.length} nodes) ${v.helpUrl}`)
  const summary = blocking.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.slice(0, 5).map((n) => ({ target: n.target, html: n.html.slice(0, 160), why: n.failureSummary?.split('\n').slice(0, 3).join(' | ') })),
  }))
  expect(summary, `${label}: serious or critical accessibility violations`).toEqual([])
}
