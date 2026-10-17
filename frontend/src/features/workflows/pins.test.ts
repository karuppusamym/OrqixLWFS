import { describe, expect, it, vi } from 'vitest'
import { changePin, parsePins, PIN_LIMIT, pinsKey } from './pins'

const id = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
describe('personal workflow pin storage', () => {
  it('scopes pins to both subject and workspace and stores only versioned IDs', () => {
    const key = pinsKey('alice', 'workspace')
    expect(changePin(key, id(1))).toBeNull()
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual({ version: 1, ids: [id(1)] })
    expect(parsePins(localStorage.getItem(pinsKey('bob', 'workspace')))).toEqual([])
    expect(parsePins(localStorage.getItem(pinsKey('alice', 'other')))).toEqual([])
    expect(pinsKey('a:b', 'c')).not.toBe(pinsKey('a', 'b:c'))
    expect(changePin(key, id(1))).toBeNull()
    expect(parsePins(localStorage.getItem(key))).toEqual([])
  })

  it('caps pins without overwriting existing IDs and allows unpinning at the cap', () => {
    const key = pinsKey('alice', 'workspace')
    for (let n = 1; n <= PIN_LIMIT; n++) expect(changePin(key, id(n))).toBeNull()
    const before = localStorage.getItem(key)
    expect(changePin(key, id(11))).toMatch(/up to 10/)
    expect(localStorage.getItem(key)).toBe(before)
    expect(changePin(key, id(1))).toBeNull()
    expect(changePin(key, id(11))).toBeNull()
    expect(parsePins(localStorage.getItem(key))).toHaveLength(PIN_LIMIT)
  })

  it.each([null, '', '{', 'null', '[]', '{"version":2,"ids":[]}', '{"version":1,"ids":["../secret"]}', 'x'.repeat(4097), JSON.stringify({ version: 1, ids: Array.from({ length: 11 }, (_, n) => id(n)) })])('ignores malformed or oversized storage %s', raw => {
    expect(parsePins(raw)).toEqual([])
  })

  it('deduplicates IDs and refuses invalid IDs without writing', () => {
    expect(parsePins(JSON.stringify({ version: 1, ids: [id(1), id(1)] }))).toEqual([id(1)])
    expect(changePin('bad', '../../secret')).toMatch(/cannot be pinned/)
    expect(localStorage.getItem('bad')).toBeNull()
  })

  it('reports blocked or full storage without claiming success or mutating stored pins', () => {
    const key = pinsKey('alice', 'workspace')
    expect(changePin(key, id(1))).toBeNull()
    const before = localStorage.getItem(key)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    expect(changePin(key, id(2))).toMatch(/Could not save/)
    expect(localStorage.getItem(key)).toBe(before)
  })
})
