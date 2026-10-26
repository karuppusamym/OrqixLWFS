import { describe, expect, it } from 'vitest'
import { activeMention, insertMention, keptMentions, splitMentions } from './mentions'

const paul = { id: 'p', displayName: 'Paul Publisher' }
const pat = { id: 'q', displayName: 'Pat' }

describe('mention text helpers (CMP01)', () => {
  it('finds the mention being typed only after whitespace or at the start', () => {
    expect(activeMention('@pa', 3)).toEqual({ start: 0, query: 'pa' })
    expect(activeMention('hi @Paul P', 10)).toEqual({ start: 3, query: 'Paul P' })
    expect(activeMention('mail a@b', 8)).toBeNull()
    expect(activeMention('@ x', 3)).toBeNull()
    expect(activeMention('@pa\nx', 5)).toBeNull()
    expect(activeMention('no at', 5)).toBeNull()
  })

  it('inserts the chosen name and keeps the rest of the text', () => {
    expect(insertMention('ask @pa now', 4, 7, 'Paul Publisher')).toEqual({ text: 'ask @Paul Publisher now', caret: 20 })
  })

  it('keeps only chosen people still named in the text, once, at most ten', () => {
    expect(keptMentions('hi @paul publisher', [paul, paul, pat])).toEqual([paul])
    const many = Array.from({ length: 12 }, (_, i) => ({ id: `u${i}`, displayName: `U${i}x` }))
    expect(keptMentions(many.map((m) => `@${m.displayName}`).join(' '), many)).toHaveLength(10)
  })

  it('splits a comment into text and stored mentions, longest name first, never others', () => {
    const parts = splitMentions('Hi @Paul Publisher and @Pat and @Someone', [pat, paul])
    expect(parts.filter((p) => p.kind === 'mention').map((p) => p.text)).toEqual(['@Paul Publisher', '@Pat'])
    expect(parts.map((p) => p.text).join('')).toBe('Hi @Paul Publisher and @Pat and @Someone')
    expect(splitMentions('plain', [])).toEqual([{ kind: 'text', text: 'plain' }])
  })
})
