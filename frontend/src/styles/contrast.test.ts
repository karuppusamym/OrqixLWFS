import { describe, expect, it } from 'vitest'
import { baseTokens, contrast, over, parseColor, themeMappings, tokens, wfOverrides, type Brand, type Rgba } from './tokens'

/**
 * Contrast regression (WCAG 2.2 AA): every text/background pair of the design tokens is at least 4.5:1, every UI
 * component boundary, focus ring and meaningful graphic at least 3:1, for BOTH brands. The tokens are read from
 * src/index.css, so a color change that breaks a pair fails here.
 *
 * A token written `a@b` means "a, composited over b" (for translucent hover colors).
 */
const TEXT = 4.5
const UI = 3

const KINDS = ['start', 'approval', 'decision', 'branch', 'http', 'notification', 'end'] as const
const STATUSES = ['waiting', 'running', 'attention', 'success', 'rejected', 'cancelled'] as const

type Pair = [fg: string, bg: string, min: number, what: string]

const pairs: Pair[] = [
  // Body text on every surface it appears on.
  ['foreground', 'background', TEXT, 'body text on the page'],
  ['foreground', 'surface', TEXT, 'body text on cards'],
  ['foreground', 'surface-muted', TEXT, 'body text on muted surfaces'],
  ['foreground', 'primary-soft', TEXT, 'text on a selected row'],
  ['muted-foreground', 'background', TEXT, 'secondary text on the page'],
  ['muted-foreground', 'surface', TEXT, 'secondary text on cards'],
  ['muted-foreground', 'surface-muted', TEXT, 'secondary text on muted surfaces'],
  ['muted-foreground', 'neutral-soft', TEXT, 'neutral badges and inactive tabs'],
  ['placeholder', 'surface', TEXT, 'placeholder text'],
  ['placeholder', 'surface-muted', TEXT, 'placeholder text on a disabled field'],
  // Primary actions and links.
  ['primary-foreground', 'primary', TEXT, 'primary button label'],
  ['primary-foreground', 'primary-hover', TEXT, 'primary button label, hovered'],
  ['primary-foreground', 'primary-pressed', TEXT, 'primary button label, pressed'],
  ['primary-text', 'surface', TEXT, 'links on cards'],
  ['primary-text', 'background', TEXT, 'links on the page'],
  ['primary-text', 'surface-muted', TEXT, 'links on muted surfaces'],
  ['primary-text', 'primary-soft', TEXT, 'active tab and selected row text'],
  ['accent-foreground', 'accent', TEXT, 'text on gold highlight badges'],
  // Top bar and navigation.
  ['topbar-foreground', 'topbar', TEXT, 'top bar text'],
  ['topbar-foreground', 'topbar-hover@topbar', TEXT, 'top bar text, hovered'],
  ['nav-foreground', 'nav', TEXT, 'navigation items'],
  ['nav-foreground', 'nav-hover@nav', TEXT, 'navigation items, hovered'],
  ['nav-muted', 'nav', TEXT, 'navigation secondary text'],
  ['nav-active-foreground', 'nav-active', TEXT, 'active navigation item'],
  ['nav-badge-foreground', 'nav-badge', TEXT, 'count badge in the navigation'],
  // Semantic colors.
  ['danger', 'surface', TEXT, 'error text and destructive outline buttons'],
  ['danger', 'background', TEXT, 'error text on the page'],
  ['danger', 'danger-soft', TEXT, 'destructive outline button, hovered'],
  ['danger-foreground', 'danger-soft', TEXT, 'error alerts and blocker rows'],
  ['danger-foreground', 'surface', TEXT, 'error summary text'],
  ['primary-foreground', 'danger-solid', TEXT, 'destructive confirmation button'],
  ['primary-foreground', 'danger-solid-hover', TEXT, 'destructive confirmation button, hovered'],
  ['success', 'surface', TEXT, 'success text'],
  ['success-foreground', 'success-soft', TEXT, 'success alerts and badges'],
  ['warning-foreground', 'warning-soft', TEXT, 'warning alerts and badges'],
  ['warning-foreground', 'accent-soft', TEXT, 'test-workspace banner'],
  ['info-foreground', 'info-soft', TEXT, 'info alerts and badges'],
  // Interface boundaries, focus and graphics (3:1).
  ['input-border', 'surface', UI, 'input and select boundary'],
  ['input-border', 'surface-muted', UI, 'disabled input boundary'],
  ['ring', 'surface', UI, 'focus ring on cards'],
  ['ring', 'background', UI, 'focus ring on the page'],
  ['ring', 'surface-muted', UI, 'focus ring on muted surfaces'],
  ['ring', 'topbar', UI, 'focus ring on the top bar'],
  ['nav-ring', 'nav', UI, 'focus ring on the navigation'],
  ['primary', 'surface', UI, 'primary control and handle outline on cards'],
  ['primary', 'canvas', UI, 'canvas handles and selected edge'],
  ['nav-indicator', 'nav', UI, 'active navigation indicator'],
  // Run status chips: each pair is text, shown with an icon.
  ...STATUSES.flatMap((s): Pair[] => [[`status-${s}-fg`, `status-${s}-bg`, TEXT, `${s} status chip`]]),
  // Studio canvas.
  ...KINDS.flatMap((k): Pair[] => [
    [`kind-${k}`, `kind-${k}-soft`, TEXT, `${k} kind badge`],
    [`kind-${k}`, 'surface', TEXT, `${k} kind text on a card`],
    ['primary-foreground', `kind-${k}`, UI, `${k} palette icon on its color`],
    [`kind-${k}`, 'canvas', UI, `${k} accent bar on the canvas`],
  ]),
  ...['approved', 'rejected', 'default', 'condition', 'next'].flatMap((e): Pair[] => [
    [`edge-${e}`, 'edge-label-bg', TEXT, `${e} route label text`],
    [`edge-${e}`, 'canvas', UI, `${e} route line on the canvas`],
  ]),
  ['status-success-fg', 'canvas', UI, 'Fulfilled end step accent bar'],
  ['status-rejected-fg', 'canvas', UI, 'Rejected end step accent bar'],
]

