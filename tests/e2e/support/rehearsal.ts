import AxeBuilder from '@axe-core/playwright'
import { appendFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, type Page } from '@playwright/test'

/** Demo-rehearsal helpers (S86). Results are appended to evidence/demo-1025/results.jsonl; nothing here fails a test on axe. */
const OUT = path.resolve(process.env.REHEARSAL_OUT ?? '../../evidence/demo-1025')
mkdirSync(path.join(OUT, 'shots'), { recursive: true })

export function record(step: string, status: 'PASS' | 'FAIL' | 'INFO', note = ''): void {
  appendFileSync(path.join(OUT, 'results.jsonl'), JSON.stringify({ t: new Date().toISOString(), step, status, note }) + '\n')
  console.log(`[${status}] ${step} ${note}`)
}

export async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: path.join(OUT, 'shots', `${name}.png`), fullPage: true })
}

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']
/** Full axe scan (all impacts) recorded, never failing. */
export async function axe(page: Page, label: string): Promise<void> {
  const r = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  const v = r.violations.map((x) => `${x.impact}:${x.id}(${x.nodes.length})`).join(', ')
  record(`axe ${label}`, r.violations.some((x) => x.impact === 'serious' || x.impact === 'critical') ? 'FAIL' : 'PASS', v || 'no violations')
}

export const key = (p: string): string => `${p}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 4)}`.toLowerCase().replace(/[^a-z0-9-]/g, '')

export { expect }
