import { describe, expect, it } from 'vitest'

/**
 * Components never carry colors of their own: every color is a theme token in src/index.css, so a brand switch recolors
 * the whole application (including the canvas). This scans the source for hex and rgb() literals.
 */
const sources = import.meta.glob<string>('../**/*.{ts,tsx,css}', { query: '?raw', import: 'default', eager: true })

const skip = (file: string) =>
  file.endsWith('/index.css') || /\.test\.tsx?$/.test(file) || /\/(mocks|test|styles)\//.test(file) || file.endsWith('schema.d.ts')

describe('no hardcoded colors outside the tokens', () => {
  it('scans the whole source tree', () => {
    expect(Object.keys(sources).filter((f) => !skip(f)).length).toBeGreaterThan(100)
  })

  it('finds no hex colors or rgb()/hsl() literals in components, features, lib or css', () => {
    const offenders: string[] = []
    for (const [file, text] of Object.entries(sources)) {
      if (skip(file)) continue
      // "#" followed by 3, 4, 6 or 8 hex digits and nothing word-like after it (so "#main" or "#f" fragments do not count).
      for (const m of text.matchAll(/(?<![\w&/])#([0-9a-fA-F]{3,8})(?![\w-])/g)) {
        if ([3, 4, 6, 8].includes((m[1] as string).length)) offenders.push(`${file}: ${m[0]}`)
      }
      // Colors built from a token, such as rgb(var(--shadow-rgb) / 0.2), are fine.
      for (const m of text.matchAll(/\b(?:rgba?|hsla?)\((?!var\()/g)) offenders.push(`${file}: ${m[0]}`)
    }
    expect(offenders).toEqual([])
  })
})