const WHITE: Rgba = { r: 255, g: 255, b: 255, a: 1 }

function resolve(brand: Brand, spec: string): Rgba {
  const t = tokens(brand)
  const [name, under] = spec.split('@') as [string, string | undefined]
  const raw = t[`--${name}`]
  if (raw === undefined) throw new Error(`Token --${name} is not defined for ${brand}`)
  const color = parseColor(raw)
  return under ? over(color, resolve(brand, under)) : color
}

describe.each<Brand>(['orqix', 'wf'])('contrast of the %s brand tokens', (brand) => {
  it.each(pairs)('%s on %s is at least %s:1 (%s)', (fg, bg, min) => {
    const background = over(resolve(brand, bg), WHITE)
    const ratio = contrast(over(resolve(brand, fg), background), background)
    expect(ratio, `${fg} on ${bg}`).toBeGreaterThanOrEqual(min)
  })
})

describe('the wf brand', () => {
  it('keeps the white focus gap clear of the red top bar', () => {
    const t = tokens('wf')
    expect(contrast(parseColor(t['--ring-gap'] as string), parseColor(t['--topbar'] as string))).toBeGreaterThanOrEqual(UI)
  })

  it('overrides every color token of the base palette, so no teal leaks through', () => {
    const notColors = new Set(['--radius', '--topbar-stripe-height', '--shadow-rgb'])
    const missing = Object.keys(baseTokens).filter((k) => !notColors.has(k) && !(k in wfOverrides))
    expect(missing).toEqual([])
    expect(Object.keys(wfOverrides).filter((k) => !(k in baseTokens))).toEqual([])
  })

  it('uses the red / gold / warm neutral palette', () => {
    const t = tokens('wf')
    expect(t['--primary']).toBe('#d71e28')
    expect(t['--accent']).toBe('#ffcd41')
    expect(t['--topbar']).toBe('#d71e28')
    expect(t['--background']).toBe('#f4f0ed')
    expect(t['--surface']).toBe('#ffffff')
    expect(t['--surface-muted']).toBe('#faf8f6')
    expect(t['--foreground']).toBe('#3b3331')
    expect(t['--border']).toBe('#d9d2cc')
    expect(t['--primary-text']).toBe('#a8161f')
  })

  it('keeps gold an accent: never usable as text on a light surface, always paired with dark text', () => {
    const t = tokens('wf')
    const gold = parseColor(t['--accent'] as string)
    expect(contrast(gold, parseColor(t['--surface'] as string))).toBeLessThan(TEXT)
    expect(contrast(parseColor(t['--accent-foreground'] as string), gold)).toBeGreaterThanOrEqual(TEXT)
  })

  it('keeps destructive actions distinguishable from the primary red', () => {
    const t = tokens('wf')
    const primary = parseColor(t['--primary'] as string)
    for (const name of ['--danger', '--danger-solid', '--danger-solid-hover']) {
      const colour = parseColor(t[name] as string)
      // Darker than the bright primary, so an outline or fill in this color is clearly not the primary action.
      expect(colour.r + colour.g + colour.b, name).toBeLessThan(primary.r + primary.g + primary.b)
      expect(contrast(colour, primary), name).toBeGreaterThanOrEqual(1.5)
    }
  })
})

describe('the Tailwind theme', () => {
  it('only maps colors to tokens that exist', () => {
    const unknown: string[] = []
    for (const [name, value] of Object.entries(themeMappings)) {
      const ref = /^var\((--[a-z0-9-]+)\)$/.exec(value)
      if (name.startsWith('--color-') && ref && !((ref[1] as string) in baseTokens)) unknown.push(`${name} -> ${ref[1]}`)
    }
    expect(unknown).toEqual([])
  })
})
