import indexCss from '../index.css?raw'

/**
 * Reads the design tokens straight from src/index.css, so the contrast checks cannot drift from what ships.
 * Test-only helper.
 */
export type Brand = 'orqix' | 'wf'

const css = indexCss.replace(/\/\*[\s\S]*?\*\//g, '')

function block(selector: RegExp): string {
  const match = selector.exec(css)
  if (!match) throw new Error(`Block not found: ${selector}`)
  return match[1] as string
}

function declarations(body: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const m of body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)) out[m[1] as string] = (m[2] as string).trim()
  return out
}

export const baseTokens = declarations(block(/:root\s*\{([^}]*)\}/))
export const wfOverrides = declarations(block(/:root\[data-brand='wf'\]\s*\{([^}]*)\}/))
export const themeMappings = declarations(block(/@theme inline\s*\{([^}]*)\}/))

/** All tokens of a brand: the base (orqix) palette, with the wf overrides applied on top for `wf`. */
export function tokens(brand: Brand): Record<string, string> {
  return brand === 'wf' ? { ...baseTokens, ...wfOverrides } : { ...baseTokens }
}

export interface Rgba {
  r: number
  g: number
  b: number
  a: number
}

export function parseColor(value: string): Rgba {
  const v = value.trim().toLowerCase()
  const hex = /^#([0-9a-f]{6})$/.exec(v)
  if (hex) {
    const n = parseInt(hex[1] as string, 16)
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 }
  }
  const rgb = /^rgb\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)(?:\s*[/,]\s*([\d.]+))?\s*\)$/.exec(v)
  if (rgb) return { r: +(rgb[1] as string), g: +(rgb[2] as string), b: +(rgb[3] as string), a: rgb[4] === undefined ? 1 : +rgb[4] }
  throw new Error(`Unsupported color: ${value}`)
}

/** Composite a (possibly translucent) color over an opaque one. */
export function over(top: Rgba, bottom: Rgba): Rgba {
  const a = top.a
  return { r: top.r * a + bottom.r * (1 - a), g: top.g * a + bottom.g * (1 - a), b: top.b * a + bottom.b * (1 - a), a: 1 }
}

function channel(c: number): number {
  const s = c / 255
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}

export function luminance(c: Rgba): number {
  return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
}

/** WCAG 2.x contrast ratio between two opaque colors. */
export function contrast(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number]
  return (hi + 0.05) / (lo + 0.05)
}
